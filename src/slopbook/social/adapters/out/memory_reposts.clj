(ns slopbook.social.adapters.out.memory-reposts
  (:require [slopbook.social.domain.repost :as repost]
            [slopbook.social.ports.reposts :as repost-port]))

(defn- blocked? [snapshot viewer-character-id other-character-id]
  (when (and viewer-character-id other-character-id)
    (let [viewer-account (get-in snapshot [:characters viewer-character-id :account_id])
          other-account (get-in snapshot [:characters other-character-id :account_id])
          blocks (or (:blocks snapshot) #{})]
      (and viewer-account other-account
           (or (contains? blocks [viewer-account other-account])
               (contains? blocks [other-account viewer-account]))))))

(defn- visible-count [snapshot post-id viewer-character-id]
  (count
   (filter (fn [[[row-post-id reposter-id] row]]
             (and (= post-id row-post-id)
                  (:active row)
                  (= "active" (get-in snapshot [:characters reposter-id :status]))
                  (not (blocked? snapshot viewer-character-id reposter-id))))
           (:reposts snapshot))))

(defn- add-notification [snapshot new-id now actor target]
  (let [target-character-id (:character-id target)]
    (if (= (:id actor) target-character-id)
      snapshot
      (let [notification-id (new-id)
            event-id (new-id)
            created-at (now)]
        (-> snapshot
            (assoc-in [:notifications notification-id]
                      {:id notification-id :target_character_id target-character-id
                       :type "repost" :resource_type "post" :resource_id (:id target)
                       :reaction nil :_actor actor :unavailable false
                       :read_at nil :created_at created-at})
            (assoc-in [:outbox event-id]
                      {:id event-id :event_type "notification.created"
                       :aggregate_id notification-id :target_character_id target-character-id
                       :created_at created-at :published_at nil :attempt_count 0}))))))

(defn adapter [state new-id now]
  (repost-port/assert-adapter!
   {:target-public
    (fn [{:keys [id actor-character-id]}]
      (when-let [post (get-in @state [:posts id])]
        (let [author-id (:character_id post)]
          (when (and (= "published" (:status post))
                     (not (blocked? @state actor-character-id author-id)))
            {:id id
             :character-id author-id
             :owner-account-id (get-in @state [:characters author-id :account_id])}))))

    :toggle!
    (fn [{:keys [target actor actor-character-id persona-snapshot]}]
      (locking state
        (let [key [(:id target) actor-character-id]
              existing (get-in @state [:reposts key])
              transition (repost/transition existing)
              created-at (or (:created_at existing) (now))
              value {:active (:active transition)
                     :character persona-snapshot
                     :created_at created-at}]
          (swap! state assoc-in [:reposts key] value)
          (when (:new-repost transition)
            (swap! state add-notification new-id now actor target))
          {:active (:active transition)
           :restored (:restored transition)
           :repost_count (visible-count @state (:id target) actor-character-id)})))}))
