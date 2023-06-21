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
        "success" 200
        {"_name" "collections/users"
         "schema" {"type" "object"}}

        "missing schema" 400
        {"_name" "collections/users"}

        "additional field" 400
        {"_name" "collections/users"
         "schema" {"type" "object"}
         "other-field" "not allowed"}

        "missing _name" 400
        {"schema" {"type" "object"}}

        "invalid _name w/o 'collections/' prefix" 400
        {"_name" "users"
         "schema" {"type" "object"}}

        "cannot edit collections/collections" 409
        {"_name" "collections/collections"
         "schema" {"type" "object"}}

        "cannot create collection that already exists" 409
        {"_name" "collections/teams"
         "schema" {"type" "object"}}

        "schema is invalid" 400
        {"_name" "collections/users"
         "schema" {"type" "invalid-schema"}}))))
