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

;;;; TODOs:
;; TODO: support list params
;; TODO: can a user add custom logic to the server? via custom hooks? wrap this server? lib v. framework approach?
;; TODO: write transactionally? use transaction function for validation? jinx would need to be installed on xtdb node.
;; TODO: other conventional fields: parent field? created and updated timestamps?
;; TODO: writes via JSON Patch or JSON Merge Patch?

(def collections-schema {"type" "object"
                         "properties" {"schema" {"type" "object"}},
                         "required" ["schema"]
                         "additionalProperties" false})

(defn- log [m]
  (println m)) ;; or print JSON?

;; hack: I expected wrap-json-body to handle this for us
(defn- is-empty-body? [body]
  (or (nil? body)
      (= (.getName (type body)) "org.eclipse.jetty.server.HttpInputOverHTTP")))

(defn- uri-parts [uri]
  (when (not (str/starts-with? uri "/"))
    (throw (Exception. "uri must start with /")))
  (str/split (str/replace-first uri #"/" "") #"/"))

(defn- ->xtdb-record [record]
  (-> record
      (walk/keywordize-keys)
      (set/rename-keys {:_name :xt/id})))

(defn- ->rest-record [xt-record]
  (-> xt-record
      (set/rename-keys {:xt/id :_name})
      (walk/stringify-keys)))

(defn xt-transact [node data]
  (xt/await-tx node (xt/submit-tx node data)))

;; user-provided schemas only validate non-underscore, non-system fields
(defn remove-underscore-keys [m]
  (apply dissoc m (filter #(str/starts-with? % "_") (keys m))))

(defn create-collection
  ([xtdb-node record]
   (create-collection xtdb-node record false))
  ([xtdb-node record strict-create]
   (let [record (assoc record "_collection" "collections/collections")
         validation (jinx/validate
                     (remove-underscore-keys record)
                     (jinx/schema collections-schema))]
     (cond
       (not (:valid? validation))
       {:status 400
        :body {:message (str "Failed collection resource validation: " (:errors validation))}}

       (seq (filter #(and (str/starts-with? % "_") (not= % "_name") (not= % "_collection")) (keys record)))
       {:status 400
        :body {:message "Cannot create or update system fields"}}

       (not (contains? record "_name"))
       {:status 400
        :body {:message "'_name' is required and must be of the form 'collections/<id>' where <id> should be plural form of the collection resource type"}}

       (not (re-matches #"collections/\w+" (get record "_name")))
       {:status 400
        :body {:message "'_name' must be of the form 'collections/<id>' where <id> should be plural form of the collection resource type"}}

       (= (get record "_name") "collections/collections")
       {:status 409
        :body {:message "Cannot create or update 'collections/collections'"}}

       (and strict-create (some? (xt/entity (xt/db xtdb-node) (get record "_name"))))
       {:status 409
        :body {:message (str "Collection already exists: " (get record "_name"))}}

       :else
       (let [validation-err (try (jinx/schema (get record "schema")) nil
                                 (catch Exception e
                                   {:status 400
                                    :body {:message (str "Invalid schema: " e)}}))]
         (if (nil? validation-err)
           (do
             (xt-transact xtdb-node [[::xt/put (->xtdb-record record)]])
             (ring-response/response record))
           validation-err))))))

(defn create-record [xtdb-node collection-id record-id record]
  (let [record (-> record
                   (assoc "_collection" (str "collections/" collection-id))
                   (assoc "_name" (str collection-id "/" record-id)))
        xt-collection (xt/entity (xt/db xtdb-node) (str "collections/" collection-id))]
    (if (nil? xt-collection)
      {:status 400
       :body {:message (str "Collection does not exist: " collection-id)}}
      (let [collection (->rest-record xt-collection)
            validation (jinx/validate
                        (remove-underscore-keys record)
                        (jinx/schema (get collection "schema")))]
        (cond
          (not (:valid? validation))
          {:status 400
           :body {:message (str "Failed collection resource validation: " (:errors validation))}}

          (seq (filter #(and (str/starts-with? % "_") (not= % "_name") (not= % "_collection")) (keys record)))
          {:status 400
           :body {:message "Cannot create or update system fields"}}

          :else
          (do
            (xt-transact xtdb-node [[::xt/put (->xtdb-record record)]])
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
            "collections" (create-collection xtdb-node record true)
            (create-record xtdb-node collection-id (.toString (java.util.UUID/randomUUID)) record)))))))

(defn put-handler [xtdb-node req]
  (if (nil? (:body req))
    {:status 400
     :body {:message "'body' is required"}}
    (let [parts (uri-parts (:uri req))]
      (if (not= (count parts) 2)
        {:status 400
         :body {:message "Invalid resource name in url"}}
        (let [collection-id (first parts)
              record-id (second parts)
              record (:body req)
              exists? (some? (xt/entity (xt/db xtdb-node) (str collection-id "/" record-id)))]
          (if (not exists?)
            {:status 404
             :body {:message (str "Not Found")}}
            (if (= collection-id "collections")
              ;; NOTE: backwards compatible schema changes + migration is currently unmanaged by the system
              (create-collection xtdb-node (-> record
                                               (assoc "_collection" "collections/collections")
                                               (assoc "_name" (str collection-id "/" record-id))))
              (create-record xtdb-node collection-id record-id record))))))))

(defn delete-handler [xtdb-node req]
  (let [parts (uri-parts (:uri req))]
    (if (not= (count parts) 2)
      {:status 400
       :body {:message "Invalid resource name in url"}}
      (let [collection-id (first parts)
            record-id (second parts)
            exists? (some? (xt/entity (xt/db xtdb-node) (str collection-id "/" record-id)))]
        (if (not exists?)
          {:status 404
           :body {:message (str "Not Found")}}
          (if (= collection-id "collections")
            {:status 501
             :body {:message "Deleting collections is not supported"}}
            (do
              (xt-transact xtdb-node [[::xt/delete (str collection-id "/" record-id)]])
              (ring-response/response {}))))))))

(defn get-record [xtdb-node collection-id record-id]
  (let [xt-record (xt/entity (xt/db xtdb-node) (str collection-id "/" record-id))]
    (if (nil? xt-record)
      {:status 404
       :body {:message "Not Found"}}
      (ring-response/response (->rest-record xt-record)))))

(defn list-records [xtdb-node collection-id]
  (let [xtdb (xt/db xtdb-node)]
    (if (and (not= collection-id "collections") ;; no literal collections collection record right now
             (nil? (xt/entity xtdb (str "collections/" collection-id))))
      {:status 404
       :body {:message (str "Collection does not exist: " (str "collections/" collection-id))}}
      ;; no list params right now...
      (let [q-res (xt/q xtdb
                        '{:find [(pull ?v [*])]
                          :in [c]
                          :where [[?v :_collection c]]}
                        (str "collections/" collection-id))]
        ;; NOTE: "resources", not "records"?
        (ring-response/response {:resources (map #(->rest-record (first %)) q-res)})))))

(defn get-handler [xtdb-node req]
  (let [parts (uri-parts (:uri req))]
            (case (count parts)
              1 (list-records xtdb-node (first parts))
              2 (get-record xtdb-node (first parts) (second parts))
              {:status 400
               :body {:message "Invalid url"}})))

(defn handler [xtdb-node req]
  (let [req (if (is-empty-body? (:body req))
              (assoc req :body nil)
              req)]
    (log {:msg "request received" :req req})
    (case (:request-method req)
      :post (create-handler xtdb-node req)
      :put (put-handler xtdb-node req)
      :delete (delete-handler xtdb-node req)
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
