{:dev {:dependencies [[org.clojure/clojure "1.5.1"] ; for fipp
                      [ch.qos.logback/logback-classic "1.0.9"]
                      [org.slf4j/jcl-over-slf4j "1.7.5"]
                      [org.clojure/tools.logging "0.2.6"]]
       :source-paths ["src" "dev-src" "target/generated"]
       ;; We would like to create aliases in the dev profile, but this
       ;; causes a stack overflow in lein 2.0.0
       ;; https://github.com/technomancy/leiningen/pull/993
       ;; :aliases {"marg" ["with-profile" "+doc"
       ;;                   "marg" "-d" "autodoc/marginalia/0.8/"]
       ;;           "codox" ["with-profile" "+doc" "doc"]
       ;;           "doc" ["do" "codox," "marg"]}
       :checkout-deps-shares [:source-paths :test-paths :resource-paths
                              :compile-path]
       :plugins [[codox/codox.leiningen "0.6.4"]
                 [lein-marginalia "0.7.1"]]}
 :gen {:prep-tasks ^:replace []}
 :doc {:dependencies [[com.palletops/pallet-codox "0.1.0"]]
       :codox {:sources ["src" "target/generated"]
               :writer codox-md.writer/write-docs
               :output-dir "doc/0.1/api"
               :src-dir-uri "https://github.com/pallet/awaze/blob/develop"
               :src-linenum-anchor-prefix "L"}
       :aliases {"marg" ["marg" "-d" "doc/0.1/"]
                 "codox" ["doc"]
                 "doc" ["do" "codox," "marg"]}}
 :release
 {:plugins [[lein-set-version "0.3.0"]]
  :set-version
  {:updates [{:path "README.md" :no-snapshot true}]}}
 :no-checkouts {:checkout-shares ^:replace []} ; disable checkouts
 :clojure-1.5.0 {:dependencies [[org.clojure/clojure "1.5.0"]]}}
