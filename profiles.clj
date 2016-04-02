{:dev {:dependencies [[org.clojure/clojure "1.6.0"]
                      [ch.qos.logback/logback-classic "1.1.1"]
                      [org.slf4j/jcl-over-slf4j "1.7.5"]
                      [org.clojure/tools.logging "0.2.6"]
                      [fipp "0.4.3"]]
       :source-paths ["src" "dev-src" "target/generated"]
       :plugins [[lein-pallet-release "LATEST"]]}
 :gen {:prep-tasks ^:replace []}
 :clojure-1.5 {:dependencies [[org.clojure/clojure "1.5.1"]]}
 :clojure-1.6 {:dependencies [[org.clojure/clojure "1.6.0"]]}}
