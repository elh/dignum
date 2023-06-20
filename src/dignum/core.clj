(ns dignum.core
  (:gen-class)
  (:require [ring.adapter.jetty :as jetty]
            [ring.util.response :as ring-response]
            [ring.middleware.json :as ring-json]
            [ring.middleware.params :as ring-params]
            [clojure.data.json :as json]))

(defn- log [m]
  (println (json/write-str m)))

;; hack. I expected wrap-json-body to handle this for us
(defn- is-empty-body? [body]
  (= (.getName (type body)) "org.eclipse.jetty.server.HttpInputOverHTTP"))

(defn handler [req]
  (let [req (if (is-empty-body? (:body req))
              (assoc req :body nil)
              req)]
    (log {:msg "request received" :req req})
    (ring-response/response {:foo "bar"})))

(defn -main
  [& _]
  (jetty/run-jetty (-> handler
                       ring-json/wrap-json-response
                       ring-json/wrap-json-body
                       ring-params/wrap-params)
                   {:port 3000}))
