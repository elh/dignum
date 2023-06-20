(ns dignum.core
  (:gen-class)
  (:require [clojure.walk :as walk]
            [clojure.set :as set]
            [clojure.string :as str]
            [ring.adapter.jetty :as jetty]
            [ring.util.response :as ring-response]
            [ring.middleware.json :as ring-json]
            [ring.middleware.params :as ring-params]
            [xtdb.api :as xt]
            [juxt.jinx-alpha :as jinx]))

(def type-schema {"type" "object"
                  "properties" {"_id" {"type" "string"
                                       "pattern" "^type\\/.*"}
                                "schema" {"type" "object"}},
                  "required" ["_id", "schema"]
                  "additionalProperties" false})

(defn- log [m]
  (println m))

;; hack. I expected wrap-json-body to handle this for us
(defn- is-empty-body? [body]
  (= (.getName (type body)) "org.eclipse.jetty.server.HttpInputOverHTTP"))

(defn- to-xtdb [record]
  (-> record
      (walk/keywordize-keys)
      (set/rename-keys {:_id :xt/id})))

(defn- to-rest [xt-record]
  (-> xt-record
      (walk/stringify-keys)
      (set/rename-keys {:xt/id :_id})))

(defn- uri-parts [uri]
  (str/split (str/replace-first uri #"/" "") #"/"))

;; TODO: generalize create-type and create-record?
;; TODO: check that type with id does not already exist. also check for "type/type"
;; TODO: should schemas enforce additionalProperties=false?
;; TODO: should we ensure schemas do not break core fields like id?
(defn create-type [xtdb-client record]
  (let [validation (jinx/validate
                    record
                    (jinx/schema type-schema))]
    (if (not (:valid? validation))
      {:status 400
       :body {:message (str "Failed validation: " (:errors validation))}}
      (try
        (jinx/schema (get record "schema"))
        (let [xt-record (assoc (to-xtdb record) :_type "type/type")
              tx (xt/submit-tx xtdb-client [[::xt/put xt-record]])]
          (xt/await-tx xtdb-client tx)
          (ring-response/response (to-rest xt-record)))
        (catch Exception e
          {:status 400
           :body {:message (str "Failed validation: " e)}})))))

(defn create-record [xtdb-client type record]
  (let [xt-type-record (xt/entity (xt/db xtdb-client) (str "type/" type))]
    (if (nil? xt-type-record)
      {:status 404
       :body {:message (str "Type not found: " type)}}
      (let [type-record (to-rest xt-type-record)
            validation (jinx/validate
                        record
                        (jinx/schema (get type-record "schema")))]
        (if (not (:valid? validation))
          {:status 400
           :body {:message (str "Failed validation: " (:errors validation))}}
          (let [id (.toString (java.util.UUID/randomUUID))
                xt-record (-> (to-xtdb record)
                              (assoc :xt/id (str type "/" id))
                              (assoc :_type (str "type/" type)))
                tx (xt/submit-tx xtdb-client [[::xt/put xt-record]])]
            (xt/await-tx xtdb-client tx)
            (ring-response/response (to-rest xt-record))))))))

(defn create-handler [xtdb-client req]
  (if (nil? (:body req))
    {:status 400
     :body {:message "'body' is required"}}
    (let [parts (uri-parts (:uri req))]
      (if (not= (count parts) 1)
        {:status 400
         :body {:message "Invalid type in uri"}}
        (let [type (first parts)
              record (:body req)]
          (case type
            "type" (create-type xtdb-client record)
            (create-record xtdb-client type record)))))))

(defn get-handler [xtdb-client req]
  (let [parts (uri-parts (:uri req))]
    (if (not= (count parts) 2)
      {:status 400
       :body {:message "Invalid uri"}}
      (let [type (first parts)
            id (second parts)
            xt-record (xt/entity (xt/db xtdb-client) (str type "/" id))]
        (if (nil? xt-record)
          {:status 404
           :body {:message "Not Found"}}
          (ring-response/response (to-rest xt-record)))))))

(defn handler [xtdb-client req]
  (let [req (if (is-empty-body? (:body req))
              (assoc req :body nil)
              req)]
    (log {:msg "request received" :req req})
    (case (:request-method req)
      :post (create-handler xtdb-client req)
      :get (get-handler xtdb-client req)
      {:status 501
       :body {:message "Unimplemented"}})))

(defn -main []
  (log "Starting server")
  (let [xtdb-url (or (System/getenv "XTDB_URL") "http://localhost:9999")
        xtdb-client (xt/new-api-client xtdb-url)]
    (jetty/run-jetty (-> (partial handler xtdb-client)
                         ring-json/wrap-json-response
                         ring-json/wrap-json-body
                         ring-params/wrap-params)
                     {:port 3000})))
