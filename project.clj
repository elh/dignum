(defproject dignum "0.1.0-SNAPSHOT"
  :description "Dignum Server"
  :url "https://github.com/elh/dignum"
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [ring/ring-jetty-adapter "1.10.0"]]
  :main ^:skip-aot dignum.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}})
