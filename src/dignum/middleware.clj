(ns dignum.middleware
  (:gen-class)
   (:require [dignum.util :as util]))

(defn wrap-logging
  ([handler]
   (wrap-logging handler {:request-keys [:params
                                         :content-length
                                         :form-params
                                         :query-params
                                         :content-length
                                         :character-encoding
                                         :method
                                         :query-string
                                         ;; :body
                                         :scheme
                                         :request-method]}))
  ([handler {:keys [request-keys]}]
   (fn [request]
     (let [start-time (System/currentTimeMillis)
           response (handler request)
           end-time (System/currentTimeMillis)]
       (util/log {:msg "request"
                  :request (select-keys request request-keys)
                  :status (:status response)
                  :start-ms start-time
                  :duration-ms (- end-time start-time)})
       response))))
