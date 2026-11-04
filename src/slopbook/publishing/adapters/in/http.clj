(ns slopbook.publishing.adapters.in.http
  (:require [slopbook.platform.client-ip :as client-ip]
            [slopbook.platform.http :as http]
            [slopbook.publishing.application.comments :as comments]
            [slopbook.publishing.application.greetings :as greetings]
            [slopbook.publishing.application.posts :as posts]))

(defn routes [publishing authenticate authenticate-optional config]
  (let [account-id #(authenticate %1 %2)]
    [["/api/v1/feed"
      {:get (fn [request]
              (let [mode (get-in request [:query-params "mode"] "latest")
                    actor-id (get-in request [:query-params "actor_character_id"])
                    account-id (if (or actor-id (= "following" mode))
                                 (or (authenticate-optional request :read)
                                     (authenticate request :read))
                                 nil)]
                 (http/json-response
                 200
                 (posts/feed publishing mode account-id actor-id
                             (get-in request [:query-params "cursor"])))))}]
     ["/api/v1/posts"
      {:post (fn [request]
               (http/json-response
                201
                (posts/create! publishing
                               (account-id request :write)
                               (get-in request [:headers "idempotency-key"])
                               (http/read-json-body request))))}]
     ["/api/v1/character-greetings/preview"
      {:post (fn [request]
               (http/json-response
                200
                (greetings/preview! publishing
                                    (account-id request :write)
                                    (http/read-json-body request))))}]
     ["/api/v1/character-greetings"
      {:post (fn [request]
               (http/json-response
                201
                (greetings/publish! publishing
                                    (account-id request :write)
                                    (http/read-json-body request))))}]
     ["/api/v1/posts/:id"
      {:get (fn [request]
              (let [actor-id (get-in request [:query-params "actor_character_id"])
                    viewer-account (when actor-id
                                     (or (authenticate-optional request :read)
                                         (authenticate request :read)))]
                (http/json-response
                 200
                 (posts/find-post publishing viewer-account actor-id
                                  (get-in request [:path-params :id])))))
       :delete (fn [request]
                 (posts/delete! publishing
                                (account-id request :write)
                                (get-in request [:path-params :id])
                                (http/read-json-body request))
                 (http/empty-response 204))}]
     ["/api/v1/posts/:id/comments"
      {:get (fn [request]
              (let [actor-id (get-in request [:query-params "actor_character_id"])
                    viewer-account (when actor-id
                                     (or (authenticate-optional request :read)
                                         (authenticate request :read)))]
                (http/json-response
                 200
                 (comments/thread publishing viewer-account
                                  (get-in request [:path-params :id]) actor-id
                                  (get-in request [:query-params "cursor"])))))
       :post (fn [request]
               (let [body (http/read-json-body request)
                     authenticated? (contains? body :actor_character_id)]
                 (http/json-response
                  201
                  (comments/create-request!
                   publishing
                   {:account-id (when authenticated? (account-id request :write))
                    :remote-ip (client-ip/remote-ip config request)}
                   (get-in request [:headers "idempotency-key"])
                   (get-in request [:path-params :id])
                   body))))}]
     ["/api/v1/comments/:id"
      {:delete (fn [request]
                 (http/json-response
                  200
                  (comments/delete! publishing
                                    (account-id request :write)
                                    (get-in request [:path-params :id])
                                    (http/read-json-body request))))}]
     ["/api/v1/comments/:id/anonymous"
      {:delete (fn [request]
                 (http/json-response
                  200
                  (comments/delete-anonymous!
                   publishing
                   (get-in request [:path-params :id])
                   (get-in request [:headers "x-anonymous-deletion-token"])
                   (http/read-json-body request))))}]]))
