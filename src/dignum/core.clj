(ns dignum.core
  (:gen-class)
  (:import [com.fasterxml.jackson.databind ObjectMapper]
           [com.github.fge.jsonpatch JsonPatch])
  (:require [clojure.walk :as walk]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [ring.util.response :as ring-response]
            [xtdb.api :as xt]
            [juxt.jinx-alpha :as jinx]))

;; TODOs -> https://github.com/elh/dignum/blob/main/TODO.md

(def collections-schema {"type" "object"
                         "properties" {"_collection" {"type" "string"
                                                      "const" "collections/collections"}
                                       "_name" {"type" "string"
                                                "pattern" "^collections/"}
                                       "schema" {"type" "object"}} ;; this will be validated by jinx instantiation
                         "required" ["_collection" "_name" "schema"]
                         "additionalProperties" false})

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

(defn create-collection [xtdb-node record]
   (let [record (assoc record "_collection" "collections/collections")
         validation (jinx/validate
                     record
                     (jinx/schema collections-schema))]
     (cond
       (not (:valid? validation))
       {:status 400
        :body {:message (str "Failed collection resource validation: " (:errors validation))}}

       (= (get record "_name") "collections/collections")
       {:status 409
        :body {:message "Cannot create or update 'collections/collections'"}}

       :else
       (let [validation-err (try (jinx/schema (get record "schema")) nil
                                 (catch Exception e
                                   {:status 400
                                    :body {:message (str "Invalid schema: " e)}}))]
         (if (nil? validation-err)
           (do
             (xt-transact xtdb-node [[::xt/put (->xtdb-record record)]])
             (ring-response/response record))
           validation-err)))))

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
            "collections" (if (some? (xt/entity (xt/db xtdb-node) (get record "_name")))
                            {:status 409
                             :body {:message (str "Collection already exists: " (get record "_name"))}}
                            (create-collection xtdb-node record))
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
              (create-collection xtdb-node (assoc record "_name" (str collection-id "/" record-id)))
              (create-record xtdb-node collection-id record-id record))))))))

(defn patch-handler [xtdb-node req]
  (cond
    (not= "application/json-patch+json" (get-in req [:headers "content-type"]))
    {:status 400
     :body {:message "Content-Type must be application/json-patch+json"}}

    (nil? (:body req))
    {:status 400
     :body {:message "'body' is required"}}

    (not= (count (uri-parts (:uri req))) 2)
    {:status 400
     :body {:message "Invalid resource name in url"}}

    :else
    (let [parts (uri-parts (:uri req))
          collection-id (first parts)
          record-id (second parts)
          xt-current (xt/entity (xt/db xtdb-node) (str collection-id "/" record-id))]
      (if (nil? xt-current)
        {:status 404
         :body {:message (str "Not Found")}}
        (let [current-json-node (.readTree (ObjectMapper.) (json/write-str (->rest-record xt-current)))
              patch-expr (:body req)
              json-node (.readTree (ObjectMapper.) (json/write-str patch-expr))
              record (try
                       (let [json-patch (JsonPatch/fromJson json-node)
                             record-json-node (.apply json-patch current-json-node)
                             record (json/read-str (.toString record-json-node))]
                         record)
                       (catch Throwable _
                         nil))]
          (if (nil? record)
            ;; TODO: give more context from the exception
            {:status 400
             :body {:message "Invalid patch"}}
            (if (= collection-id "collections")
              ;; NOTE: backwards compatible schema changes + migration is currently unmanaged by the system
              (create-collection xtdb-node (assoc record "_name" (str collection-id "/" record-id)))
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

;; pagination? order results? more expressive queries?
;; NOTE: only supporting the simplest string equality query param
(defn list-records [xtdb-node collection-id query-params]
  (let [xtdb (xt/db xtdb-node)]
    (if (and (not= collection-id "collections") ;; no literal collections collection record right now
             (nil? (xt/entity xtdb (str "collections/" collection-id))))
      {:status 404
       :body {:message (str "Collection does not exist: " (str "collections/" collection-id))}}
      (let [q-where (mapv (fn [[k v]] ['?v (keyword k) v]) query-params)
            where ['[?v :_collection c]]
            where (into [] (concat where q-where))
            q-res (xt/q xtdb
                        (hash-map :find '[(pull ?v [*])]
                                  :in '[c]
                                  :where where)
                        (str "collections/" collection-id))]
        ;; NOTE: "resources", not "records"?
        (ring-response/response {:resources (map #(->rest-record (first %)) q-res)})))))

(defn get-handler [xtdb-node req]
  (let [parts (uri-parts (:uri req))]
            (case (count parts)
              1 (list-records xtdb-node (first parts) (:query-params req))
              2 (get-record xtdb-node (first parts) (second parts))
              {:status 400
               :body {:message "Invalid url"}})))

(defn handler [xtdb-node req]
  (let [req (if (is-empty-body? (:body req))
              (assoc req :body nil)
              req)]
    (case (:request-method req)
      :post (create-handler xtdb-node req)
      :put (put-handler xtdb-node req)
      :patch (patch-handler xtdb-node req)
      :delete (delete-handler xtdb-node req)
      :get (get-handler xtdb-node req)
      {:status 501
       :body {:message "Unimplemented"}})))
