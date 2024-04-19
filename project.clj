(defproject dignum "0.1.0-SNAPSHOT"
  :description "A REST API generator for XTDB records"
  :url "https://github.com/elh/dignum"
  :license {:name "The MIT License"
            :url "http://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [org.clojure/data.json "2.5.0"]
                 [ring/ring-jetty-adapter "1.12.1"]
                 [ring/ring-json "0.5.1"]
                 [com.xtdb/xtdb-http-client "1.24.3"]
                 [jinx "0.1.6"]
                 [com.fasterxml.jackson.core/jackson-databind "2.17.0"]
                 [com.github.java-json-tools/json-patch "1.13"]]
  :main ^:skip-aot dignum.server
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}}
  :plugins [[lein-exec "0.3.7"]])
