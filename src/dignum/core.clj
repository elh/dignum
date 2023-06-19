(ns dignum.core
  (:gen-class)
  (:require [ring.adapter.jetty :as jetty]))

(defn handler [_]
  {:status 200
   :headers {"Content-Type" "text/plain"}
   :body "Hello World"})

(defn -main
  [& _]
  (jetty/run-jetty handler {:port 3000}))
