(ns dignum.server-test
  (:require [clojure.test :refer :all]
            [dignum.server :refer :all]
            [xtdb.api :as xt]))

(deftest create-collection-test
  (are [request res-status] (= (:status (with-open [node (xt/start-node {})]
                                          (handler node request)))
                               res-status)
    ;; success
    {:uri "/collections"
     :request-method :post
     :body {"_name" "collections/users"
            "schema" {"type" "object"}}} 200
    ;; missing schema
    {:uri "/collections"
     :request-method :post
     :body {"_name" "collections/users"}} 400
    ;; additional field
    {:uri "/collections"
     :request-method :post
     :body {"_name" "collections/users"
            "schema" {"type" "object"}
            "other-field" "not allowed"}} 400
    ;; missing _name
    {:uri "/collections"
     :request-method :post
     :body {"schema" {"type" "object"}}} 400
    ;; invalid _name w/o "collections/" prefix
    {:uri "/collections"
     :request-method :post
     :body {"_name" "users"
            "schema" {"type" "object"}}} 400
    ;; cannot edit collections/collections
    {:uri "/collections"
     :request-method :post
     :body {"_name" "collections/collections"
            "schema" {"type" "object"}}} 409
    ;; TODO: collection already exists
    ;; schema is invalid
    {:uri "/collections"
     :request-method :post
     :body {"_name" "collections/users"
            "schema" {"type" "invalid-schema"}}} 400
    ))
