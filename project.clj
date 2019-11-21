(defproject com.mungolab/trek-mate "0.1.0-SNAPSHOT"
  :description "http://trek-mate.eu"
  :url "https://github.com/vanjakom/trek-mate"
  :jvm-opts ["-Xmx4g"]
  :source-paths ["src/clj" "src/cljc"]
  :dependencies [
                 [org.clojure/clojure "1.10.0"]
                 [org.clojure/clojurescript "1.10.439"]
                 [org.clojure/tools.reader "1.3.0"]

                 [org.openstreetmap.osmosis/osmosis-pbf "0.47"]
                 
                 [com.mungolab/clj-common "0.3.0-SNAPSHOT"]
                 [com.mungolab/clj-geo "0.1.0-SNAPSHOT"]
                 [com.mungolab/clj-cloudkit "0.1.0-SNAPSHOT"]
                 [com.mungolab/clj-scraper "0.1.0-SNAPSHOT"]]
  :cljsbuild {
              :builds [{
                        :source-paths ["src/cljc" "src/cljs"]
                        :compiler {
                                   :output-to "target/core.js"
                                   :optimizations :whitespace
                                   :pretty-print true}}]})
