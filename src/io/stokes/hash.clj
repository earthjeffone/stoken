(ns io.stokes.hash
  (:require [secp256k1.hashes :as digest]))

(defn of-byte-array [ba]
  (-> ba
      digest/sha256
      digest/sha256
      java.math.BigInteger.
      .abs
      (.toString 16)))

(defn of
  "returns the hash of `data`. NOTE: this function may not return the same hash for two different instances of `data` that are `=`. This fact is a known bug."
  [data]
  (-> data
      sort
      pr-str
      of-byte-array))

(defn- make-node [[left right]]
  {:hash (of (map :hash [left right]))
   :left left
   :right right})

(defn- build-tree [leaves]
  (if (= (count leaves) 1)
    (first leaves)
    (->> leaves
         (partition 2)
         (map make-node)
         build-tree)))

(defn- make-leaf [data]
  {:hash  (of data)
   :left  nil
   :right nil})

(defn- make-even [seq]
  (if (= 0 (mod (count seq) 2))
    seq
    (conj seq (last seq))))

(defn tree-of
  "builds a binary Merkle tree out of the seq `data`"
  [data]
  (when (seq data)
    (let [leaves (->> data
                      (make-even)
                      (map make-leaf))]
      (build-tree leaves))))

(defn root-of
  "returns the root hash of a Merkle tree as produced by `tree-of`"
  [hash-tree]
  (get hash-tree :hash (of "")))
