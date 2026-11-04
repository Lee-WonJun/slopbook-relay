(ns slopbook.platform.token
  (:import [java.security MessageDigest SecureRandom]
           [java.util Base64]))

(def secure-random (SecureRandom.))
(def encoder (.withoutPadding (Base64/getUrlEncoder)))

(defn random-token []
  (let [value (byte-array 32)]
    (.nextBytes secure-random value)
    (.encodeToString encoder value)))

(defn digest [value]
  (.digest (MessageDigest/getInstance "SHA-256")
           (.getBytes (str value) java.nio.charset.StandardCharsets/UTF_8)))

(defn digest-equal? [left right]
  (and (some? left) (some? right)
       (MessageDigest/isEqual ^bytes left ^bytes right)))

(defn digest-token? [plain expected-digest]
  (and (string? plain)
       (digest-equal? (digest plain) expected-digest)))

(defn ephemeral-key [salt value]
  (.encodeToString encoder (digest (str salt "\u0000" (or value "unknown")))))
