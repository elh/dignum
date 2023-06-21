(ns dignum.server
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
;; TODO: implement list
;; TODO: implement PUT
;; TODO: implement DELETE
;; TODO: document
;; TODO: custom hooks OR wrap server. add custom logic
;; TODO: do more transactionally?
;; TODO: parent field?
;; TODO: created and updated timestamps?

(def collections-schema {"type" "object"
                         "properties" {"schema" {"type" "object"}},
                         "required" ["schema"]
                         "additionalProperties" false})

(defn- log [m]
  (println m))

;; hack. I expected wrap-json-body to handle this for us
(defn- is-empty-body? [body]
  (= (.getName (type body)) "org.eclipse.jetty.server.HttpInputOverHTTP"))

(defn- ->xtdb-record [record]
  (-> record
      (walk/keywordize-keys)
      (set/rename-keys {:_name :xt/id})))

(defn- ->rest-record [xt-record]
  (-> xt-record
      (set/rename-keys {:xt/id :_name})
      (walk/stringify-keys)))

(defn- uri-parts [uri]
  (str/split (str/replace-first uri #"/" "") #"/"))

;; user-provided schemas only validate non-underscore, non-system fields
(defn- remove-underscore-keys [m]
  (apply dissoc m (filter #(str/starts-with? % "_") (keys m))))

(defn create-collection [xtdb-node record]
  (let [record (assoc record "_collection" "collections/collections")
        validation (jinx/validate
                    (remove-underscore-keys record)
                    (jinx/schema collections-schema))]
    (cond
      (not (:valid? validation))
      {:status 400
       :body {:message (str "Failed collection resource validation: " (:errors validation))}}

      (not (contains? record "_name"))
      {:status 400
       :body {:message "'_name' is required and must be of the form 'collections/<id>' where <id> should be plural form of the collection resource type"}}

      (not (re-matches #"collections/\w+" (get record "_name")))
      {:status 400
       :body {:message "'_name' must be of the form 'collections/<id>' where <id> should be plural form of the collection resource type"}}

      (= (get record "_name") "collections/collections")
      {:status 403
       :body {:message "Cannot create or update 'collections/collections'"}}

      (some? (xt/entity (xt/db xtdb-node) (get record "_name")))
      {:status 403
       :body {:message (str "Collection already exists: " (get record "_name"))}}

      :else
      (try
        ;; exception thrown if schema is invalid
        (jinx/schema (get record "schema"))
        (let [xt-record (->xtdb-record record)
              tx (xt/submit-tx xtdb-node [[::xt/put xt-record]])]
          (xt/await-tx xtdb-node tx)
          (ring-response/response (->rest-record xt-record)))
        (catch Exception e
          {:status 400
           :body {:message (str "Invalid schema: " e)}})))))

(defn create-record [xtdb-node collection-id record]
  (let [record (-> record
                   (assoc "_collection" (str "collections/" collection-id))
                   (assoc "_name" (str collection-id "/" (.toString (java.util.UUID/randomUUID)))))
        xt-collection (xt/entity (xt/db xtdb-node) (str "collections/" collection-id))]
    (if (nil? xt-collection)
      {:status 404
       :body {:message (str "Collection not found: " collection-id)}}
      (let [collection (->rest-record xt-collection)
            validation (jinx/validate
                        (remove-underscore-keys record)
                        (jinx/schema (get collection "schema")))]
        (if (not (:valid? validation))
          {:status 400
           :body {:message (str "Failed collection resource validation: " (:errors validation))}}
          (let [tx (xt/submit-tx xtdb-node [[::xt/put (->xtdb-record record)]])]
            (xt/await-tx xtdb-node tx)
            (ring-response/response record)))))))

(defn create-handler [xtdb-node req]
  (if (nil? (:body req))
    {:status 400
     :body {:message "'body' is required"}}
    (let [parts (uri-parts (:uri req))]
      (if (not= (count parts) 1)
        {:status 400
         :body {:message "Invalid collection id in url"}}
        (let [collection-id (first parts)
              record (:body req)]
          (case collection-id
            "collections" (create-collection xtdb-node record)
            (create-record xtdb-node collection-id record)))))))

(defn get-handler [xtdb-node req]
  (let [parts (uri-parts (:uri req))]
    (if (not= (count parts) 2)
      {:status 400
       :body {:message "Invalid url"}}
      (let [collection-id (first parts)
            id (second parts)
            xt-record (xt/entity (xt/db xtdb-node) (str collection-id "/" id))]
        (if (nil? xt-record)
          {:status 404
           :body {:message "Not Found"}}
          (ring-response/response (->rest-record xt-record)))))))

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
