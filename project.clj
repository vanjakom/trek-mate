(defproject com.mungolab/trek-mate "0.1.0-SNAPSHOT"
  :description "http://trek-mate.eu"
  :url "https://github.com/vanjakom/trek-mate"
  :jvm-opts ["-Xmx4g"]
  :dependencies [
                 [org.clojure/clojure "1.8.0"]
                 [com.mungolab/clj-common "0.2.0-SNAPSHOT"]
                 [com.mungolab/clj-geo "0.1.0-SNAPSHOT"]
                 [com.mungolab/clj-cloudkit "0.1.0-SNAPSHOT"]]
  :repl-options {:nrepl-middleware
                 [cider.nrepl/wrap-apropos
                  cider.nrepl/wrap-classpath
                  cider.nrepl/wrap-complete
                  cider.nrepl/wrap-debug
                  cider.nrepl/wrap-format
                  cider.nrepl/wrap-info
                  cider.nrepl/wrap-inspect
                  cider.nrepl/wrap-macroexpand
                  cider.nrepl/wrap-ns
                  cider.nrepl/wrap-spec
                  cider.nrepl/wrap-pprint
                  cider.nrepl/wrap-pprint-fn
                  cider.nrepl/wrap-profile
                  cider.nrepl/wrap-refresh
                  cider.nrepl/wrap-resource
                  cider.nrepl/wrap-stacktrace
                  cider.nrepl/wrap-test
                  cider.nrepl/wrap-trace
                  cider.nrepl/wrap-out
                  cider.nrepl/wrap-undef
                  cider.nrepl/wrap-version]})
