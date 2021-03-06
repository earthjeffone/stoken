(defproject io.stokes/stoken "0.1.0-SNAPSHOT"
  :description "a simple proof-of-work blockchain"
  :url "github.com/ralexstokes/stoken"
  :license {:name "MIT License"
            :url "https://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/core.async "0.3.465"]
                 [crypto-random "1.2.0"]
                 [digest "1.4.6"]
                 [clj-time "0.14.0"]
                 [http-kit "2.2.0"]
                 [compojure "1.6.0"]
                 [org.clojure/data.json "0.2.6"]
                 [com.github.Sepia-Officinalis/secp256k1 "fd44e1e0d6"]
                 [rm-hull/base58 "0.1.0"]
                 [aleph "0.4.4"]
                 [gloss "0.2.6"]
                 [manifold "0.1.6"]
                 [me.raynes/fs "1.4.6"]
                 [lock-key "1.5.0"]
                 [com.stuartsierra/component "0.3.2"]]
  :repositories [["jitpack" "https://jitpack.io"]]
  :profiles {:dev {:dependencies [[org.clojure/tools.namespace "0.2.11"]
                                  [com.stuartsierra/component.repl "0.2.0"]]
                   :source-paths ["dev"]}})
