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

;; TODO: add tests
;; TODO: implement PUT
;; TODO: implement DELETE
;; TODO: document

(def type-schema {"type" "object"
                  "properties" {"schema" {"type" "object"}},
                  "required" ["schema"]
                  "additionalProperties" false})

(defn- log [m]
  (println m))

;; hack. I expected wrap-json-body to handle this for us
(defn- is-empty-body? [body]
  (= (.getName (type body)) "org.eclipse.jetty.server.HttpInputOverHTTP"))

(defn- to-xtdb [record]
  (-> record
      (walk/keywordize-keys)
      (set/rename-keys {:_name :xt/id})))

(defn- to-rest [xt-record]
  (-> xt-record
      (set/rename-keys {:xt/id :_name})
      (walk/stringify-keys)))

(defn- uri-parts [uri]
  (str/split (str/replace-first uri #"/" "") #"/"))

;; type schemas only validate non-underscore, non-system fields
(defn- remove-underscore-keys [m]
  (apply dissoc m (filter #(str/starts-with? % "_") (keys m))))

(defn create-type [xtdb-node record]
  (let [record (assoc record "_type" "type/type")
        validation (jinx/validate
                    (remove-underscore-keys record)
                    (jinx/schema type-schema))]
    (cond
      (not (:valid? validation))
      {:status 400
       :body {:message (str "Failed validation: " (:errors validation))}}

      (not (contains? record "_name"))
      {:status 400
       :body {:message "'_name' is required and must be of the form 'type/<id>'"}}

      (not (re-matches #"type/\w+" (get record "_name")))
      {:status 400
       :body {:message "'_name' must be of the form 'type/<id>'"}}

      (= (get record "_name") "type/type")
      {:status 403
       :body {:message "Cannot create or update 'type/type'"}}

      (some? (xt/entity (xt/db xtdb-node) (get record "_name")))
      {:status 403
       :body {:message (str "Type already exists: " (get record "_name"))}}

      :else
      (try
        ;; exception thrown if schema is invalid
        (jinx/schema (get record "schema"))
        (let [xt-record (to-xtdb record)
              tx (xt/submit-tx xtdb-node [[::xt/put xt-record]])]
          (xt/await-tx xtdb-node tx)
          (ring-response/response (to-rest xt-record)))
        (catch Exception e
          {:status 400
           :body {:message (str "Failed validation: " e)}})))))

(defn create-record [xtdb-node type record]
  (let [record (-> record
                   (assoc "_type" (str "type/" type))
                   (assoc "_name" (str type "/" (.toString (java.util.UUID/randomUUID)))))
        xt-type-record (xt/entity (xt/db xtdb-node) (str "type/" type))]
    (if (nil? xt-type-record)
      {:status 404
       :body {:message (str "Type not found: " type)}}
      (let [type-record (to-rest xt-type-record)
            validation (jinx/validate
                        (remove-underscore-keys record)
                        (jinx/schema (get type-record "schema")))]
        (if (not (:valid? validation))
          {:status 400
           :body {:message (str "Failed validation: " (:errors validation))}}
          (let [tx (xt/submit-tx xtdb-node [[::xt/put (to-xtdb record)]])]
            (xt/await-tx xtdb-node tx)
            (ring-response/response record)))))))

(defn create-handler [xtdb-node req]
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
            "type" (create-type xtdb-node record)
            (create-record xtdb-node type record)))))))

(defn get-handler [xtdb-node req]
  (let [parts (uri-parts (:uri req))]
    (if (not= (count parts) 2)
      {:status 400
       :body {:message "Invalid uri"}}
      (let [type (first parts)
            id (second parts)
            xt-record (xt/entity (xt/db xtdb-node) (str type "/" id))]
        (if (nil? xt-record)
          {:status 404
           :body {:message "Not Found"}}
          (ring-response/response (to-rest xt-record)))))))

(defn handler [xtdb-node req]
  (let [req (if (is-empty-body? (:body req))
              (assoc req :body nil)
              req)]
    (log {:msg "request received" :req req})
    (case (:request-method req)
      :post (create-handler xtdb-node req)
      :get (get-handler xtdb-node req)
      {:status 501
       :body {:message "Unimplemented"}})))

(defn -main []
  (log "Starting server")
  (let [xtdb-url (or (System/getenv "XTDB_URL") "http://localhost:9999")
        xtdb-node (xt/new-api-client xtdb-url)]
    (jetty/run-jetty (-> (partial handler xtdb-node)
                         ring-json/wrap-json-response
                         ring-json/wrap-json-body
                         ring-params/wrap-params)
                     {:port 3000})))
