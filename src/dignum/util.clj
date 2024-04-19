(ns dignum.util
  (:gen-class)
  (:require [clojure.data.json :as json]))

(defn log [m]
  (println (json/write-str m)))
