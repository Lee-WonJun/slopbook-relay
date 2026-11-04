(ns slopbook.publishing.application.greetings
  (:require [slopbook.identity.application.characters :as characters]
            [slopbook.platform.cursor :as cursor]
            [slopbook.platform.errors :as errors]
            [slopbook.platform.token :as token]
            [slopbook.publishing.domain.content :as content]
            [slopbook.publishing.ports.posts :as post-port]
            [slopbook.shared.domain.greeting :as greeting]
            [slopbook.transformation.application.rewrites :as rewrites])
  (:import [java.time Instant]))

(def preview-ttl-seconds (* 15 60))
(def preview-scope "character-greeting-preview:v1")
(def publish-route "character-greetings.publish")
(def publish-keys #{:actor_character_id :preview_token})
(def ^:private preview-attempt-limit 2)

(defn- rewrite-preview!
  [transformation request]
  (loop [attempt 1]
    (let [outcome (try
                    {:result (rewrites/rewrite! transformation request)}
                    (catch clojure.lang.ExceptionInfo exception
                      {:exception exception}))]
      (if-let [exception (:exception outcome)]
        (if (and (< attempt preview-attempt-limit)
                 (= "AI_PROVIDER_ERROR" (:code (ex-data exception))))
          (recur (inc attempt))
          (throw exception))
        (:result outcome)))))

(defn- account-scope [secret account-id]
  (token/ephemeral-key secret account-id))

(defn preview!
  [{:keys [transformation cursor-secret now]} account-id draft]
  (let [draft (characters/prepare-draft! draft)
        result (rewrite-preview! transformation
                                 {:operation "rewrite.post"
                                  :grapheme-limit 280
                                  :character draft
                                  :input-text (greeting/intent (:output_language draft)
                                                               (:display_name draft))})
        expires-at (str (.plusSeconds (Instant/parse (now)) preview-ttl-seconds))
        payload {:scope preview-scope
                 :account_scope (account-scope cursor-secret account-id)
                 :expires_at expires-at
                 :character_fingerprint (characters/greeting-fingerprint draft)
                 :output_language (:output_language draft)
                 :text (:text result)
                 :ai_run (rewrites/run-metadata "rewrite.post" result)}]
    {:text (:text result)
     :preview_token (cursor/encode cursor-secret payload)
     :expires_at expires-at}))

(defn- invalid-preview! []
  (errors/fail! 400 "GREETING_PREVIEW_INVALID" :validation))

(defn- decode-preview! [secret encoded]
  (try
    (cursor/decode secret encoded)
    (catch Exception _
      (invalid-preview!))))

(defn- verified-preview!
  [{:keys [cursor-secret now]} account-id actor encoded]
  (let [payload (decode-preview! cursor-secret encoded)
        expires-at (try (Instant/parse (:expires_at payload))
                        (catch Exception _ (invalid-preview!)))
        current (Instant/parse (now))]
    (when-not (and (= preview-scope (:scope payload))
                   (= (account-scope cursor-secret account-id) (:account_scope payload))
                   (.isAfter expires-at current)
                   (= (:output_language actor) (:output_language payload))
                   (= (characters/greeting-fingerprint actor)
                      (:character_fingerprint payload))
                   (content/valid-input? (:text payload) 280)
                   (map? (:ai_run payload)))
      (invalid-preview!))
    payload))

(defn publish!
  [{:keys [store identity new-id now] :as deps} account-id body]
  (errors/assert-allowed-keys! body publish-keys)
  (let [actor (characters/require-owned-internal identity account-id
                                                  (:actor_character_id body))
        preview (verified-preview! deps account-id actor (:preview_token body))
        reservation {:account-id account-id
                     :route publish-route
                     :idempotency-key (str "character-greeting:" (:id actor))
                     :fingerprint (content/idempotency-fingerprint
                                   publish-route (:id actor) (:persona_version actor) [])}
        decision (post-port/reserve! store reservation)]
    (case (:state decision)
      :completed (:post decision)
      :processing (errors/fail! 409 "REQUEST_IN_PROGRESS" :conflict)
      :conflict (errors/fail! 409 "IDEMPOTENCY_CONFLICT" :conflict)
      :claimed
      (let [claimed (or (:reservation decision) reservation)]
        (try
          (let [post (content/post-value {:id (new-id)
                                          :now (now)
                                          :character (characters/public-projection actor)
                                          :media []
                                          :transformed-text (:text preview)})]
            (post-port/publish! store {:reservation claimed
                                       :post post
                                       :ai-run (:ai_run preview)}))
          (catch Exception exception
            (post-port/fail-reservation! store claimed)
            (throw exception)))))))
