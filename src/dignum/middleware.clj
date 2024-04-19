(ns dignum.middleware
  (:gen-class)
   (:require [dignum.util :as util]))

(defn wrap-logging [handler]
  (fn [request]
    (let [start-time (System/currentTimeMillis)
          response (handler request)
          end-time (System/currentTimeMillis)]
      (util/log {:msg "request"
            :request (dissoc request :headers :ssl-client-cert) ;; TODO: switch to allow list
            :status (:status response)
            :start-ms start-time
            :duration-ms (- end-time start-time)})
      response)))
