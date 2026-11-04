(ns slopbook.trust.adapters.in.http
  (:require [slopbook.platform.http :as http]
            [slopbook.trust.application.reports :as reports]))

(defn create-handler [deps reporter-id]
  (fn [request]
    (http/json-response 201 (reports/create! deps reporter-id (http/read-json-body request)))))
