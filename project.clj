(defproject dignum "0.1.0-SNAPSHOT"
  :description "Dignum Server"
  :url "https://github.com/elh/dignum"
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [org.clojure/data.json "2.4.0"]
                 [ring/ring-jetty-adapter "1.10.0"]
                 [ring/ring-json "0.5.1"]
                 [com.xtdb/xtdb-http-client "1.23.3"]]
  :main ^:skip-aot dignum.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}})
