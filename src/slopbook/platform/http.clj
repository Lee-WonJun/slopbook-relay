(ns slopbook.platform.http
  (:require [clojure.string :as str]
            [slopbook.platform.errors :as errors]
            [slopbook.platform.json :as json])
  (:import [java.io InputStream]
           [java.net URI]
           [java.util UUID]))

(defn request-id [] (str "req_" (UUID/randomUUID)))

(defn json-response
  ([status body] (json-response status body {}))
  ([status body headers]
   {:status status
    :headers (merge {"content-type" "application/json; charset=utf-8"
                     "cache-control" "no-store"}
                    headers)
    :body (json/write-value body)}))

(defn empty-response [status]
  {:status status :headers {} :body ""})

(defn api-not-found-handler [request]
  (when (str/starts-with? (:uri request) "/api/")
    (json-response 404 {:error {:code "ROUTE_NOT_FOUND"
                                :message "The requested API route does not exist."
                                :retryable false
                                :request_id (:request-id request)}})))

(defn- safe-origin [url]
  (when url
    (try
      (let [uri (URI/create url)]
        (when (and (contains? #{"https" "http"} (.getScheme uri)) (.getHost uri))
          (str (.getScheme uri) "://" (.getAuthority uri))))
      (catch IllegalArgumentException _ nil))))

(defn- content-security-policy [config]
  (let [auth-origin (safe-origin (:supabase-url config))
        turnstile-origin "https://challenges.cloudflare.com"
        connect-sources (str/join " " (remove nil? ["'self'" "wss:" auth-origin turnstile-origin]))]
    (str "default-src 'self'; connect-src " connect-sources
         "; img-src 'self' data: blob: " (or auth-origin "")
         "; style-src 'self'; script-src 'self' " turnstile-origin
         "; frame-src " turnstile-origin "; object-src 'none'; "
         "base-uri 'none'; frame-ancestors 'none'; form-action 'self'")))

(defn wrap-security-headers [handler config]
  (fn [request]
    (let [response (handler request)
          base {"content-security-policy" (content-security-policy config)
                "permissions-policy" "camera=(), microphone=(), geolocation=(), payment=()"
                "referrer-policy" "strict-origin-when-cross-origin"
                "x-content-type-options" "nosniff"
                "x-frame-options" "DENY"
                "cross-origin-opener-policy" "same-origin"}
          headers (cond-> base
                    (= "production" (:environment config))
                    (assoc "strict-transport-security" "max-age=31536000; includeSubDomains"))]
      (update response :headers #(merge headers (or % {}))))))

(defn read-json-body [request]
  (let [body (:body request)]
    (if (instance? InputStream body)
      (try
        (json/read-value body)
        (catch Exception _
          (errors/fail! 400 "INVALID_JSON" :invalid-request)))
      {})))

(defn wrap-request-id [handler]
  (fn [request]
    (let [id (request-id)
          response (handler (assoc request :request-id id))]
      (update response :headers #(assoc (or % {}) "x-request-id" id)))))

(defn wrap-safe-errors [handler]
  (fn [request]
    (try
      (handler request)
      (catch clojure.lang.ExceptionInfo exception
        (let [{:keys [slopbook/error status code safe-message details headers]} (ex-data exception)]
          (if error
            (json-response status
                           {:error (cond-> {:code code
                                           :message safe-message
                                           :retryable (contains? #{429 502 503 504} status)
                                           :request_id (:request-id request)}
                                     details (assoc :details details))}
                           headers)
            (do
              (binding [*out* *err*]
                (println "Unexpected exception class:" (.getName (class exception))))
              (json-response 500 {:error {:code "INTERNAL_ERROR"
                                          :message (:internal errors/safe-messages)
                                          :retryable true
                                          :request_id (:request-id request)}})))))
      (catch Exception exception
        (binding [*out* *err*]
          (println "Unhandled request exception class:" (.getName (class exception))))
        (json-response 500 {:error {:code "INTERNAL_ERROR"
                                    :message (:internal errors/safe-messages)
                                    :retryable true
                                    :request_id (:request-id request)}})))))

(defn wrap-json-content-type [handler]
  (fn [request]
    (let [method (:request-method request)
          content-type (get-in request [:headers "content-type"] "")]
      (when (and (contains? #{:post :put :patch :delete} method)
                 (str/starts-with? (:uri request) "/api/v1")
                 (not (or (str/starts-with? content-type "application/json")
                          (and (= :post method) (= "/api/v1/media" (:uri request))
                               (str/starts-with? content-type "multipart/form-data")))))
        (errors/fail! 415 "CONTENT_TYPE_REQUIRED" :invalid-request))
      (handler request))))

(defn wrap-idempotency [handler _state]
  (fn [request]
    (let [method (:request-method request)
          domain-write? (and (contains? #{:post :put :patch :delete} method)
                             (str/starts-with? (:uri request) "/api/v1"))]
      (if-not domain-write?
        (handler request)
        (let [key (get-in request [:headers "idempotency-key"])]
          (when-not (and key (<= 16 (count key) 128))
            (errors/fail! 400 "IDEMPOTENCY_KEY_REQUIRED" :invalid-request))
          (handler request))))))
