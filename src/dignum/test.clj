(ns dignum.test
  (:gen-class)
  (:require
   [juxt.jinx-alpha :as jinx]))

;; (require '[juxt.jinx-alpha-2 :as jinx])
(jinx/schema {"type" "array" "items" {"type" "string"}})
(jinx/schema {"type" "asdfe"})
(jinx/schema {"bar" 1})
