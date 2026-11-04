(ns slopbook.social.adapters.out.memory-reactions
  (:require [slopbook.social.domain.reaction :as reaction]
            [slopbook.social.ports.reactions :as reaction-port]))

(defn- blocked? [snapshot viewer-character-id author-character-id]
  (when (and viewer-character-id author-character-id)
    (let [viewer-account (get-in snapshot [:characters viewer-character-id :account_id])
          author-account (get-in snapshot [:characters author-character-id :account_id])
          blocks (or (:blocks snapshot) #{})]
      (and viewer-account author-account
           (or (contains? blocks [viewer-account author-account])
               (contains? blocks [author-account viewer-account]))))))

(defn- target [state type id actor-character-id]
  (case type
    "post" (when-let [post (get-in @state [:posts id])]
             (when (and (= "published" (:status post))
                        (not (blocked? @state actor-character-id (:character_id post))))
               {:id id :type "post" :text (:text post) :character (:character post)}))
    "comment" (when-let [value (get-in @state [:comments id])]
                (when (and (= "published" (:status value))
                           (not (blocked? @state actor-character-id (:character_id value))))
                  {:id id :type "comment" :post-id (:post_id value)
                   :text (:text value) :character (:character value)}))
    nil))

(defn- counts [state type id viewer-character-id]
  (let [rows (->> (:reactions @state)
                  (filter (fn [[[row-type row-id reactor-id] row]]
                            (and (= type row-type) (= id row-id) (:active row)
                                 (not (blocked? @state viewer-character-id reactor-id)))))
                  (map (comp :reaction val))
                  frequencies)]
    {:like (get rows "like" 0)
     :love (get rows "love" 0)
     :insightful (get rows "insightful" 0)
     :celebrate (get rows "celebrate" 0)
     :support (get rows "support" 0)
     :funny (get rows "funny" 0)}))

(defn- add-reaction-notification [snapshot new-id now actor target transition]
  (let [target-id (get-in target [:character :id])]
    (if (or (nil? target-id) (= target-id (:id actor)))
      snapshot
      (let [notification-id (new-id)
            event-id (new-id)
            created-at (now)]
        (-> snapshot
            (assoc-in [:notifications notification-id]
                      {:id notification-id :target_character_id target-id :type "reaction"
                       :resource_type (:type target) :resource_id (:id target)
                       :post_id (:post-id target)
                       :reaction (:reaction transition) :_actor actor :unavailable false
                       :read_at nil :created_at created-at})
            (assoc-in [:outbox event-id]
                      {:id event-id :event_type "notification.created"
                       :aggregate_id notification-id :target_character_id target-id
                       :created_at created-at :published_at nil :attempt_count 0}))))))

(defn adapter [state new-id now]
  (reaction-port/assert-adapter!
   {:target-public
    (fn [{:keys [type id actor-character-id]}]
      (target state type id actor-character-id))

    :toggle!
    (fn [{:keys [type target actor-character-id decide]}]
      (locking state
        (let [key [type (:id target) actor-character-id]
              existing (get-in @state [:reactions key])
              decision (when-not existing (decide))
              transition (reaction/transition existing (:reaction decision))
              value (select-keys transition [:reaction :active])]
          (swap! state assoc-in [:reactions key] value)
          (when decision
            (swap! state
                   (fn [snapshot]
                     (-> snapshot
                         (update :ai-runs (fnil conj []) (:ai-run decision))
                         (add-reaction-notification new-id now
                                                    (get-in snapshot [:characters actor-character-id])
                                                    target transition)))))
          (let [totals (counts state type (:id target) actor-character-id)
                global-totals (counts state type (:id target) nil)]
            (case type
              "post" (swap! state assoc-in [:posts (:id target) :reaction_counts] global-totals)
              "comment" (swap! state assoc-in [:comments (:id target) :reaction_counts]
                               global-totals)
              nil)
            (assoc (select-keys transition [:reaction :active :restored])
                   :counts totals)))))}))
