(ns slopbook.platform.cursor
  (:require [clojure.string :as str]
            [slopbook.platform.errors :as errors]
            [slopbook.platform.json :as json])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.util Base64]
           [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]))

(def encoder (.withoutPadding (Base64/getUrlEncoder)))
(def decoder (Base64/getUrlDecoder))

(defn- utf8 [value]
  (.getBytes (str value) StandardCharsets/UTF_8))

(defn- b64 [value]
  (.encodeToString encoder value))

(defn- hmac [secret payload]
  (let [mac (Mac/getInstance "HmacSHA256")]
    (.init mac (SecretKeySpec. (utf8 secret) "HmacSHA256"))
    (.doFinal mac (utf8 payload))))

(defn encode [secret value]
  (let [payload (b64 (utf8 (json/write-value value)))]
    (str payload "." (b64 (hmac secret payload)))))

(defn decode [secret token]
  (when token
    (try
      (let [[payload signature & extra] (str/split token #"\." -1)
            expected (hmac secret payload)
            actual (.decode decoder signature)]
        (when (or extra (str/blank? payload) (str/blank? signature)
                  (not (MessageDigest/isEqual expected actual)))
          (throw (IllegalArgumentException. "invalid cursor signature")))
        (json/read-value (String. (.decode decoder payload) StandardCharsets/UTF_8)))
      (catch Exception _
        (errors/fail! 400 "CURSOR_INVALID" :invalid-request)))))

(defn decode-for
  "Decodes a cursor and binds it to one endpoint/resource scope. A valid cursor
  from a different collection is deliberately rejected."
  [secret token expected-scope]
  (when token
    (let [value (decode secret token)]
      (when-not (= expected-scope (:scope value))
        (errors/fail! 400 "CURSOR_INVALID" :invalid-request))
      value)))
