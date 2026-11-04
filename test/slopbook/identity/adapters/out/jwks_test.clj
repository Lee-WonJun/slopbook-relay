(ns slopbook.identity.adapters.out.jwks-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopbook.identity.adapters.out.jwks :as jwks]
            [slopbook.identity.ports.authentication :as authentication-port])
  (:import [com.nimbusds.jose JWSAlgorithm JWSHeader$Builder]
           [com.nimbusds.jose.crypto ECDSASigner MACSigner]
           [com.nimbusds.jose.jwk Curve JWKSet]
           [com.nimbusds.jose.jwk.gen ECKeyGenerator]
           [com.nimbusds.jwt JWTClaimsSet$Builder SignedJWT]
           [java.util Date List]))

(def now 1784160000000)
(def issuer "https://auth.example.test/auth/v1")
(def audience "authenticated")

(defn key-pair [kid]
  (-> (ECKeyGenerator. Curve/P_256)
      (.keyID kid)
      .generate))

(defn jwk-set [& keys]
  (JWKSet. ^List (mapv #(.toPublicJWK %) keys)))

(defn token
  [signing-key {:keys [kid exp iss aud sub]
                :or {kid (.getKeyID signing-key)
                     exp (+ now 60000)
                     iss issuer
                     aud audience
                     sub "auth-subject-1"}}]
  (let [header (-> (JWSHeader$Builder. JWSAlgorithm/ES256) (.keyID kid) .build)
        claims (-> (JWTClaimsSet$Builder.)
                   (.issuer iss)
                   (.audience ^String aud)
                   (.expirationTime (Date. exp))
                   (.subject sub)
                   .build)
        jwt (SignedJWT. header claims)]
    (.sign jwt (ECDSASigner. signing-key))
    (.serialize jwt)))

(defn auth-adapter [key-set]
  (jwks/adapter {:supabase-jwks-url "https://unused.example.test/jwks"
                 :supabase-issuer issuer
                 :supabase-audience audience
                 :jwks-cache-seconds 300}
                {:fetch-jwks (constantly key-set)
                 :now-ms (constantly now)}))

(def hs-secret (.getBytes "0123456789abcdef0123456789abcdef"))

(defn hs-token
  [{:keys [exp iss aud sub omit-issuer?]
    :or {exp (+ now 60000) iss issuer aud audience sub "legacy-subject-1"}}]
  (let [header (-> (JWSHeader$Builder. JWSAlgorithm/HS256) .build)
        claims-builder (-> (JWTClaimsSet$Builder.)
                           (.audience ^String aud)
                           (.expirationTime (Date. exp))
                           (.subject sub))
        claims-builder (if omit-issuer? claims-builder (.issuer claims-builder iss))
        claims (.build claims-builder)
        jwt (SignedJWT. header claims)]
    (.sign jwt (MACSigner. hs-secret))
    (.serialize jwt)))

(defn error-code [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo exception
      (:code (ex-data exception)))))

(deftest validates-signature-and-required-claims
  (let [trusted (key-pair "trusted")
        other (key-pair "other")
        adapter (auth-adapter (jwk-set trusted))]
    (is (= {:subject "auth-subject-1"}
           (authentication-port/verify-token adapter (token trusted {}))))
    (testing "expiry has a distinct public error code"
      (is (= "AUTH_EXPIRED"
             (error-code #(authentication-port/verify-token
                           adapter (token trusted {:exp (- now 60000)}))))))
    (doseq [[label signed-token]
            [["wrong issuer" (token trusted {:iss "https://wrong.example.test"})]
             ["wrong audience" (token trusted {:aud "wrong"})]
             ["wrong signature" (token other {:kid "trusted"})]]]
      (testing label
        (is (= "AUTH_INVALID"
               (error-code #(authentication-port/verify-token adapter signed-token))))))))

(deftest unknown-kid-forces-one-refresh
  (let [old-key (key-pair "old")
        new-key (key-pair "new")
        calls (atom 0)
        rotating (jwks/adapter {:supabase-issuer issuer
                                :supabase-audience audience
                                :jwks-cache-seconds 300}
                               {:fetch-jwks #(if (= 1 (swap! calls inc))
                                               (jwk-set old-key)
                                               (jwk-set old-key new-key))
                                :now-ms (constantly now)})]
    (is (= {:subject "auth-subject-1"}
           (authentication-port/verify-token rotating (token new-key {}))))
    (is (= 2 @calls)))
  (let [known (key-pair "known")
        unknown (key-pair "unknown")
        calls (atom 0)
        adapter (jwks/adapter {:supabase-issuer issuer
                               :supabase-audience audience
                               :jwks-cache-seconds 300}
                              {:fetch-jwks #(do (swap! calls inc) (jwk-set known))
                               :now-ms (constantly now)})]
    (is (= "AUTH_INVALID"
           (error-code #(authentication-port/verify-token adapter (token unknown {})))))
    (is (= 2 @calls))))

(deftest legacy-hs256-is-verified-by-the-auth-server-only
  (let [calls (atom 0)
        expected-token (hs-token {})
        adapter (jwks/adapter {:supabase-issuer issuer
                               :supabase-audience audience
                               :jwks-cache-seconds 300}
                              {:fetch-jwks #(throw (ex-info "must not load JWKS" {}))
                               :introspect-token (fn [presented]
                                                   (swap! calls inc)
                                                   (is (= expected-token presented))
                                                   {:status :valid :subject "legacy-subject-1"})
                               :now-ms (constantly now)})]
    (is (= {:subject "legacy-subject-1"}
           (authentication-port/verify-token adapter expected-token)))
    (is (= 1 @calls)))
  (testing "self-hosted legacy token without issuer is accepted only after introspection"
    (let [calls (atom 0)
          adapter (jwks/adapter {:supabase-issuer issuer
                                 :supabase-audience audience
                                 :jwks-cache-seconds 300}
                                {:fetch-jwks #(throw (ex-info "must not load JWKS" {}))
                                 :introspect-token (fn [_]
                                                     (swap! calls inc)
                                                     {:status :valid :subject "legacy-subject-1"})
                                 :now-ms (constantly now)})]
      (is (= {:subject "legacy-subject-1"}
             (authentication-port/verify-token adapter (hs-token {:omit-issuer? true}))))
      (is (= 1 @calls))))
  (testing "a present mismatched issuer is rejected before introspection"
    (let [calls (atom 0)
          adapter (jwks/adapter {:supabase-issuer issuer
                                 :supabase-audience audience
                                 :jwks-cache-seconds 300}
                                {:fetch-jwks (constantly (JWKSet.))
                                 :introspect-token (fn [_]
                                                     (swap! calls inc)
                                                     {:status :valid :subject "legacy-subject-1"})
                                 :now-ms (constantly now)})]
      (is (= "AUTH_INVALID"
             (error-code #(authentication-port/verify-token
                           adapter (hs-token {:iss "https://wrong.example.test"})))))
      (is (zero? @calls))))
  (doseq [[label remote]
          [["remote reject" {:status :invalid}]
           ["subject mismatch" {:status :valid :subject "different"}]]]
    (testing label
      (let [adapter (jwks/adapter {:supabase-issuer issuer
                                   :supabase-audience audience
                                   :jwks-cache-seconds 300}
                                  {:fetch-jwks (constantly (JWKSet.))
                                   :introspect-token (constantly remote)
                                   :now-ms (constantly now)})]
        (is (= "AUTH_INVALID"
               (error-code #(authentication-port/verify-token adapter (hs-token {}))))))))
  (let [calls (atom 0)
        adapter (jwks/adapter {:supabase-issuer issuer
                               :supabase-audience audience
                               :jwks-cache-seconds 300}
                              {:fetch-jwks (constantly (JWKSet.))
                               :introspect-token #(do (swap! calls inc)
                                                     {:status :valid :subject "legacy-subject-1"})
                               :now-ms (constantly now)})]
    (is (= "AUTH_EXPIRED"
           (error-code #(authentication-port/verify-token
                         adapter (hs-token {:exp (- now 60000)})))))
    (is (zero? @calls) "expired untrusted claims never reach the Auth server")))
