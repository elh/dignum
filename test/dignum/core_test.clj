(ns dignum.core-test
  (:require [clojure.test :refer :all]
            [dignum.core :refer :all]
            [xtdb.api :as xt]))

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
      (are [desc res-status uri record] (= (:status (with-open [node (xt/start-node {})]
                                                      (when (not= 200 (:status (handler node (->request "/collections" coll))))
                                                        (throw (Exception. "setting up collection failed")))
                                                      (handler node (->request uri record))))
                                           res-status)
        "success" 200 "/users" {"name" "joe"}
        "nonexistent collection" 400 "/animals" {"name" "joe"}
        "fails collection validation" 400 "/users" {"name" 30}))))

(deftest get-test
  (let [coll {"_name" "collections/users"
              "schema" {"type" "object"
                        "properties" {"name" {"type" "string"}}}}
        rec {"name" "joe"}]
    (letfn [(->request [uri body]
              {:uri uri
               :request-method :post
               :body body})]
      (with-open [node (xt/start-node {})]
        (let [coll-res (handler node (->request "/collections" coll))
              rec-res (handler node (->request "/users" rec))]
          (when (not= 200 (:status coll-res))
            (throw (Exception. "setting up collection failed")))
          (when (not= 200 (:status rec-res))
            (throw (Exception. "setting up record failed")))
          (are [desc res-status uri-fn]
               (= (:status (handler node {:uri (uri-fn (get-in coll-res [:body "_name"])
                                                       (get-in rec-res [:body "_name"]))
                                          :request-method :get
                                          :body nil}))
                  res-status)
            "get existing collection" 200 (fn [coll-name _] (str "/" coll-name))
            "get existing collection" 200 (fn [_ rec-name] (str "/" rec-name))
            "invalid uri" 400 (fn [_ _] "/asdf")
            "nonexistent collection" 404 (fn [_ _] "/collection/dne")
            "nonexistent record" 404 (fn [_ _] "/users/asdf")
            "nonexistent collection's record" 404 (fn [_ _] "/dne/dne")))))))
