(ns slopbook.social.adapters.in.notifications-http
  (:require [slopbook.platform.http :as http]
            [slopbook.platform.websocket :as websocket]
            [slopbook.social.application.notifications :as notifications]))

(defn routes [social authenticate hub]
  [["/api/v1/notifications"
    [""
     {:get (fn [request]
             (http/json-response
              200
              (notifications/list!
               social
               (authenticate request :read)
               (get-in request [:query-params "actor_character_id"])
               (get-in request [:query-params "cursor"]))))}]
    ["/read-all"
     {:post (fn [request]
              (http/json-response
               200
               (notifications/mark-all-read!
                social
                (authenticate request :write)
                (http/read-json-body request))))}]
    ["/:id"
     {:patch (fn [request]
               (http/json-response
                200
                (notifications/mark-read!
                 social
                 (authenticate request :write)
                 (get-in request [:path-params :id])
                 (http/read-json-body request))))}]]
   ["/api/v1/ws-tickets"
    {:post (fn [request]
             (http/json-response
              201
              (notifications/issue-ticket!
               social
               (authenticate request :write)
               (http/read-json-body request))))}]
   ["/ws"
    {:get (websocket/notification-handler
           hub #(notifications/consume-ticket! social %))}]])
