(ns slopbook.system
  (:require [clojure.string :as str]
            [org.httpkit.server :as http-kit]
            [ring.middleware.params :refer [wrap-params]]
            [reitit.ring :as ring]
            [slopbook.identity.adapters.in.authentication :as identity-authentication]
            [slopbook.identity.adapters.in.anonymous-http :as anonymous-http]
            [slopbook.identity.adapters.out.jwks :as identity-jwks]
            [slopbook.identity.adapters.in.http :as identity-http]
            [slopbook.identity.adapters.out.memory :as identity-memory]
            [slopbook.identity.adapters.out.memory-anonymous-sessions :as memory-anonymous-sessions]
            [slopbook.identity.adapters.out.memory-accounts :as memory-accounts]
            [slopbook.identity.adapters.out.postgres :as identity-postgres]
            [slopbook.identity.adapters.out.postgres-anonymous-sessions :as postgres-anonymous-sessions]
            [slopbook.identity.adapters.out.postgres-accounts :as postgres-accounts]
            [slopbook.identity.adapters.out.turnstile :as turnstile]
            [slopbook.messaging.adapters.in.http :as messaging-http]
            [slopbook.messaging.adapters.out.memory :as messaging-memory]
            [slopbook.messaging.adapters.out.postgres :as messaging-postgres]
            [slopbook.media.adapters.in.http :as media-http]
            [slopbook.media.adapters.in.multipart :as media-multipart]
            [slopbook.media.adapters.out.executor :as media-executor]
            [slopbook.media.adapters.out.image-processor :as image-processor]
            [slopbook.media.adapters.out.memory :as media-memory]
            [slopbook.media.adapters.out.memory-storage :as memory-storage]
            [slopbook.media.adapters.out.postgres :as media-postgres]
            [slopbook.media.adapters.out.supabase-storage :as supabase-storage]
            [slopbook.media.application.assets :as media-assets]
            [slopbook.platform.config :as config]
            [slopbook.platform.client-ip :as client-ip]
            [slopbook.platform.db :as db]
            [slopbook.platform.http :as http]
            [slopbook.platform.memory :as memory]
            [slopbook.platform.migrations :as migrations]
            [slopbook.platform.outbox :as outbox]
            [slopbook.platform.rate-limit :as rate-limit]
            [slopbook.platform.routes :as platform-routes]
            [slopbook.platform.time :as time]
            [slopbook.platform.websocket :as websocket]
            [slopbook.publishing.adapters.in.http :as publishing-http]
            [slopbook.publishing.adapters.out.memory-comments :as memory-comments]
            [slopbook.publishing.adapters.out.memory :as publishing-memory]
            [slopbook.publishing.adapters.out.postgres-comments :as postgres-comments]
            [slopbook.publishing.adapters.out.postgres :as publishing-postgres]
            [slopbook.social.adapters.in.http :as social-http]
            [slopbook.social.adapters.in.notifications-http :as notifications-http]
            [slopbook.social.adapters.out.memory-discovery :as memory-discovery]
            [slopbook.social.adapters.out.memory-notifications :as memory-notifications]
            [slopbook.social.adapters.out.memory-reactions :as memory-reactions]
            [slopbook.social.adapters.out.memory-reposts :as memory-reposts]
            [slopbook.social.adapters.out.postgres-discovery :as postgres-discovery]
            [slopbook.social.adapters.out.postgres-notifications :as postgres-notifications]
            [slopbook.social.adapters.out.postgres-reactions :as postgres-reactions]
            [slopbook.social.adapters.out.postgres-reposts :as postgres-reposts]
            [slopbook.transformation.adapters.out.openai :as openai]
            [slopbook.transformation.adapters.out.resilient :as resilient]
            [slopbook.transformation.ports.rewriter :as rewriter-port]))

(defn authenticated-write-policies [request]
  (let [method (:request-method request)
        uri (:uri request)]
    (cond
      (and (= :post method) (= "/api/v1/posts" uri))
      [{:bucket :rewrite-post :maximum 20 :window-ms 60000}]

      (and (= :post method) (= "/api/v1/character-greetings/preview" uri))
      [{:bucket :rewrite-greeting-preview :maximum 20 :window-ms 60000}]

      (and (= :post method) (re-matches #"/api/v1/posts/[^/]+/comments" uri))
      [{:bucket :rewrite-comment :maximum 30 :window-ms 60000}]

      (and (= :post method) (re-matches #"/api/v1/conversations/[^/]+/messages" uri))
      [{:bucket :rewrite-message :maximum 40 :window-ms 60000}]

      (and (= :post method) (re-matches #"/api/v1/targets/[^/]+/[^/]+/reaction" uri))
      [{:bucket :choose-reaction-burst :maximum 12 :window-ms 60000}
       {:bucket :choose-reaction-day :maximum 120 :window-ms 86400000}]

      (and (= :post method) (re-matches #"/api/v1/posts/[^/]+/repost" uri))
      [{:bucket :repost :maximum 60 :window-ms 60000}]

      (and (= :post method) (= "/api/v1/media" uri))
      [{:bucket :media-upload :maximum 10 :window-ms 60000}]

      :else [{:bucket :authenticated-write :maximum 120 :window-ms 60000}])))

(defn wrap-public-read-rate-limit [handler limiter config]
  (fn [request]
    (when (and (= :get (:request-method request))
               (str/starts-with? (:uri request) "/api/v1/"))
      (rate-limit/enforce! limiter :public-read
                           (client-ip/remote-ip config request)
                           300 60000 "PUBLIC_READ_RATE_LIMITED"))
    (handler request)))

(defn create-system
  ([] (create-system (config/load-config)))
  ([config]
   (let [state (memory/new-state)
         new-id memory/new-id
         now time/now-string
         datasource (when (= :postgres (:store config)) (db/datasource config))
         account-store (if datasource
                         (postgres-accounts/adapter datasource)
                         (memory-accounts/adapter state new-id now))
         identity-store (if datasource
                          (identity-postgres/adapter datasource)
                          (identity-memory/adapter state))
         anonymous-session-store (if datasource
                                   (postgres-anonymous-sessions/adapter datasource)
                                   (memory-anonymous-sessions/adapter state now))
         anonymous-rate (rate-limit/limiter)
         search-rate (rate-limit/limiter)
         authenticated-write-rate (rate-limit/limiter)
         public-read-rate (rate-limit/limiter)
         identity {:store identity-store
                   :accounts account-store
                   :anonymous-sessions anonymous-session-store
                   :human-verification (turnstile/adapter config)
                   :anonymous-rate anonymous-rate
                   :auth (identity-jwks/adapter config)
                   :new-id new-id
                   :now now}
         publishing-store (if datasource
                            (publishing-postgres/adapter datasource)
                            (publishing-memory/adapter state))
         comment-store (if datasource
                         (postgres-comments/adapter datasource)
                         (memory-comments/adapter state new-id now))
         base-ai (or (:ai-adapter config) (openai/adapter config))
         ai (resilient/adapter base-ai config)
         transformation {:ai ai}
         reaction-store (if datasource
                          (postgres-reactions/adapter datasource)
                          (memory-reactions/adapter state new-id now))
         repost-store (if datasource
                        (postgres-reposts/adapter datasource)
                        (memory-reposts/adapter state new-id now))
         discovery-store (if datasource
                           (postgres-discovery/adapter datasource)
                           (memory-discovery/adapter state new-id now))
         notification-store (if datasource
                              (postgres-notifications/adapter datasource)
                              (memory-notifications/adapter state))
         message-store (if datasource
                         (messaging-postgres/adapter datasource)
                         (messaging-memory/adapter state new-id now))
         media-store (if datasource (media-postgres/adapter datasource) (media-memory/adapter state))
         media-storage (if datasource (supabase-storage/adapter config) (memory-storage/adapter state))
         media-worker (media-executor/create)
         media {:store media-store :storage media-storage :processor (image-processor/adapter)
                :executor-submit #(media-executor/submit! media-worker %)
                :identity identity :new-id new-id :now now}
         ws-hub (websocket/hub)
         outbox-store (if datasource
                        (outbox/postgres-adapter datasource)
                        (outbox/memory-adapter state))
         social {:reactions reaction-store :reposts repost-store :discovery discovery-store
                 :notifications notification-store
                 :identity identity :transformation transformation
                 :search-rate search-rate
                 :new-id new-id :now now :cursor-secret (:cursor-secret config)}
         publishing {:store publishing-store :comments comment-store
                     :identity identity :transformation transformation :media media
                     :new-id new-id :now now :cursor-secret (:cursor-secret config)}
         messaging {:store message-store :identity identity :transformation transformation
                    :new-id new-id :now now :cursor-secret (:cursor-secret config)}
         authenticate-base #(identity-authentication/require-account-id identity config %1 %2)
         authenticate (fn [request operation]
                        (let [account-id (authenticate-base request operation)]
                          (when (= :write operation)
                            (doseq [{:keys [bucket maximum window-ms]}
                                    (authenticated-write-policies request)]
                              (rate-limit/enforce! authenticated-write-rate bucket account-id
                                                   maximum window-ms
                                                   "AUTH_WRITE_RATE_LIMITED")))
                          account-id))
         authenticate-optional #(identity-authentication/optional-account-id identity config %1 %2)
         ai-available? #(rewriter-port/available? ai)
         routes (vec (concat (platform-routes/routes config datasource ai-available?)
                             (identity-http/routes identity publishing media config)
                             (anonymous-http/routes identity config)
                             (publishing-http/routes publishing authenticate authenticate-optional config)
                             (social-http/routes social authenticate authenticate-optional config)
                             (notifications-http/routes social authenticate ws-hub)
                             (messaging-http/routes messaging authenticate)
                             (media-http/routes media authenticate)))
        handler (ring/ring-handler
                 ;; The contract intentionally has both /notifications/read-all and
                 ;; /notifications/:id. Reitit quarantines that static/dynamic pair
                 ;; and preserves the declared static-first match order.
                 (ring/router routes {:conflicts nil})
                 (ring/routes
                  http/api-not-found-handler
                  (ring/create-resource-handler {:path "/" :root "public"})
                  (ring/create-default-handler)))
        app (-> handler
                (http/wrap-idempotency state)
                wrap-params
                media-multipart/wrap
                http/wrap-json-content-type
                (wrap-public-read-rate-limit public-read-rate config)
                http/wrap-safe-errors
                (http/wrap-security-headers config)
                http/wrap-request-id)]
    {:config config
     :datasource datasource
     :state state
     :app app
     :identity identity
     :publishing publishing
     :social social
     :messaging messaging
     :media media
     :media-worker media-worker
     :websocket-hub ws-hub
     :outbox outbox-store
     :rate-limits {:anonymous anonymous-rate
                   :search search-rate
                   :authenticated-write authenticated-write-rate
                   :public-read public-read-rate}})))

(defn prepare-store! [runtime-config]
  (when (= :postgres (:store runtime-config))
    (migrations/migrate! runtime-config)))

(defn start! []
  (let [runtime-config (config/load-config)
        _ (prepare-store! runtime-config)
        {:keys [config app websocket-hub outbox media] :as system} (create-system runtime-config)
        stop-fn (http-kit/run-server app {:port (:port config)})
        dispatcher (outbox/start! outbox #(websocket/deliver! websocket-hub %))
        media-sweeper (media-assets/start-sweeper! media)]
    (println (str "Slopbook listening on http://localhost:" (:port config)))
    (println "Runtime:" (pr-str (config/public-summary config)))
    (assoc system :stop stop-fn :outbox-dispatcher dispatcher :media-sweeper media-sweeper)))

(defn stop! [system]
  (when-let [stop (:stop system)]
    (stop))
  (outbox/stop! (:outbox-dispatcher system))
  (media-assets/stop-sweeper! (:media-sweeper system))
  (media-executor/stop! (:media-worker system))
  (db/close! (:datasource system)))
