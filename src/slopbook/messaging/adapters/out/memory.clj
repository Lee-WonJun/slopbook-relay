(ns slopbook.messaging.adapters.out.memory
  (:require [slopbook.messaging.domain.conversation :as conversation]
            [slopbook.messaging.ports.messages :as message-port])
  (:import [java.util Arrays]))

(defn- blocked? [snapshot left right]
  (let [left-account (get-in snapshot [:characters left :account_id])
        right-account (get-in snapshot [:characters right :account_id])]
    (or (contains? (or (:blocks snapshot) #{}) [left-account right-account])
        (contains? (or (:blocks snapshot) #{}) [right-account left-account]))))

(defn- context [snapshot conversation-id actor-id]
  (when-let [value (get-in snapshot [:conversations conversation-id])]
    (let [members (:member_ids value)
          other-id (first (remove #{actor-id} members))]
      (when (and (= "active" (:status value))
                 (contains? (set members) actor-id)
                 (= "active" (get-in snapshot [:characters actor-id :status]))
                 (= "active" (get-in snapshot [:characters other-id :status]))
                 (not (blocked? snapshot actor-id other-id)))
        {:id conversation-id :status "active" :created_at (:created_at value)
         :member_ids members
         :other_last_read_at (get-in snapshot [:conversation-members [conversation-id other-id] :last_read_at])
         :_other (get-in snapshot [:characters other-id])}))))

(defn- public-message [message]
  (select-keys message [:id :conversation_id :sender_character_id :sender :text
                        :status :created_at :read]))

(defn adapter [state new-id now]
  (message-port/assert-adapter!
   {:create-conversation!
    (fn [{:keys [actor-character-id recipient-character-id]}]
      (locking state
        (let [snapshot @state
              recipient (get-in snapshot [:characters recipient-character-id])]
          (when (and (= "active" (:status recipient))
                     (not (blocked? snapshot actor-character-id recipient-character-id)))
            (let [pair (conversation/canonical-pair actor-character-id recipient-character-id)
                  existing-id (get-in snapshot [:conversation-pairs pair])
                  id (or existing-id (new-id))
                  created? (nil? existing-id)
                  created-at (or (get-in snapshot [:conversations id :created_at]) (now))]
              (swap! state
                     (fn [value]
                       (-> value
                           (assoc-in [:conversation-pairs pair] id)
                           (assoc-in [:conversations id]
                                     {:id id :member_ids pair :status "active"
                                      :created_at created-at
                                      :last_message_at (get-in value [:conversations id :last_message_at])})
                           (assoc-in [:conversation-members [id actor-character-id]]
                                     (or (get-in value [:conversation-members [id actor-character-id]]) {}))
                           (assoc-in [:conversation-members [id recipient-character-id]]
                                     (or (get-in value [:conversation-members [id recipient-character-id]]) {})))))
              (assoc (context @state id actor-character-id) :created created?))))))

    :list-conversations
    (fn [{:keys [actor-character-id cursor limit]}]
      (let [before? (fn [value]
                      (if-not cursor true
                              (neg? (compare [(:_sort_at value) (:id value)]
                                             [(:sort_at cursor) (:id cursor)]))))]
        (->> (:conversations @state)
             vals
             (keep (fn [value]
                     (when-let [ctx (context @state (:id value) actor-character-id)]
                       (let [visible (->> (:messages @state)
                                          vals
                                          (filter #(and (= (:id value) (:conversation_id %))
                                                        (= "sent" (:status %))
                                                        (not (contains? (or (:message-visibility @state) #{})
                                                                        [(:id %) actor-character-id]))))
                                          (sort-by (juxt :created_at :id) #(compare %2 %1)))
                             latest (first visible)
                             read-at (get-in @state [:conversation-members [(:id value) actor-character-id] :last_read_at])
                             unread (count (filter #(and (not= actor-character-id (:sender_character_id %))
                                                         (or (nil? read-at) (pos? (compare (:created_at %) read-at))))
                                                   visible))
                             sort-at (or (:created_at latest) (:created_at value))]
                         {:id (:id value) :status "active" :created_at (:created_at value)
                          :last_message_at sort-at :preview (:text latest) :unread_count unread
                          :_sort_at sort-at :_other (:_other ctx)}))))
             (filter before?)
             (sort-by (juxt :_sort_at :id) #(compare %2 %1))
             (take limit)
             vec)))

    :conversation-context
    (fn [{:keys [conversation-id actor-character-id]}]
      (context @state conversation-id actor-character-id))

    :messages
    (fn [{:keys [conversation-id actor-character-id cursor limit]}]
      (when (context @state conversation-id actor-character-id)
        (let [actor-read-at (get-in @state [:conversation-members [conversation-id actor-character-id] :last_read_at])
              other-id (first (remove #{actor-character-id}
                                      (get-in @state [:conversations conversation-id :member_ids])))
              other-read-at (get-in @state [:conversation-members [conversation-id other-id] :last_read_at])
              before? (fn [value]
                        (if-not cursor true
                                (neg? (compare [(:created_at value) (:id value)]
                                               [(:created_at cursor) (:id cursor)]))))]
          (->> (:messages @state)
               vals
               (filter #(and (= conversation-id (:conversation_id %))
                             (= "sent" (:status %))
                             (not (contains? (or (:message-visibility @state) #{})
                                             [(:id %) actor-character-id]))
                             (before? %)))
               (sort-by (juxt :created_at :id) #(compare %2 %1))
               (take limit)
               (mapv (fn [value]
                       (assoc (public-message value)
                              :_created_at (:created_at value)
                              :read (boolean
                                     (let [watermark (if (= actor-character-id (:sender_character_id value))
                                                       other-read-at actor-read-at)]
                                       (and watermark (not (pos? (compare (:created_at value) watermark)))))))))))))

    :reserve!
    (fn [{:keys [account-id idempotency-key fingerprint] :as reservation}]
      (locking state
        (let [scope [account-id "messages.create" idempotency-key]
              existing (get-in @state [:idempotency scope])]
          (cond
            (and existing (not (Arrays/equals ^bytes fingerprint ^bytes (:fingerprint existing))))
            {:state :conflict}
            (= :completed (:status existing))
            {:state :completed :message (public-message (get-in @state [:messages (:resource-id existing)]))}
            (= :processing (:status existing)) {:state :processing}
            :else (do (swap! state assoc-in [:idempotency scope]
                             {:status :processing :fingerprint fingerprint})
                      {:state :claimed :reservation (assoc reservation :scope scope)})))))

    :publish!
    (fn [{:keys [reservation message ai-run]}]
      (locking state
        (when-not (context @state (:conversation_id message) (:sender_character_id message))
          (throw (ex-info "Conversation unavailable during message transaction" {})))
        (let [receiver-id (first (remove #{(:sender_character_id message)}
                                        (get-in @state [:conversations (:conversation_id message) :member_ids])))
              notification-id (new-id)
              notification-event-id (new-id)
              message-event-id (new-id)
              created-at (:created_at message)]
          (swap! state
                 (fn [snapshot]
                   (-> snapshot
                       (assoc-in [:messages (:id message)] message)
                       (update :message-order (fnil conj []) (:id message))
                       (assoc-in [:conversations (:conversation_id message) :last_message_at] created-at)
                       (update :ai-runs (fnil conj []) ai-run)
                       (assoc-in [:notifications notification-id]
                                 {:id notification-id :target_character_id receiver-id :type "dm"
                                  :resource_type "message" :resource_id (:id message)
                                  :_actor (get-in snapshot [:characters (:sender_character_id message)])
                                  :unavailable false :read_at nil :created_at created-at})
                       (assoc-in [:outbox notification-event-id]
                                 {:id notification-event-id :event_type "notification.created"
                                  :aggregate_id notification-id :target_character_id receiver-id
                                  :created_at created-at :published_at nil :attempt_count 0})
                       (assoc-in [:outbox message-event-id]
                                 {:id message-event-id :event_type "dm.message.created"
                                  :aggregate_id (:id message) :target_character_id receiver-id
                                  :created_at created-at :published_at nil :attempt_count 0})
                       (assoc-in [:idempotency (:scope reservation)]
                                 {:status :completed :fingerprint (:fingerprint reservation)
                                  :resource-id (:id message)}))))
          (public-message message))))

    :fail-reservation!
    (fn [{:keys [scope fingerprint]}]
      (swap! state assoc-in [:idempotency scope]
             {:status :failed-retryable :fingerprint fingerprint})
      true)

    :mark-read!
    (fn [{:keys [conversation-id actor-character-id message-id]}]
      (locking state
        (let [message (get-in @state [:messages message-id])]
          (when (and (context @state conversation-id actor-character-id)
                     (= conversation-id (:conversation_id message))
                     (= "sent" (:status message)))
            (let [path [:conversation-members [conversation-id actor-character-id]]
                  other-id (first (remove #{actor-character-id}
                                          (get-in @state [:conversations conversation-id :member_ids])))
                  current-id (get-in @state (conj path :last_read_message_id))
                  current (get-in @state [:messages current-id])
                  advance? (or (nil? current)
                               (neg? (compare [(:created_at current) (:id current)]
                                              [(:created_at message) (:id message)])))
                  read-at (now)]
              (when advance?
                (let [event-id (new-id)]
                  (swap! state
                         (fn [snapshot]
                           (-> snapshot
                               (update-in path merge
                                          {:last_read_message_id message-id :last_read_at read-at})
                               (assoc-in [:outbox event-id]
                                         {:id event-id :event_type "dm.message.read"
                                          :aggregate_id conversation-id
                                          :target_character_id other-id
                                          :created_at read-at :published_at nil
                                          :attempt_count 0}))))))
              {:conversation_id conversation-id
               :last_read_message_id (if advance? message-id current-id)
               :last_read_at (if advance? read-at (get-in @state (conj path :last_read_at)))})))))

    :hide-message!
    (fn [{:keys [message-id actor-character-id]}]
      (locking state
        (let [message (get-in @state [:messages message-id])]
          (when (= actor-character-id (:sender_character_id message))
            (swap! state update :message-visibility (fnil conj #{}) [message-id actor-character-id])
            true))))}))
