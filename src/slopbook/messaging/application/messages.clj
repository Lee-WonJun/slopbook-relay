(ns slopbook.messaging.application.messages
  (:require [slopbook.identity.application.characters :as characters]
            [slopbook.messaging.domain.conversation :as conversation]
            [slopbook.messaging.ports.messages :as message-port]
            [slopbook.platform.cursor :as cursor]
            [slopbook.platform.errors :as errors]
            [slopbook.transformation.application.rewrites :as rewrites]))

(def page-size 20)
(def create-keys #{:actor_character_id :recipient_character_id})
(def send-keys #{:actor_character_id :input_text})
(def read-keys #{:actor_character_id :last_read_message_id})
(def delete-keys #{:actor_character_id})

(defn- owned-character [{:keys [identity]} account-id actor-id]
  (characters/require-owned-internal identity account-id actor-id))

(defn- project-conversation [value]
  (cond-> value
    (:_other value) (assoc :other (characters/public-projection (:_other value)))
    true (dissoc :_other)))

(defn create! [{:keys [store] :as messaging} account-id body]
  (errors/assert-allowed-keys! body create-keys)
  (let [actor (owned-character messaging account-id (:actor_character_id body))
        recipient-id (:recipient_character_id body)]
    (when-not (conversation/valid-id? recipient-id)
      (errors/fail! 400 "DM_RECIPIENT_INVALID" :validation))
    (when (= (:id actor) recipient-id)
      (errors/fail! 400 "DM_SELF_FORBIDDEN" :validation))
    (or (some-> (message-port/create-conversation!
                 store {:actor-character-id (:id actor) :recipient-character-id recipient-id})
                project-conversation)
        (errors/fail! 404 "DM_RECIPIENT_UNAVAILABLE" :not-found))))

(defn list! [{:keys [store cursor-secret] :as messaging} account-id actor-id encoded-cursor]
  (let [actor (owned-character messaging account-id actor-id)
        scope (str "conversations:" (:id actor))
        boundary (cursor/decode-for cursor-secret encoded-cursor scope)
        rows (message-port/list-conversations
              store {:actor-character-id (:id actor) :cursor boundary :limit (inc page-size)})
        page (vec (take page-size rows))
        more? (> (count rows) page-size)
        last-row (peek page)]
    {:items (mapv #(-> % project-conversation (dissoc :_sort_at)) page)
     :next_cursor (when (and more? last-row)
                    (cursor/encode cursor-secret
                                   {:scope scope :sort_at (:_sort_at last-row)
                                    :id (:id last-row)}))}))

(defn detail! [{:keys [store cursor-secret] :as messaging}
               account-id conversation-id actor-id encoded-cursor]
  (when-not (conversation/valid-id? conversation-id)
    (errors/fail! 404 "CONVERSATION_NOT_FOUND" :not-found))
  (let [actor (owned-character messaging account-id actor-id)
        context (or (message-port/conversation-context
                     store {:conversation-id conversation-id :actor-character-id (:id actor)})
                    (errors/fail! 404 "CONVERSATION_NOT_FOUND" :not-found))
        scope (str "messages:" conversation-id ":" (:id actor))
        boundary (cursor/decode-for cursor-secret encoded-cursor scope)
        rows (message-port/messages
              store {:conversation-id conversation-id :actor-character-id (:id actor)
                     :cursor boundary :limit (inc page-size)})
        page-desc (vec (take page-size rows))
        more? (> (count rows) page-size)
        last-row (peek page-desc)]
    {:conversation (project-conversation context)
     :messages {:items (mapv #(dissoc % :_created_at) (reverse page-desc))
                :next_cursor (when (and more? last-row)
                               (cursor/encode cursor-secret
                                              {:scope scope
                                               :created_at (:_created_at last-row)
                                               :id (:id last-row)}))}}))

(defn send! [{:keys [store transformation new-id now] :as messaging}
             account-id idempotency-key conversation-id body]
  (errors/assert-allowed-keys! body send-keys)
  (when-not (conversation/valid-input? (:input_text body))
    (errors/fail! 400 "MESSAGE_TOO_LONG" :validation {:maximum 180}))
  (let [actor (owned-character messaging account-id (:actor_character_id body))
        context (or (message-port/conversation-context
                     store {:conversation-id conversation-id :actor-character-id (:id actor)})
                    (errors/fail! 404 "CONVERSATION_NOT_FOUND" :not-found))
        reservation {:account-id account-id :idempotency-key idempotency-key
                     :fingerprint (conversation/idempotency-fingerprint
                                   conversation-id (:id actor) (:persona_version actor))}
        decision (message-port/reserve! store reservation)]
    (case (:state decision)
      :completed (:message decision)
      :processing (errors/fail! 409 "REQUEST_IN_PROGRESS" :conflict)
      :conflict (errors/fail! 409 "IDEMPOTENCY_CONFLICT" :conflict)
      :claimed
      (let [claimed (or (:reservation decision) reservation)]
        (try
          (let [result (rewrites/rewrite! transformation
                                          {:operation "rewrite.message" :grapheme-limit 180
                                           :character actor :input-text (:input_text body)})
                value (conversation/message-value
                       {:id (new-id) :conversation-id conversation-id
                        :sender (characters/public-projection actor)
                        :transformed-text (:text result) :now (now)
                        :members (:member_ids context)})]
            (message-port/publish!
             store {:reservation claimed :message value
                    :ai-run (rewrites/run-metadata "rewrite.message" result)}))
          (catch Exception exception
            (message-port/fail-reservation! store claimed)
            (throw exception)))))))

(defn mark-read! [{:keys [store] :as messaging} account-id conversation-id body]
  (errors/assert-allowed-keys! body read-keys)
  (let [actor (owned-character messaging account-id (:actor_character_id body))]
    (or (message-port/mark-read!
         store {:conversation-id conversation-id :actor-character-id (:id actor)
                :message-id (:last_read_message_id body)})
        (errors/fail! 404 "CONVERSATION_NOT_FOUND" :not-found))))

(defn delete-for-me! [{:keys [store] :as messaging} account-id message-id body]
  (errors/assert-allowed-keys! body delete-keys)
  (let [actor (owned-character messaging account-id (:actor_character_id body))]
    (or (message-port/hide-message!
         store {:message-id message-id :actor-character-id (:id actor)})
        (errors/fail! 404 "MESSAGE_NOT_FOUND" :not-found))))
