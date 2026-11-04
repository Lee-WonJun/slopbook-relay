(ns slopbook.platform.routes
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [slopbook.identity.adapters.out.sigil-raster :as sigil-raster]
            [slopbook.identity.domain.sigil :as sigil]
            [slopbook.platform.config :as config]
            [slopbook.platform.db :as db]
            [slopbook.platform.errors :as errors]
            [slopbook.platform.http :as http]
            [slopbook.platform.time :as time]
            [slopbook.platform.websocket :as websocket]))

(defn- matching-etag? [request etag]
  (some (fn [candidate]
          (let [candidate (str/trim candidate)]
            (or (= "*" candidate)
                (= etag (str/replace candidate #"^W/" "")))))
        (some-> (get-in request [:headers "if-none-match"])
                (str/split #","))))

(defn- immutable-asset-response [request etag content-type body-fn]
  (let [headers {"cache-control" "public, max-age=31536000, immutable"
                 "etag" etag}]
    (if (matching-etag? request etag)
      {:status 304 :headers headers}
      {:status 200
       :headers (assoc headers "content-type" content-type)
       :body (body-fn)})))

(defn- app-entry-response [_]
  {:status 200
   :headers {"content-type" "text/html; charset=utf-8"
             "cache-control" "no-store"}
   :body (slurp (io/resource "public/index.html"))})

(defn status-payload
  ([config] (status-payload config nil))
  ([config ai-available?]
  (let [ai? (if ai-available?
              (boolean (ai-available?))
              (boolean (and (:openai-api-key config)
                            (or (= "standard" (:openai-data-policy config))
                                (and (= "zdr" (:openai-data-policy config))
                                     (:openai-zdr-verified? config))))))]
    {:mode (if ai? "normal" "read_only")
     :features {:public_read "available"
                :ai_writes (if ai? "available" "unavailable")
                :anonymous_comments (if (or (:turnstile-enabled? config)
                                            (:development? config))
                                      "available"
                                      "unavailable")
                :media_uploads (if (:media-enabled? config) "available" "unavailable")}
     :message_key (if ai? "status.normal" "status.read_only")
     :updated_at (time/now-string)})))

(defn routes
  ([config] (routes config nil nil))
  ([config datasource] (routes config datasource nil))
  ([config datasource ai-available?]
  (let [status-fn #(status-payload config ai-available?)]
    [["/health/live" {:get (fn [_] (http/json-response 200 {:status "live"}))}]
     ["/health/ready"
      {:get (fn [_]
              (let [ready? (or (nil? datasource) (db/healthy? datasource))]
                (http/json-response (if ready? 200 503)
                                    {:status (if ready? "ready" "not_ready")
                                     :store (name (:store config))})))}]
     ["/ws/status" {:get (websocket/status-handler status-fn)}]
     ["/auth/callback" {:get app-entry-response}]
     ["/compose" {:get app-entry-response}]
     ["/characters" {:get app-entry-response}]
     ["/characters/:handle" {:get app-entry-response}]
     ["/search" {:get app-entry-response}]
     ["/notifications" {:get app-entry-response}]
     ["/messages" {:get app-entry-response}]
     ["/posts/:id" {:get app-entry-response}]
     ["/privacy" {:get app-entry-response}]
     ["/terms" {:get app-entry-response}]
     ["/ai-policy" {:get app-entry-response}]
     ["/community-guidelines" {:get app-entry-response}]
     ["/security" {:get app-entry-response}]
     ["/avatars/sigil/v1/:asset"
      {:get (fn [request]
              (let [asset (get-in request [:path-params :asset])
                    svg-match (re-matches #"([0-9a-f]{64})\.svg" asset)
                    raster-match (re-matches #"([0-9a-f]{64})@(80|96|224)\.webp" asset)]
                (cond
                  svg-match
                  (let [seed (second svg-match)
                        etag (str "\"sigil-v1-" seed "\"")]
                    (immutable-asset-response
                     request etag "image/svg+xml; charset=utf-8"
                     #(sigil/svg seed)))

                  raster-match
                  (let [seed (second raster-match)
                        size (Integer/parseInt (nth raster-match 2))
                        etag (str "\"sigil-v1-" seed "-" size "\"")]
                    (if (matching-etag? request etag)
                      (immutable-asset-response request etag "image/webp" (constantly nil))
                      (let [{:keys [bytes content-type]} (sigil-raster/variant seed size)]
                        (immutable-asset-response request etag content-type #(identity bytes)))))

                  :else (errors/fail! 404 "SIGIL_NOT_FOUND" :not-found))))}]
     ["/api/v1/config" {:get (fn [_] (http/json-response 200 (config/public-client-config config)))}]
     ["/api/v1/status" {:get (fn [_] (http/json-response 200 (status-fn)))}]])))
