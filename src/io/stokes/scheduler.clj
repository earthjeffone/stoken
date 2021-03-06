(ns io.stokes.scheduler
  (:require [com.stuartsierra.component :as component]
            [clojure.core.async :as async]
            [io.stokes.p2p :as p2p]
            [io.stokes.miner :as miner]
            [io.stokes.state :as state]
            [io.stokes.queue :as queue]
            [clojure.set :as set]
            [io.stokes.transaction :as transaction]
            [io.stokes.block :as block]))

(defn- cancel-miner [{:keys [channel]}]
  (when-let [cancel @channel]
    (async/close! cancel)))

(defn- publish-block [block queue p2p]
  (queue/submit-block queue block)
  (p2p/send-block p2p block))

(defn- run-miner [{:keys [queue miner p2p state]}]
  (let [channel (:channel miner)
        cancel (async/chan)]
    (async/go-loop []
      (let [[_ channel] (async/alts! [cancel] :default :continue)]
        (when-not (= channel cancel)
          (let [[chain transaction-pool] (state/reader state
                                                       (comp block/best-chain :blockchain)
                                                       :transaction-pool)]
            (if-let [block (miner/mine miner chain transaction-pool)]
              (publish-block block queue p2p)
              (recur))))))
    (reset! channel cancel)))

(defmulti dispatch queue/dispatch)

(defmethod dispatch :block [{:keys [block]} {:keys [state queue miner] :as scheduler}]
  (when
      (and (not (state/contains-block? state block))
           (let [[chain ledger] (state/reader state
                                              :blockchain
                                              :ledger)
                 {:keys [max-threshold
                         halving-frequency
                         base-block-reward]} miner]
             (block/valid? chain max-threshold ledger block halving-frequency base-block-reward)))
    (state/add-block state block)
    (queue/submit-request-to-mine queue)))

(defmethod dispatch :transaction [{:keys [transaction]} {:keys [state p2p]}]
  (when
      (and (not (state/contains-transaction? state transaction))
           (transaction/valid? (state/->ledger state) transaction))
    (state/add-transaction state transaction)
    (p2p/send-transaction p2p transaction)))

(defmethod dispatch :mine [{force? :mine} {:keys [state queue miner total-blocks] :as scheduler}]
  (cancel-miner miner)
  (if total-blocks
    (let [chain (state/reader state (comp block/best-chain :blockchain))]
      (when (or force?
                (pos? (- total-blocks (count chain))))
        (run-miner scheduler)))
    (run-miner scheduler)))

(defmethod dispatch :peers [{peer-set :peers} {:keys [p2p]}]
  (let [new-peers (p2p/merge-into-peer-set p2p peer-set)]
    (when-not (empty? (set/difference new-peers peer-set))
      (p2p/announce p2p new-peers))))

(defmethod dispatch :request-inventory [_ {:keys [p2p queue]}]
  (p2p/request-inventory p2p)
  (async/go
    (async/<! (async/timeout (* 1000
                                (rand-int 20))))
    (queue/submit-request-inventory queue)))

(defmethod dispatch :inventory-request [request {:keys [p2p state]}]
  (let [inventory (state/->inventory state)]
    (p2p/send-inventory p2p request inventory)))

(defmethod dispatch :default [msg _]
  (println "unknown message type:" msg))

(defn- start-worker [{:keys [queue] :as scheduler}]
  (let [stop (async/chan)]
    (async/go-loop [[msg channel] (async/alts! [stop queue])]
      (when-not (= channel stop)
        (when msg
          (try
            (dispatch msg scheduler)
            (catch Exception e
              (prn e)))
          (recur (async/alts! [stop queue])))))
    stop))

(defn- start-workers [{:keys [number-of-workers] :as scheduler}]
  (doall
   (map (fn [_] (start-worker scheduler)) (range number-of-workers))))

(defn- stop-worker [worker]
  (async/close! worker))

(defn- stop-workers [workers]
  (doall
   (map stop-worker workers)))

(defn- begin-query-peers [queue]
  (queue/submit-request-inventory queue))

(defn- begin-mining [queue]
  (async/go
    (Thread/sleep 1000)
    ;; this is to give peers time to connect
    ;; this is a pretty hacky solution but the proper fix will take some thorough refactoring so for now...
    (queue/submit-request-to-mine queue)))

(defn- start [{:keys [queue p2p] :as scheduler}]
  (let [workers (start-workers scheduler)]
    (begin-query-peers queue)
    (begin-mining queue)
    workers))

(defrecord Scheduler [state queue p2p miner]
  component/Lifecycle
  (start [scheduler]
    (assoc scheduler :workers (start scheduler)))
  (stop [scheduler]
    (cancel-miner miner)
    (stop-workers (:workers scheduler))
    (dissoc scheduler :workers)))

(defn new [config]
  (component/using (map->Scheduler config)
                   [:state :queue :p2p :miner]))
