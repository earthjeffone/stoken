(ns io.stokes.miner
  (:require [com.stuartsierra.component :as component]
            [io.stokes.hash :as hash]
            [io.stokes.block :as block]
            [io.stokes.transaction :as transaction]
            [io.stokes.transaction-pool :as transaction-pool]
            [io.stokes.address :as address]
            [clojure.core.async :as async]
            [clj-time.core :as time]
            [clj-time.coerce :as coerce]))

(defn valid-block? [block]
  (and (block :hash)
       block))

(defn- calculate-threshold [max-threshold difficulty]
  (.shiftRight max-threshold difficulty))

(defn- hex->bignum [str]
  (BigInteger. str 16))

(defn- sealed? [block max-threshold]
  "a proof-of-work block is sealed when the block hash is less than a threshold determined by the difficulty"
  (let [threshold (calculate-threshold max-threshold (block/difficulty block))
        hash (-> block
                 block/hash
                 hex->bignum)]
    (< hash threshold)))

(defn- prepare-block [block nonce]
  (let [block (assoc block :nonce nonce)]
    (assoc block :hash (block/hash block))))

(defn- mine-range [block seed number-of-rounds max-threshold]
  (loop [count number-of-rounds
         nonce seed]
    (when (pos? count)
      (let [block (prepare-block block nonce)]
        (if (sealed? block max-threshold)
          block
          (recur (dec count)
                 (inc nonce)))))))

(defn- select-transactions [pool]
  (transaction-pool/take-by-fee pool 20))

(defn- build-coinbase-transaction [address subsidy]
  (transaction/from (address/zero) address subsidy 0))

(defn- derive-next-block [chain transactions]
  (block/next-template chain transactions))

(defn mine [{:keys [number-of-rounds coinbase max-threshold] :or {number-of-rounds 250}} chain transaction-pool]
  (let [seed (rand-int 10000000) ;; TODO pick a better ceiling?
        subsidy 100 ;; TODO calculate subsidy
        transactions (select-transactions transaction-pool)
        coinbase-transaction (build-coinbase-transaction coinbase subsidy)
        next-block (derive-next-block chain (concat [coinbase-transaction]
                                                    transactions))]
    (mine-range next-block seed number-of-rounds max-threshold)))

(defn new [config]
  (merge config {:channel (atom nil)}))
