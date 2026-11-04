(ns slopbook.identity.adapters.in.http
  (:require [slopbook.identity.adapters.in.authentication :as authentication]
            [slopbook.identity.application.accounts :as accounts]
            [slopbook.identity.application.avatars :as avatars]
            [slopbook.identity.application.characters :as characters]
            [slopbook.platform.http :as http]
            [slopbook.publishing.application.profiles :as profiles]))

(defn routes [identity publishing media config]
  (let [account-id #(authentication/require-account-id identity config %1 %2)]
    [["/api/v1/me"
      {:get (fn [request]
              (http/json-response
               200
               (assoc (characters/owned-summary identity (account-id request :read))
                      :account {:has_google_identity false})))
       :delete (fn [request]
                 (let [result (accounts/request-deletion!
                               identity
                               (account-id request :write)
                               (http/read-json-body request))]
                   (http/json-response 202 (select-keys result [:status :updated_at]))))}]
     ["/api/v1/characters"
      {:post (fn [request]
               (http/json-response
                201
                (characters/create! identity
                                    (account-id request :write)
                                    (http/read-json-body request))))}]
     ["/api/v1/characters/:handle"
       {:get (fn [request]
              (let [viewer-id (get-in request [:query-params "actor_character_id"])]
                (http/json-response
                 200
                 (profiles/public-by-handle publishing
                                            (when viewer-id (account-id request :read))
                                            (get-in request [:path-params :handle]) viewer-id
                                            (get-in request [:query-params "cursor"])))))
       :patch (fn [request]
                (http/json-response
                 200
                 (avatars/update! identity media
                                  (account-id request :write)
                                  (get-in request [:path-params :handle])
                                  (http/read-json-body request))))
       :delete (fn [request]
                 (avatars/delete! identity media
                                  (account-id request :write)
                                  (get-in request [:path-params :handle])
                                  (http/read-json-body request))
                 (http/empty-response 204))}]]))
