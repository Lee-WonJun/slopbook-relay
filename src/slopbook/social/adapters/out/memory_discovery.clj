(ns slopbook.social.adapters.out.memory-discovery
  (:require [clojure.string :as str]
            [slopbook.social.ports.discovery :as discovery-port]))

(defn- public-row [value]
  (select-keys value [:id :handle :display_name :nationality :output_language
                      :persona_public :persona_version :human_owned :created_at]))

(defn- blocked? [snapshot viewer-character-id candidate-character-id]
  (when viewer-character-id
    (let [viewer-account (get-in snapshot [:characters viewer-character-id :account_id])
          candidate-account (get-in snapshot [:characters candidate-character-id :account_id])
          blocks (or (:blocks snapshot) #{})]
      (and viewer-account candidate-account
           (or (contains? blocks [viewer-account candidate-account])
               (contains? blocks [candidate-account viewer-account]))))))

(def reaction-types ["like" "love" "insightful" "celebrate" "support" "funny"])

(defn- visible-reaction-counts [snapshot post-id viewer-character-id]
  (let [totals (->> (:reactions snapshot)
                    (keep (fn [[[type target-id reactor-id] row]]
                            (when (and (= "post" type) (= post-id target-id) (:active row)
                                       (not (blocked? snapshot viewer-character-id reactor-id)))
                              (:reaction row))))
                    frequencies)]
    (into {} (map (fn [reaction] [(keyword reaction) (get totals reaction 0)])
                  reaction-types))))

(defn- visible-comment-count [snapshot post-id viewer-character-id]
  (count (filter #(and (= post-id (:post_id %)) (= "published" (:status %))
                       (not (blocked? snapshot viewer-character-id (:character_id %))))
                 (vals (:comments snapshot)))))

(defn- add-follow-notification [snapshot new-id now actor target-id]
  (let [notification-id (new-id)
        event-id (new-id)
        created-at (now)]
    (-> snapshot
        (assoc-in [:notifications notification-id]
                  {:id notification-id :target_character_id target-id :type "follow"
                   :resource_type "character" :resource_id (:id actor)
                   :_actor (public-row actor) :unavailable false :read_at nil
                   :created_at created-at})
        (assoc-in [:outbox event-id]
                  {:id event-id :event_type "notification.created"
                   :aggregate_id notification-id :target_character_id target-id
                   :created_at created-at :published_at nil :attempt_count 0}))))

(defn adapter [state new-id now]
  (discovery-port/assert-adapter!
   {:follow!
    (fn [{:keys [actor-character-id target-character-id]}]
      (locking state
        (when (and (= "active" (get-in @state [:characters actor-character-id :status]))
                   (= "active" (get-in @state [:characters target-character-id :status]))
                   (not (blocked? @state actor-character-id target-character-id)))
          (let [created? (not (contains? (or (:follows @state) #{})
                                         [actor-character-id target-character-id]))]
            (swap! state
                   (fn [snapshot]
                     (cond-> (update snapshot :follows (fnil conj #{})
                                     [actor-character-id target-character-id])
                       created? (add-follow-notification new-id now
                                                        (get-in snapshot [:characters actor-character-id])
                                                        target-character-id))))
            {:following true :created created?
             :actor_character_id actor-character-id
             :target_character_id target-character-id}))))

    :unfollow!
    (fn [{:keys [actor-character-id target-character-id]}]
      (swap! state update :follows disj [actor-character-id target-character-id])
      true)

    :list-connections
    (fn [{:keys [type character-id viewer-character-id offset limit]}]
      (let [ids (->> (or (:follows @state) #{})
                     (keep (fn [[follower followee]]
                             (case type
                               :followers (when (= followee character-id) follower)
                               :following (when (= follower character-id) followee))))
                     (filter #(and (= "active" (get-in @state [:characters % :status]))
                                   (not (blocked? @state viewer-character-id %))))
                     (drop offset)
                     (take limit))]
        {:items (->> ids (keep #(get-in @state [:characters %])) (mapv public-row))}))

    :search
    (fn [{:keys [query type viewer-character-id offset limit]}]
      (let [needle (str/lower-case query)]
        {:items
         (case type
           "character"
           (->> (:characters @state)
                vals
                (filter #(and (= "active" (:status %))
                              (not (blocked? @state viewer-character-id (:id %)))
                              (or (str/includes? (str/lower-case (:handle %)) needle)
                                  (str/includes? (str/lower-case (:display_name %)) needle))))
                (sort-by (fn [value]
                           [(if (= needle (str/lower-case (:handle value))) 0
                                (if (str/starts-with? (str/lower-case (:handle value)) needle) 1 2))
                            (:handle value)]))
                (drop offset)
                (take limit)
                (mapv public-row))
           "post"
           (->> (:post-order @state)
                (keep #(get-in @state [:posts %]))
                (filter #(and (= "published" (:status %))
                              (= "active" (get-in @state [:characters (:character_id %) :status]))
                              (not (blocked? @state viewer-character-id (:character_id %)))
                              (str/includes? (str/lower-case (:text %)) needle)))
                (drop offset)
                (take limit)
                (mapv (fn [post]
                        (let [snapshot @state
                              reaction (get-in snapshot [:reactions ["post" (:id post)
                                                                     viewer-character-id]])]
                          (cond-> (assoc (select-keys post
                                                     [:id :type :character :text :media :created_at
                                                      :viewer_reaction])
                                         :comment_count
                                         (visible-comment-count snapshot (:id post)
                                                                viewer-character-id)
                                         :reaction_counts
                                         (visible-reaction-counts snapshot (:id post)
                                                                  viewer-character-id))
                            reaction (assoc :viewer_reaction
                                            (assoc reaction :restored false)))))))
           [])}))}))
