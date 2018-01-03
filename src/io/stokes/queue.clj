(ns io.stokes.queue
  (:require [clojure.core.async :as async]
            [com.stuartsierra.component :as component]
            [io.stokes.state :as state]
            [io.stokes.p2p :as p2p]))

(defn new []
  (async/chan))

(def tag :tag)

(defn dispatch [msg & rest]
  (tag msg))

(defn- submit [queue work]
  (async/go
    (async/>! queue work)))

(defn- with-tag
  ([key] {tag key})
  ([key msg]
   {tag key
    key msg}))

(defn ->transaction [transaction]
  (with-tag
    :transaction transaction))

(defn ->block [block]
  (with-tag
    :block block))

(defn submit-transaction [queue transaction]
  (submit queue (->transaction transaction)))

(defn submit-block [queue block]
  (submit queue (->block block)))

(defn submit-inventory [queue {:keys [blocks transactions]}]
  (for [block blocks]
    (submit-block queue block))
  (for [transaction transactions]
    (submit-transaction queue transaction)))

(defn submit-request-for-inventory [queue]
  (submit queue (with-tag :inventory)))

(defn submit-request-to-mine [queue]
  (submit queue (with-tag :mine)))