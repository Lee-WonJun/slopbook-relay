(ns slopbook.relay.adapters.in.http
  (:require [slopbook.platform.client-ip :as client-ip]
            [slopbook.platform.http :as http]
            [slopbook.platform.rate-limit :as rate-limit]
            [slopbook.relay.application.bridge :as bridge]))

(defn routes [relay authenticate config]
  (let [limits (rate-limit/limiter)
        admit! (fn [request]
                 (rate-limit/enforce! limits :relay-ip (client-ip/remote-ip config request)
                                      12 3600000 "RELAY_RATE_LIMITED")
                 (rate-limit/enforce! limits :relay-global "public-briefs"
                                      300 86400000 "RELAY_RATE_LIMITED"))]
    [["/api/v1/relay/posts"
      {:get (fn [request]
              (http/json-response 200 (bridge/feed relay (get-in request [:query-params "sort"] "hot"))))
       :post (fn [request]
               (let [account-id (authenticate request :write)]
                 (admit! request)
                 (http/json-response 201 (bridge/publish! relay account-id
                                                        (get-in request [:headers "idempotency-key"])
                                                        (http/read-json-body request)))))}]
     ["/api/v1/relay/brief"
      {:post (fn [request]
               (admit! request)
               (http/json-response 200 (bridge/brief! relay (http/read-json-body request))))}]]))
