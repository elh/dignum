(ns dignum.core-test
  (:require [clojure.test :refer :all]
            [dignum.core :refer :all]
            [xtdb.api :as xt]))

;; TODO: rewrite all tests for better output. `is`/`are` form must be function call, not macro. just manually manage the
;; cases and use `is`?
(deftest create-collection-test
  (let [prior-coll {"_name" "collections/teams"
                    "schema" {"type" "object"}}]
    (letfn [(->request [coll]
              {:uri "/collections"
               :request-method :post
               :body coll})]
      (are [desc res-status coll] (= (:status (with-open [node (xt/start-node {})]
                                                (when (not= 200 (:status (handler node (->request prior-coll))))
                                                  (throw (Exception. "set up create failed")))
                                                (handler node (->request coll))))
                                     res-status)
        "success" 200 {"_name" "collections/users"
                       "schema" {"type" "object"}}
        "missing schema" 400 {"_name" "collections/users"}
        "additional field" 400 {"_name" "collections/users"
                                "schema" {"type" "object"}
                                "other-field" "not allowed"}
        "missing _name" 400 {"schema" {"type" "object"}}
        "invalid _name w/o 'collections/' prefix" 400 {"_name" "users"
                                                       "schema" {"type" "object"}}
        "cannot edit collections/collections" 409 {"_name" "collections/collections"
                                                   "schema" {"type" "object"}}
        "cannot create collection that already exists" 409 {"_name" "collections/teams"
                                                            "schema" {"type" "object"}}
        "schema is invalid" 400 {"_name" "collections/users"
                                 "schema" {"type" "invalid-schema"}}))))

(deftest create-record-test
  (let [coll {"_name" "collections/users"
              "schema" {"type" "object"
                        "properties" {"name" {"type" "string"}}}}]
    (letfn [(->request [uri body]
              {:uri uri
               :request-method :post
               :body body})]
      (are [desc res-status uri record expect-rec]
           (with-open [node (xt/start-node {})]
             (when (not= 200 (:status (handler node (->request "/collections" coll))))
               (throw (Exception. "setting up collection failed")))
             (let [res (handler node (->request uri record))]
               (and (= (:status res)
                       res-status)
                    (or (nil? expect-rec)
                        (= (remove-underscore-keys (:body res)) (remove-underscore-keys expect-rec))))))
        "success" 200 "/users" {"name" "alice"} {"name" "alice"}
        "nonexistent collection" 400 "/animals" {"name" "alice"} nil
        "fails collection validation" 400 "/users" {"name" 30} nil))))

(deftest update-record-test
  (let [coll {"_name" "collections/users"
              "schema" {"type" "object"
                        "properties" {"name" {"type" "string"}}}}
        orig-record {"name" "alice"}]
    (letfn [(->request [uri method body]
              {:uri uri
               :request-method method
               :body body})]
      (are [desc res-status uri-fn new-record expect-rec]
           (with-open [node (xt/start-node {})]
             (let [coll-res (handler node (->request "/collections" :post coll))
                   rec-res (handler node (->request "/users" :post orig-record))]
               (when (or (not= 200 (:status coll-res))
                         (not= 200 (:status rec-res)))
                 (throw (Exception. "setting up fixtures failed")))
               (let [res (handler node (->request (uri-fn (get-in rec-res [:body "_name"])) :put new-record))]
                 (and (= (:status res)
                         res-status)
                      (or (nil? expect-rec)
                          (= (remove-underscore-keys (:body res)) (remove-underscore-keys expect-rec)))))))
        "success" 200 (fn [rec-name] (str "/" rec-name)) {"name" "alison"} {"name" "alison"}
        "nonexistent record" 404 (fn [_] "/users/dne") {"name" "alison"} nil
        "fails collection validation" 400 (fn [rec-name] (str "/" rec-name)) {"name" 30} nil))))

(deftest update-collection-test
  (let [coll {"_name" "collections/users"
              "schema" {"type" "object"
                        "properties" {"name" {"type" "string"}}}}]
    (letfn [(->request [uri method body]
              {:uri uri
               :request-method method
               :body body})]
      (are [desc res-status uri new-record expect-rec]
           (with-open [node (xt/start-node {})]
             (let [coll-res (handler node (->request "/collections" :post coll))]
               (when (not= 200 (:status coll-res))
                 (throw (Exception. "setting up fixtures failed")))
               (let [res (handler node (->request uri :put new-record))]
                 (and (= (:status res)
                         res-status)
                      (or (nil? expect-rec)
                          (= (remove-underscore-keys (:body res)) (remove-underscore-keys expect-rec)))))))
        "success" 200 "/collections/users"
        {"schema" {"type" "object"
                   "properties" {"name" {"type" "string"}
                                 "age" {"type" "number"}}}}
        {"schema" {"type" "object"
                   "properties" {"name" {"type" "string"}
                                 "age" {"type" "number"}}}}

        "nonexistent collection" 404 "/collections/dne"
        {"schema" {"type" "object"
                   "properties" {"name" {"type" "string"}
                                 "age" {"type" "number"}}}}
        nil

        "fails collection validation" 400 "/collections/users"
        {"schema" {"type" "bad"}}
        nil))))

(deftest delete-record-test
  (let [coll {"_name" "collections/users"
              "schema" {"type" "object"
                        "properties" {"name" {"type" "string"}}}}
        orig-record {"name" "alice"}]
    (letfn [(->request [uri method body]
              {:uri uri
               :request-method method
               :body body})]
      (are [desc res-status uri-fn]
           (with-open [node (xt/start-node {})]
             (let [coll-res (handler node (->request "/collections" :post coll))
                   rec-res (handler node (->request "/users" :post orig-record))]
               (when (or (not= 200 (:status coll-res))
                         (not= 200 (:status rec-res)))
                 (throw (Exception. "setting up fixtures failed")))
               (let [res (handler node (->request (uri-fn (get-in rec-res [:body "_name"])) :delete nil))]
                 (= (:status res)
                    res-status))))
        "success" 200 (fn [rec-name] (str "/" rec-name))
        "nonexistent record" 404 (fn [_] "/users/dne")))))

(deftest delete-collection-test
  (let [coll {"_name" "collections/users"
              "schema" {"type" "object"
                        "properties" {"name" {"type" "string"}}}}]
    (letfn [(->request [uri method body]
              {:uri uri
               :request-method method
               :body body})]
      (are [desc res-status uri]
           (with-open [node (xt/start-node {})]
             (let [coll-res (handler node (->request "/collections" :post coll))]
               (when (not= 200 (:status coll-res))
                 (throw (Exception. "setting up fixtures failed")))
               (let [res (handler node (->request uri :delete nil))]
                 (= (:status res)
                    res-status))))
        "unsupported" 501 (str "/" (get coll "_name"))))))

(deftest get-test
  (let [coll {"_name" "collections/users"
              "schema" {"type" "object"
                        "properties" {"name" {"type" "string"}}}}
        rec {"name" "alice"}]
    (letfn [(->request [uri body]
              {:uri uri
               :request-method :post
               :body body})]
      (with-open [node (xt/start-node {})]
        (let [coll-res (handler node (->request "/collections" coll))
              rec-res (handler node (->request "/users" rec))]
          (when (or (not= 200 (:status coll-res))
                    (not= 200 (:status rec-res)))
            (throw (Exception. "setting up fixtures failed")))
          (are [desc uri-fn expect-res-status expect-res-rec]
               (let [res (handler node {:uri (uri-fn (get-in rec-res [:body "_name"]))
                                        :request-method :get
                                        :body nil})]
                 (and (= (:status res)
                         expect-res-status)
                      (or (nil? expect-res-rec)
                          (= (remove-underscore-keys (:body res)) (remove-underscore-keys expect-res-rec)))))
            "get existing collection record" (fn [_] (str "/" (get coll "_name"))) 200 coll
            "get existing non-collection record" (fn [rec-name] (str "/" rec-name)) 200 rec
            "invalid uri" (fn [_] "/asdf/sdf/fe") 400 nil
            "nonexistent collection" (fn [_] "/collection/dne") 404 nil
            "nonexistent record" (fn [_] "/users/asdf") 404 nil
            "nonexistent collection's record" (fn [_] "/dne/dne") 404 nil))))))

(deftest list-test
  (let [colls [{"_name" "collections/users"
                "schema" {"type" "object"
                          "properties" {"name" {"type" "string"}}}}
               {"_name" "collections/films"
                "schema" {"type" "object"
                          "properties" {"name" {"type" "string"}}}}]
        coll-to-recs {"users" [{"name" "alice"} {"name" "bob"}]}]
    (letfn [(->request [uri body]
                       {:uri uri
                        :request-method :post
                        :body body})]
      (with-open [node (xt/start-node {})]
        (let [coll-res (map #(handler node (->request "/collections" %)) colls)
              rec-res (map (fn [[coll-id recs]]
                             (map (fn [rec]
                                    (handler node (->request (str "/" coll-id) rec)))
                                  recs))
                           coll-to-recs)]
          (when (or (some #(not= 200 (:status %)) coll-res)
                    (some #(some (fn [res] (not= 200 (:status res))) %) rec-res))
            (throw (Exception. "setting up fixtures failed")))
          (are [desc uri expect-res-status expect-res-recs]
               (let [res (handler node {:uri uri
                                        :request-method :get
                                        :body nil})]
                 (and (= (:status res)
                         expect-res-status)
                      (or (nil? expect-res-recs)
                          (= (map #(remove-underscore-keys (:body %)) (:resources res))
                             (map #(remove-underscore-keys (:body %)) (:resources expect-res-recs))))))
            "get existing collection records" "/collections" 200 colls
            "get existing non-collection records" "/users" 200 (get coll-to-recs "users")
            "collection has no records" "/films" 200 []
            "invalid uri" "/bad/bad/bad" 400 nil
            "nonexistent collection" "/dne" 404 nil))))))
