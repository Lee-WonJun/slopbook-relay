(ns slopbook.identity.adapters.out.jwks
  (:require [clojure.string :as str]
            [slopbook.identity.ports.authentication :as authentication-port]
            [slopbook.platform.errors :as errors]
            [slopbook.platform.json :as json])
  (:import [com.nimbusds.jose JWSAlgorithm]
           [com.nimbusds.jose.crypto ECDSAVerifier RSASSAVerifier]
           [com.nimbusds.jose.jwk ECKey JWK JWKSet RSAKey]
           [com.nimbusds.jwt SignedJWT]
           [java.net URI]
           [java.net.http HttpClient HttpClient$Version HttpRequest
            HttpResponse HttpResponse$BodyHandlers]
           [java.time Duration]
           [java.util Date]))

(def allowed-asymmetric-algorithms #{"ES256" "RS256"})
(def clock-skew-ms 30000)

(defn- invalid! []
  (errors/fail! 401 "AUTH_INVALID" :unauthorized))

(defn- expired! []
  (errors/fail! 401 "AUTH_EXPIRED" :unauthorized))

(defn- default-fetcher [url]
  (let [client (-> (HttpClient/newBuilder)
                   (.version HttpClient$Version/HTTP_2)
                   (.connectTimeout (Duration/ofSeconds 3))
                   .build)]
    (fn []
      (try
        (let [request (-> (HttpRequest/newBuilder (URI/create url))
                          (.timeout (Duration/ofSeconds 5))
                          (.header "accept" "application/json")
                          .GET
                          .build)
              response (.send client request (HttpResponse$BodyHandlers/ofString))]
          (when-not (= 200 (.statusCode response))
            (throw (ex-info "JWKS endpoint unavailable" {})))
          (when (> (count (.body response)) 262144)
            (throw (ex-info "JWKS document exceeds size limit" {})))
          (JWKSet/parse (.body response)))
        (catch Exception _
          (errors/fail! 503 "AUTH_UNAVAILABLE" :auth-unavailable))))))

(defn- default-introspector [supabase-url publishable-key]
  (let [client (-> (HttpClient/newBuilder)
                   (.version HttpClient$Version/HTTP_2)
                   (.connectTimeout (Duration/ofSeconds 3))
                   .build)
        endpoint (str (str/replace supabase-url #"/$" "") "/auth/v1/user")]
    (fn [token]
      (try
        (let [request (-> (HttpRequest/newBuilder (URI/create endpoint))
                          (.timeout (Duration/ofSeconds 5))
                          (.header "accept" "application/json")
                          (.header "apikey" publishable-key)
                          (.header "authorization" (str "Bearer " token))
                          .GET
                          .build)
              response (.send client request (HttpResponse$BodyHandlers/ofString))
              status (.statusCode response)]
          (cond
            (= 200 status)
            (do
              (when (> (count (.body response)) 262144)
                (errors/fail! 503 "AUTH_UNAVAILABLE" :auth-unavailable))
              {:status :valid :subject (:id (json/read-value (.body response)))})

            (contains? #{401 403} status) {:status :invalid}
            :else (errors/fail! 503 "AUTH_UNAVAILABLE" :auth-unavailable)))
        (catch clojure.lang.ExceptionInfo exception
          (throw exception))
        (catch Exception _
          (errors/fail! 503 "AUTH_UNAVAILABLE" :auth-unavailable))))))

(defn- public-verifier [^JWK key algorithm]
  (let [public-key (.toPublicJWK key)]
    (cond
      (and (= "ES256" algorithm) (instance? ECKey public-key))
      (ECDSAVerifier. ^ECKey public-key)

      (and (= "RS256" algorithm) (instance? RSAKey public-key))
      (RSASSAVerifier. ^RSAKey public-key)

      :else nil)))

(defn adapter
  ([config]
   (adapter config {}))
  ([{:keys [supabase-url supabase-publishable-key supabase-jwks-url supabase-issuer
            supabase-audience jwks-cache-seconds]}
    {:keys [fetch-jwks introspect-token now-ms]}]
   (let [fetch-jwks (or fetch-jwks (default-fetcher supabase-jwks-url))
         introspect-token (or introspect-token
                              (when (and supabase-url supabase-publishable-key)
                                (default-introspector supabase-url supabase-publishable-key))
                              (fn [_]
                                (errors/fail! 503 "AUTH_UNAVAILABLE" :auth-unavailable)))
         now-ms (or now-ms #(System/currentTimeMillis))
         cache (atom nil)
         ttl-ms (* 1000 jwks-cache-seconds)
         load-jwks! (fn [force?]
                      (let [{:keys [value fetched-at]} @cache
                            current (now-ms)]
                        (if (and (not force?) value (< (- current fetched-at) ttl-ms))
                          value
                          (locking cache
                            (let [{:keys [value fetched-at]} @cache
                                  current (now-ms)]
                              (if (and (not force?) value (< (- current fetched-at) ttl-ms))
                                value
                                (let [fresh (fetch-jwks)]
                                  (when-not (instance? JWKSet fresh)
                                    (errors/fail! 503 "AUTH_UNAVAILABLE" :auth-unavailable))
                                  (reset! cache {:value fresh :fetched-at current})
                                  fresh)))))))]
     (authentication-port/assert-adapter!
      {:verify-token
       (fn [token]
         (when-not (and (string? token) (<= 32 (count token) 16384))
           (invalid!))
         (let [signed-jwt (try (SignedJWT/parse token) (catch Exception _ (invalid!)))
               header (.getHeader signed-jwt)
               algorithm (str (.getAlgorithm header))
               kid (.getKeyID header)
               claims (.getJWTClaimsSet signed-jwt)
               expiration (.getExpirationTime claims)
               issuer (.getIssuer claims)
               audience (set (.getAudience claims))
               subject (.getSubject claims)
               expiry-cutoff (Date. (- (now-ms) clock-skew-ms))]
           (when (and expiration (.before expiration expiry-cutoff))
             (expired!))
           (when-not (and expiration
                          (contains? audience supabase-audience)
                          (not (str/blank? subject)))
             (invalid!))
           (cond
             (= "HS256" algorithm)
             (let [_ (when (and (not (str/blank? issuer))
                                (not= supabase-issuer issuer))
                       (invalid!))
                   {remote-status :status remote-subject :subject} (introspect-token token)]
               (when-not (and (= :valid remote-status)
                              (= subject remote-subject)
                              (not (str/blank? remote-subject)))
                 (invalid!))
               {:subject subject})

             (and (contains? allowed-asymmetric-algorithms algorithm)
                  (not (str/blank? kid)))
             (let [_ (when-not (= supabase-issuer issuer)
                       (invalid!))
                   initial-set (load-jwks! false)
                   initial-key (.getKeyByKeyId initial-set kid)
                   key (or initial-key (.getKeyByKeyId (load-jwks! true) kid))
                   verifier (when key (public-verifier key algorithm))]
               (when-not (and verifier (.verify signed-jwt verifier))
                 (invalid!))
               {:subject subject})

             :else (invalid!))))

       :bust-cache!
       (fn []
         (reset! cache nil)
         true)}))))
