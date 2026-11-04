(ns slopbook.identity.adapters.out.turnstile
  (:require [slopbook.identity.ports.human-verification :as verification-port]
            [slopbook.platform.errors :as errors]
            [slopbook.platform.json :as json])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.time Duration]
           [java.util UUID]))

(def siteverify-url "https://challenges.cloudflare.com/turnstile/v0/siteverify")

(defn valid-result? [config status result]
  (let [hostnames (:turnstile-expected-hostnames config)]
    (and (<= 200 status 299)
         (true? (:success result))
         (= (:turnstile-expected-action config) (:action result))
         (or (empty? hostnames) (contains? hostnames (:hostname result))))))

(defn- remote-verify! [client config {:keys [token remote-ip idempotency-key]}]
  (when-not (and (string? token) (<= 1 (count token) 2048))
    (errors/fail! 400 "TURNSTILE_REQUIRED" :validation))
  (let [body (cond-> {:secret (:turnstile-secret-key config)
                      :response token
                      :idempotency_key (or idempotency-key (str (UUID/randomUUID)))}
               (seq remote-ip) (assoc :remoteip remote-ip))
        request (-> (HttpRequest/newBuilder (URI/create siteverify-url))
                    (.timeout (Duration/ofSeconds 8))
                    (.header "content-type" "application/json")
                    (.POST (HttpRequest$BodyPublishers/ofString (json/write-value body)))
                    .build)
        response (try
                   (.send client request (HttpResponse$BodyHandlers/ofString))
                   (catch Exception _
                     (errors/fail! 503 "TURNSTILE_UNAVAILABLE" :internal)))
        result (try (json/read-value (.body response))
                    (catch Exception _ {}))]
    (when-not (valid-result? config (.statusCode response) result)
      (errors/fail! 400 "TURNSTILE_FAILED" :validation))
    true))

(defn adapter [config]
  (let [client (-> (HttpClient/newBuilder) (.connectTimeout (Duration/ofSeconds 3)) .build)]
    (verification-port/assert-adapter!
     {:verify! (if (:turnstile-enabled? config)
                 #(remote-verify! client config %)
                 (fn [{:keys [token]}]
                   (if (and (:development? config) (= "development-pass" token))
                     true
                     (errors/fail! 400 "TURNSTILE_REQUIRED" :validation))))})))
