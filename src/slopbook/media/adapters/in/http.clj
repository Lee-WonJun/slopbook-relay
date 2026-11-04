(ns slopbook.media.adapters.in.http
  (:require [slopbook.media.application.assets :as assets]
            [slopbook.platform.http :as http]))

(defn routes [media authenticate]
  [["/api/v1/media"
    {:post (fn [request]
             (http/json-response
              202
              (assets/upload! media (authenticate request :write)
                              (:multipart-params request))))}]
   ["/api/v1/media/:id"
    {:get (fn [request]
            (http/json-response
             200
             (assets/find! media (authenticate request :read)
                           (get-in request [:query-params "actor_character_id"])
                           (get-in request [:path-params :id]))))
     :delete (fn [request]
               (assets/delete! media (authenticate request :write)
                               (get-in request [:path-params :id])
                               (http/read-json-body request))
               (http/empty-response 204))}]])
