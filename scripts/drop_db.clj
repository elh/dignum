(ns scripts.drop-db
  (:require [xtdb.api :as xt]))

;; Delete all records from the XTDB database

(let [xtdb-url (or (System/getenv "XTDB_URL") "http://localhost:9999")
      xtdb-node (xt/new-api-client xtdb-url)
      res (xt/q (xt/db xtdb-node)
                '{:find [id]
                  :where [[id :xt/id _]]})
      ids (map first res)]
  (println "Deleting" (count ids) "records...")
  (->> ids
       (mapv (fn [id] [::xt/delete id]))
       (xt/submit-tx xtdb-node)))
