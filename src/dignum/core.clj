(ns dignum.core
  (:gen-class)
  (:require [ring.adapter.jetty :as jetty]
            [ring.util.response :as ring-response]
            [ring.middleware.json :as ring-json]
            [ring.middleware.params :as ring-params]
            [xtdb.api :as xt]))

(defn- log [m]
  (println m))

;; hack. I expected wrap-json-body to handle this for us
(defn- is-empty-body? [body]
  (= (.getName (type body)) "org.eclipse.jetty.server.HttpInputOverHTTP"))

(defn create-handler [_ req]
  (ring-response/response (:body req)))

(defn handler [xtdb-client req]
  (let [req (if (is-empty-body? (:body req))
              (assoc req :body nil)
              req)]
    (log {:msg "request received" :req req})
    (case (:request-method req)
      :post (create-handler xtdb-client req)
      {:status 501
       :body "Unimplemented"})))

(defn -main []
  (let [xtdb-url (or (System/getenv "XTDB_URL")
                     "http://localhost:9999")
        xtdb-client (xt/new-api-client xtdb-url)]
    (jetty/run-jetty (-> (partial handler xtdb-client)
                         ring-json/wrap-json-response
                         ring-json/wrap-json-body
                         ring-params/wrap-params)
                     {:port 3000})))
