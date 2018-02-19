(ns io.stokes.queue
  (:require [clojure.core.async :as async]))

(defn new []
  (async/chan))

(def ^:private tag :tag)

(defn dispatch [msg & rest]
  (tag msg))

(defn submit [queue work]
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

(defn ->peer-set [peer-set]
  (with-tag
    :peers peer-set))

(defn submit-transaction [queue transaction]
  (submit queue (->transaction transaction)))

(defn submit-block [queue block]
  (submit queue (->block block)))

(defn submit-request-to-mine [queue]
  (submit queue (with-tag :mine)))

(defn inventory-request []
  (with-tag :inventory-request))

(defn ->inventory [inventory]
  (with-tag :inventory
    inventory))
