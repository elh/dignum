(ns dignum.core
  (:gen-class)
  (:require [ring.adapter.jetty :as jetty]
            [ring.util.response :as ring-response]
            [ring.middleware.json :as ring-json]
            [clojure.data.json :as json]))

(defn- log [m]
  (println (json/write-str m)))

(defn handler [req]
  (log {:msg "request received" :req req})
  (ring-response/response {:foo "bar"}))

(defn -main
  [& _]
  (jetty/run-jetty (-> handler
                       ring-json/wrap-json-response
                       ring-json/wrap-json-body)
                   {:port 3000}))
