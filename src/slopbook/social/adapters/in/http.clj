(ns slopbook.social.adapters.in.http
  (:require [slopbook.platform.client-ip :as client-ip]
            [slopbook.platform.errors :as errors]
            [slopbook.platform.http :as http]
            [slopbook.platform.rate-limit :as rate-limit]
            [slopbook.social.application.discovery :as discovery]
            [slopbook.social.application.reactions :as reactions]
            [slopbook.social.application.reposts :as reposts]))

(defn- viewer-id [social authenticate-optional request]
  (when-let [requested (get-in request [:query-params "actor_character_id"])]
    (discovery/owned-viewer-id social
                               (or (authenticate-optional request :read)
                                   (errors/fail! 401 "AUTH_REQUIRED" :unauthorized))
                               requested)))

(defn routes [social authenticate authenticate-optional config]
  [["/api/v1/targets/:type/:id/reaction"
    {:post (fn [request]
             (http/json-response
              200
              (reactions/toggle! social
                                 (authenticate request :write)
                                 (get-in request [:path-params :type])
                                 (get-in request [:path-params :id])
                                 (http/read-json-body request))))}]
   ["/api/v1/posts/:id/repost"
    {:post (fn [request]
             (http/json-response
              200
              (reposts/toggle! social
                               (authenticate request :write)
                               (get-in request [:path-params :id])
                               (http/read-json-body request))))}]
   ["/api/v1/characters/:id/follow"
    {:put (fn [request]
            (http/json-response
             200
             (discovery/follow! social
                                (authenticate request :write)
                                (get-in request [:path-params :id])
                                (http/read-json-body request))))
     :delete (fn [request]
               (discovery/unfollow! social
                                    (authenticate request :write)
                                    (get-in request [:path-params :id])
                                    (http/read-json-body request))
               (http/empty-response 204))}]
   ["/api/v1/characters/:id/followers"
    {:get (fn [request]
            (http/json-response
             200
             (discovery/connections social :followers
                                    (get-in request [:path-params :id])
                                    (viewer-id social authenticate-optional request)
                                    (get-in request [:query-params "cursor"]))))}]
   ["/api/v1/characters/:id/following"
    {:get (fn [request]
            (http/json-response
             200
             (discovery/connections social :following
                                    (get-in request [:path-params :id])
                                    (viewer-id social authenticate-optional request)
                                    (get-in request [:query-params "cursor"]))))}]
   ["/api/v1/search"
    {:get (fn [request]
            (let [viewer (viewer-id social authenticate-optional request)
                  rate-key (or viewer (client-ip/remote-ip config request))]
              (rate-limit/enforce! (:search-rate social) :search rate-key
                                   60 60000 "SEARCH_RATE_LIMITED")
              (http/json-response
               200
               (discovery/search social
                                 (get-in request [:query-params "q"])
                                 (get-in request [:query-params "type"] "character")
                                 viewer
                                 (get-in request [:query-params "cursor"])))))}]])
