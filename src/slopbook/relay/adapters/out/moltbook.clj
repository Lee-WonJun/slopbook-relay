(ns slopbook.relay.adapters.out.moltbook
  (:require [slopbook.platform.errors :as errors]
            [slopbook.platform.json :as json]
            [slopbook.platform.time :as time]
            [slopbook.relay.domain.source :as source])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
           [java.time Duration]))

(def api-base "https://www.moltbook.com/api/v1")
(def max-response-bytes 524288)

(defn- transport [client]
  (fn [path]
    (let [request (-> (HttpRequest/newBuilder (URI/create (str api-base path)))
                      (.timeout (Duration/ofSeconds 6))
                      (.header "Accept" "application/json") .GET .build)
          response (.send client request (HttpResponse$BodyHandlers/ofInputStream))]
      (with-open [stream (.body response)]
        (let [bytes (.readNBytes stream (inc max-response-bytes))]
          (when (> (alength bytes) max-response-bytes)
            (errors/fail! 502 "RELAY_SOURCE_UNAVAILABLE" :ai-unavailable))
          {:status (.statusCode response) :body (String. bytes "UTF-8")})))))

(defn adapter
  ([] (adapter {}))
  ([{:keys [get-json clock-ms]}]
   (let [client (-> (HttpClient/newBuilder) (.connectTimeout (Duration/ofSeconds 3)) .build)
         get-json (or get-json (transport client))
         clock-ms (or clock-ms #(System/currentTimeMillis))
         cache (atom {})
         fetch! (fn [path]
                  (try
                    (let [response (get-json path)]
                      (when-not (= 200 (:status response))
                        (errors/fail! (if (= 404 (:status response)) 404 503)
                                      "RELAY_SOURCE_UNAVAILABLE" :ai-unavailable))
                      (json/read-value (:body response)))
                    (catch clojure.lang.ExceptionInfo e (throw e))
                    (catch Exception _
                      (errors/fail! 503 "RELAY_SOURCE_UNAVAILABLE" :ai-unavailable))))
         cached (fn [key fetch-fn]
                  (let [{:keys [expires value]} (get @cache key)]
                    (if (and value (> expires (clock-ms))) value
                        (let [result (fetch-fn)]
                          ;; No network operation holds the shared cache lock.
                          ;; Only bounded public source data is cached; no user intent.
                          (swap! cache #(assoc (into {} (filter (fn [[_ v]] (> (:expires v) (clock-ms))) %))
                                               key {:expires (+ (clock-ms) 60000) :value result}))
                          result))))
         fetch-post! (fn [id]
                       (when-not (source/valid-id? id)
                         (errors/fail! 400 "RELAY_SOURCE_INVALID" :validation))
                       (cached [:post id]
                               #(or (source/public-post (:post (fetch! (str "/posts/" id))))
                                    (errors/fail! 404 "RELAY_SOURCE_UNAVAILABLE" :not-found))))]
     {:feed (fn [sort-order]
              (when-not (contains? #{"hot" "new"} sort-order)
                (errors/fail! 400 "RELAY_SORT_INVALID" :validation))
              (cached [:feed sort-order]
                      #(let [payload (fetch! (str "/posts?sort=" sort-order "&limit=12"))]
                         {:items (into [] (keep source/public-post) (:posts payload))
                          :fetched_at (time/now-string) :source "moltbook"})))
      :post fetch-post!
      :thread (fn [id]
                (when-not (source/valid-id? id)
                  (errors/fail! 400 "RELAY_SOURCE_INVALID" :validation))
                (cached [:thread id]
                        #(let [post (fetch-post! id)
                               comments (fetch! (str "/posts/" id "/comments?sort=best&limit=5"))]
                           {:post post :comments (into [] (comp (keep source/public-comment) (take 5))
                                                      (:comments comments))
                            :fetched_at (time/now-string)})))})))
