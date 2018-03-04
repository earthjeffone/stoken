(ns io.stokes.block
  (:require [io.stokes.hash :as hash]
            [clojure.set :as set]
            [clj-time.core :as time]
            [clj-time.coerce :as coerce]
            [io.stokes.transaction :as transaction])
  (:refer-clojure :exclude [hash]))

(def ^:private block-header-keys #{:previous-hash
                                   :difficulty
                                   :transaction-root
                                   :time
                                   :nonce})

(defn header [block]
  (select-keys block block-header-keys))

(defn hash [block]
  (get block :hash
       (some-> block
               header
               hash/of)))

(defn difficulty [block]
  (get block :difficulty 0))

(defn- previous [block]
  (:previous-hash block))

(defn with-nonce [block nonce]
  (assoc block :nonce nonce))

(defn- calculate-threshold [max-threshold difficulty]
  (.shiftRight max-threshold difficulty))

(defn- hex->bignum [str]
  (BigInteger. str 16))

(defn sealed?
  "a proof-of-work block is sealed when the block hash is less than a threshold determined by the difficulty"
  [block max-threshold]
  (let [threshold (calculate-threshold max-threshold (difficulty block))
        hash (-> block
                 hash
                 hex->bignum)]
    (< hash threshold)))

(defn readable
  "returns a human-readable description of the block"
  [block]
  (update block :time coerce/to-date))

(defn from-readable
  "parses a human-readable description of the block"
  [block]
  (update block :time coerce/from-date))

(def ^:private target-blocktime 10000) ;; milliseconds

(defn- timestamps->blocktimes [[a b]]
  (time/in-seconds
   (time/interval b a)))

(defn- average [default coll]
  (if (seq coll)
    (let [n (count coll)
          sum (reduce + coll)]
      (/ sum n))
    default))

(defn- calculate-average-blocktime
  [timestamps]
  (->> timestamps
       reverse
       (take 4)
       (partition 2)
       (map timestamps->blocktimes)
       (average target-blocktime)))

(defn- calculate-difficulty
  "adjust difficulty so that the average time between blocks is N seconds"
  [block timestamps]
  (let [difficulty (difficulty block)
        average-blocktime (calculate-average-blocktime timestamps)
        next        (if (> average-blocktime target-blocktime)
                      dec
                      inc)]
    (next difficulty)))

(defn- transactions->root [transactions]
  (-> transactions
      hash/tree-of
      hash/root-of))

(defn- header-from [chain transactions]
  (let [previous-block (last chain)
        block-timestamps (map :time chain)]
    {:previous-hash    (hash previous-block)
     :difficulty       (calculate-difficulty previous-block block-timestamps)
     :transaction-root (transactions->root transactions)
     :time             (time/now)
     :nonce            0}))

(defn next-template
  "generates a block with `transactions` that satisfies the constraints to be appended to the chain modulo a valid proof-of-work, i.e. a nonce that satisfies the difficulty in the block, also implies a missing block hash in the returned data. note: timestamp in the block header is currently included in the hash pre-image; given that a valid block must be within some time interval, client code MUST refresh this timestamp as needed; if you are having issues, run the proof-of-work routine for a smaller number of rounds"
  [chain transactions]
  {:post [(let [keys (->> %
                          keys
                          (into #{}))]
            (set/subset? block-header-keys keys))]}
  (merge {:transactions transactions}
         (header-from chain transactions)))

(defn- node->block [node]
  (:block node))
(defn- node->children [node]
  (:children node))

(defn- parent?
  "indicates if a is a parent of b"
  [a b]
  (= (previous b)
     (hash a)))

(defn- same-block?
  "if two blocks have the same hash, they are the same block"
  [a b]
  (= (hash a)
     (hash b)))

(defn- node-of [block & children]
  (merge {:block block} (when children
                          {:children (reduce conj #{} children)})))

(defn- children-contains-block? [children block]
  (let [blocks (map (comp hash :block) children)]
    (some #{(hash block)} blocks)))

(defn- insert [node new-block]
  (let [block (node->block node)
        children (node->children node)]
    (apply node-of block
           (if (children-contains-block? children new-block)
             children
             (if (parent? block new-block)
               (conj children (node-of new-block))
               (map #(insert % new-block) children))))))

(def same-chain? =)

(defn chain-contains-block? [blockchain target-block]
  (let [block (node->block blockchain)
        children (node->children blockchain)]
    (or (same-block? block target-block)
        (some true? (map #(chain-contains-block? % target-block) children)))))

(defn add-to-chain
  "takes a blocktree and a set of blocks, possibly containing orphans; will insert all possible blocks in the set and return the updated tree along with the remaining orphans"
  [blockchain set-of-blocks]
  (let [blocks (into [] set-of-blocks)]
    (if-let [[inserted-block new-chain] (->> blocks
                                             (map #(vector % (insert blockchain %)))
                                             (drop-while (comp (partial same-chain? blockchain) second))
                                             first)]
      (add-to-chain new-chain (disj set-of-blocks inserted-block))
      [blockchain (into #{} (remove (partial chain-contains-block? blockchain) set-of-blocks))])))

(defn tree->blocks
  "collects every block in the tree into a seq of blocks"
  [blockchain]
  (->> (tree-seq :children #(into [] (:children %)) blockchain)
       (map :block)))

;; find the best chain in a given block tree

(defn- total-difficulty [node]
  (let [block (node->block node)
        children (node->children node)]
    (apply + (difficulty block) (map total-difficulty children))))

(defn- select-many-by-key [weight f xs]
  (let [decorated (map (fn [x] [x (f x)]) xs)
        max-key (apply weight (map second decorated))]
    (->> decorated
         (filter #(= max-key (second %)))
         (map first))))

(defn- max-keys [f xs]
  (select-many-by-key max f xs))

(defn- min-keys [f xs]
  (select-many-by-key min f xs))

(defn- select-nodes-with-most-work [nodes]
  (max-keys total-difficulty nodes))

(defn- node->timestamp [node]
  (let [block (node->block node)]
    (coerce/to-long (:time block))))

(defn- select-earliest-nodes [nodes]
  (min-keys node->timestamp nodes))

(defn- fork-choice-rule
  "We use a fork choice rule resembling Bitcoin Core. First find the nodes with the most work, breaking ties by timestamp"
  [nodes]
  (-> nodes
      select-nodes-with-most-work
      select-earliest-nodes
      first))

(defn- collect-best-chain [chain node]
  (let [block (node->block node)
        children (node->children node)
        chain (conj chain block)]
    (if children
      (collect-best-chain chain (fork-choice-rule children))
      chain)))

(defn best-chain
  "accepts the block tree and returns a seq of those blocks on the best chain according to the fork choice rule"
  [blockchain]
  (collect-best-chain [] blockchain))

(defn chain-from [{genesis-block :initial-state}]
  (node-of genesis-block))

(defn valid-proof-of-work? [block max-threshold]
  (sealed? block max-threshold))

(def ^:private default-forward-time-limit-in-hours 2)
(def ^:private default-backward-time-limit-by-blocks 11)

(defn median [times]
  (let [count (count times)
        index (/ (- count 1)
                 2)]
    (nth times index)))

(defn- after-median-of [times new-time]
  (let [median (median times)]
    (time/after? new-time median)))

(defn- within-hours
  "`b` must be within `hours` time of `a`"
  [hours a b]
  (time/within?
   (time/interval a (time/plus a (time/hours hours)))
   b))

(defn reasonable-time?
  "a block cannot be equal to or before the median of the last 11 blocks; a block cannot be more than 2 hours ahead of the latest block"
  [chain block]
  (let [block-time (:time block)]
    (and (after-median-of (->> chain
                               reverse
                               (take default-backward-time-limit-by-blocks)
                               (map :time)) block-time)
         (within-hours default-forward-time-limit-in-hours (->> chain
                                                                last
                                                                :time) block-time))))

(defn proper-transactions? [ledger block]
  (let [transactions (:transactions block)
        coinbase-transaction (first transactions)]
    (and (transaction/coinbase? coinbase-transaction)
         (every? true? (map (partial transaction/valid? ledger) transactions)))))

(defn valid-transaction-root? [block]
  (let [transaction-root (:transaction-root block)
        transactions (:transactions block)]
    (= transaction-root
       (transactions->root  transactions))))

(defn correct-difficulty? [chain block]
  (let [difficulty (difficulty block)
        expected-difficulty (calculate-difficulty (last chain) (map :time chain))]
    (= difficulty
       expected-difficulty)))

(defn valid? [chain max-threshold ledger block]
  (and
   (not (empty? (:transactions block)))
   (valid-proof-of-work? block max-threshold)
   (reasonable-time? chain block)
   (proper-transactions? ledger block)
   (valid-transaction-root? block)
   (correct-difficulty? chain block)) ;; rules causing consensus failure; elide for demo
  block)
