(ns dignum.server
  (:gen-class)
  (:require [dignum.core :as core]
            [dignum.util :as util]
            [dignum.middleware :as middleware]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.json :as ring-json]
            [ring.middleware.params :as ring-params]
            [xtdb.api :as xt]))

(defn run [handler {:keys [port]}]
  (jetty/run-jetty (-> handler
                       ring-json/wrap-json-response
                       ring-json/wrap-json-body
                       ring-params/wrap-params)
                   {:port port}))

(defn -main []
  (let [port (if (empty? (System/getenv "PORT"))
               3000
               (Integer/parseInt (System/getenv "PORT")))
        xtdb-url (or (System/getenv "XTDB_URL") "")
        xtdb-node (if (empty? xtdb-url)
                    (xt/start-node {})
                    (xt/new-api-client xtdb-url))]
    (util/log {:msg "Starting server"})
    (util/log {:msg (str "Listening on port " port)})
    (if (empty? xtdb-url)
      (util/log {:msg "Using in-memory XTDB node"})
      (util/log {:msg (str "Using remote XTDB node at " xtdb-url)}))
    (run
     (-> (partial core/handler xtdb-node)
         (middleware/wrap-logging))
     {:port port})))
