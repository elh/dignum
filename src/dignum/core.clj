(ns dignum.core
  (:gen-class)
  (:require [ring.adapter.jetty :as jetty]
            [ring.util.response :as util]
            [ring.middleware.json :as ring-json]))

(defn handler [_]
  ;; (println req)
  (util/response {:foo "bar"}))

(defn -main
  [& _]
  (jetty/run-jetty (-> handler
                       ring-json/wrap-json-response)
                   {:port 3000}))
