(ns slopbook.messaging.domain.conversation
  (:require [slopbook.shared.domain.text :as text])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.util UUID]))

(defn valid-id? [value]
  (try (UUID/fromString value) true (catch Exception _ false)))

(defn canonical-pair [left right]
  (when (= left right)
    (throw (ex-info "A direct conversation requires two characters" {:code :same-character})))
  (if (neg? (compare left right)) [left right] [right left]))

(defn valid-input? [value]
  (text/within-graphemes? value 1 180))

(defn message-value [{:keys [id conversation-id sender transformed-text now members]}]
  (when-not (contains? (set members) (:id sender))
    (throw (ex-info "Sender is not a conversation member" {:code :sender-not-member})))
  {:id id
   :conversation_id conversation-id
   :sender_character_id (:id sender)
   :sender sender
   :text transformed-text
   :status "sent"
   :created_at now
   :read false})

(defn idempotency-fingerprint [conversation-id actor-id persona-version]
  (.digest (MessageDigest/getInstance "SHA-256")
           (.getBytes
            (text/canonical-json {:route "messages.create"
                                  :conversation_id conversation-id
                                  :actor_character_id actor-id
                                  :persona_version persona-version})
            StandardCharsets/UTF_8)))
