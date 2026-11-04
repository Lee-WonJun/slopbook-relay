(ns slopbook.messaging.adapters.in.http
  (:require [slopbook.messaging.application.messages :as messages]
            [slopbook.platform.http :as http]))

(defn routes [messaging authenticate]
  [["/api/v1/conversations"
    {:get (fn [request]
            (http/json-response
             200
             (messages/list! messaging (authenticate request :read)
                             (get-in request [:query-params "actor_character_id"])
                             (get-in request [:query-params "cursor"]))))
     :post (fn [request]
             (let [result (messages/create! messaging (authenticate request :write)
                                            (http/read-json-body request))]
               (http/json-response (if (:created result) 201 200) result)))}]
   ["/api/v1/conversations/:id"
    {:get (fn [request]
            (http/json-response
             200
             (messages/detail! messaging (authenticate request :read)
                               (get-in request [:path-params :id])
                               (get-in request [:query-params "actor_character_id"])
                               (get-in request [:query-params "cursor"]))))}]
   ["/api/v1/conversations/:id/messages"
    {:post (fn [request]
             (http/json-response
              201
              (messages/send! messaging (authenticate request :write)
                              (get-in request [:headers "idempotency-key"])
                              (get-in request [:path-params :id])
                              (http/read-json-body request))))}]
   ["/api/v1/conversations/:id/read"
    {:post (fn [request]
             (http/json-response
              200
              (messages/mark-read! messaging (authenticate request :write)
                                   (get-in request [:path-params :id])
                                   (http/read-json-body request))))}]
   ["/api/v1/messages/:id"
    {:delete (fn [request]
               (messages/delete-for-me! messaging (authenticate request :write)
                                        (get-in request [:path-params :id])
                                        (http/read-json-body request))
               (http/empty-response 204))}]])
