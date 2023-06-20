(ns dignum.core
  (:gen-class)
  (:require [clojure.walk :as walk]
            [clojure.set :as set]
            [clojure.string :as str]
            [ring.adapter.jetty :as jetty]
            [ring.util.response :as ring-response]
            [ring.middleware.json :as ring-json]
            [ring.middleware.params :as ring-params]
            [xtdb.api :as xt]))

(defn- log [m]
  (println m))

;; hack. I expected wrap-json-body to handle this for us
(defn- is-empty-body? [body]
  (= (.getName (type body)) "org.eclipse.jetty.server.HttpInputOverHTTP"))

(defn- to-xtdb [record]
  (-> record
      (walk/keywordize-keys)
      (set/rename-keys {:id :xt/id})))

(defn- to-rest [record]
  (-> record
      ;; no need to stringify-keys. wrap-json-response handles
      (set/rename-keys {:xt/id :id})))

(defn create-handler [xtdb-client req]
  ;; TODO: define schemas and validate schema on write
  (let [id (.toString (java.util.UUID/randomUUID))
        record (assoc (to-xtdb (:body req)) :xt/id id)
        tx (xt/submit-tx xtdb-client [[::xt/put record]])]
    (xt/await-tx xtdb-client tx)
    (ring-response/response (to-rest record))))

(defn get-handler [xtdb-client req]
  (let [id (str/replace-first (:uri req) #"/" "")
        record (xt/entity (xt/db xtdb-client) id)]
    (if (nil? record)
      {:status 404
       :body "Not Found"}
      (ring-response/response (to-rest record)))))

(defn handler [xtdb-client req]
  (let [req (if (is-empty-body? (:body req))
              (assoc req :body nil)
              req)]
    (log {:msg "request received" :req req})
    (case (:request-method req)
      :post (create-handler xtdb-client req)
      :get (get-handler xtdb-client req)
      {:status 501
       :body "Unimplemented"})))

(defn -main []
  (let [xtdb-url (or (System/getenv "XTDB_URL") "http://localhost:9999")
        xtdb-client (xt/new-api-client xtdb-url)]
    (jetty/run-jetty (-> (partial handler xtdb-client)
                         ring-json/wrap-json-response
                         ring-json/wrap-json-body
                         ring-params/wrap-params)
                     {:port 3000})))
