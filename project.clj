(defproject com.palletops/awaze "0.1.1"
  :description "A pallet library for AWS, using the AWS java SDK."
  :url "https://github.com/pallet/awaze"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [com.amazonaws/aws-java-sdk "1.6.4"
                  :exclusions [commons-logging]]
                 [fipp "0.4.1"]
                 [joda-time "2.1"]]
  :source-paths ["src" "dev-src" "target/generated"]
  :global-vars {*warn-on-reflection* true}
  :main ^:skip-aot com.palletops.awaze.client-builder
  :prep-tasks [["with-profile" "+gen,+dev" "run" "pp"]  "compile"]
  :aliases {"gen" ["with-profile" "+gen,+dev" "run" "pp"]})
