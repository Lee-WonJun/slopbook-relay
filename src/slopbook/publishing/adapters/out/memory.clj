(ns slopbook.publishing.adapters.out.memory
  (:require [slopbook.publishing.domain.content :as content]
            [slopbook.publishing.ports.posts :as post-port])
  (:import [java.time Duration Instant]
           [java.util Arrays]))

(defn- blocked? [snapshot viewer-character-id author-character-id]
  (when viewer-character-id
    (let [viewer-account (get-in snapshot [:characters viewer-character-id :account_id])
          author-account (get-in snapshot [:characters author-character-id :account_id])
          blocks (or (:blocks snapshot) #{})]
      (and viewer-account author-account
           (or (contains? blocks [viewer-account author-account])
               (contains? blocks [author-account viewer-account]))))))

(def reaction-types ["like" "love" "insightful" "celebrate" "support" "funny"])

(defn- visible-reaction-counts [snapshot type target-id viewer-character-id]
  (let [totals (->> (:reactions snapshot)
                    (keep (fn [[[row-type row-id reactor-id] row]]
                            (when (and (= type row-type) (= target-id row-id)
                                       (:active row)
                                       (not (blocked? snapshot viewer-character-id reactor-id)))
                              (:reaction row))))
                    frequencies)]
    (into {} (map (fn [reaction] [(keyword reaction) (get totals reaction 0)])
                  reaction-types))))

(defn- visible-comment-count [snapshot post-id viewer-character-id]
  (count (filter #(and (= post-id (:post_id %))
                       (= "published" (:status %))
                       (not (blocked? snapshot viewer-character-id (:character_id %))))
                 (vals (:comments snapshot)))))

(defn- visible-reposts [snapshot post-id viewer-character-id]
  (->> (or (:reposts snapshot) {})
       (keep (fn [[[row-post-id reposter-id] row]]
               (when (and (= post-id row-post-id)
                          (:active row)
                          (= "active" (get-in snapshot [:characters reposter-id :status]))
                          (not (blocked? snapshot viewer-character-id reposter-id)))
                 (assoc row :character_id reposter-id))))))

(defn- latest-repost [snapshot post-id viewer-character-id allowed-character-ids as-of]
  (->> (visible-reposts snapshot post-id viewer-character-id)
       (filter #(or (nil? allowed-character-ids)
                    (contains? allowed-character-ids (:character_id %))))
       (filter #(not (.isAfter (Instant/parse (:created_at %)) (Instant/parse as-of))))
       (sort-by (juxt :created_at :character_id) #(compare %2 %1))
       first))

(defn- viewer-repost [snapshot post-id viewer-character-id]
  (when-let [row (and viewer-character-id
                      (get-in snapshot [:reposts [post-id viewer-character-id]]))]
    {:active (boolean (:active row)) :restored false}))

(defn- public-post [snapshot post viewer-character-id]
  (assoc (content/public-post post)
         :comment_count (visible-comment-count snapshot (:id post) viewer-character-id)
         :reaction_counts (visible-reaction-counts snapshot "post" (:id post)
                                                   viewer-character-id)
         :repost_count (count (visible-reposts snapshot (:id post) viewer-character-id))
         :viewer_repost (viewer-repost snapshot (:id post) viewer-character-id)))

(defn- external-account-count [snapshot author-account-id character-ids]
  (->> character-ids
       (keep #(get-in snapshot [:characters % :account_id]))
       (remove #(= author-account-id %))
       distinct
       count))

(defn- hot-engagement [snapshot post viewer-character-id]
  (let [post-id (:id post)
        author-account-id (get-in snapshot [:characters (:character_id post) :account_id])
        reactors (keep (fn [[[type target-id character-id] row]]
                         (when (and (= "post" type) (= post-id target-id) (:active row)
                                    (= "active" (get-in snapshot [:characters character-id :status]))
                                    (not (blocked? snapshot viewer-character-id character-id)))
                           character-id))
                       (:reactions snapshot))
        commenters (keep (fn [row]
                           (let [character-id (:character_id row)]
                             (when (and (= post-id (:post_id row))
                                        (= "published" (:status row))
                                        character-id
                                        (= "active" (get-in snapshot [:characters character-id :status]))
                                        (not (blocked? snapshot viewer-character-id character-id)))
                               character-id)))
                         (vals (:comments snapshot)))
        reposters (map :character_id (visible-reposts snapshot post-id viewer-character-id))]
    {:reactors (external-account-count snapshot author-account-id reactors)
     :commenters (external-account-count snapshot author-account-id commenters)
     :reposters (external-account-count snapshot author-account-id reposters)}))

(defn- hot-score [snapshot post viewer-character-id as-of]
  (let [published (Instant/parse (:created_at post))
        as-of-instant (Instant/parse as-of)
        age-hours (max 0.0 (/ (.toMillis (Duration/between published as-of-instant)) 3600000.0))
        {:keys [reactors commenters reposters]} (hot-engagement snapshot post viewer-character-id)]
    (/ (+ reactors (* 2 commenters) (* 3 reposters) 1.0)
       (Math/pow (+ age-hours 2.0) 1.5))))

(defn adapter [state]
  (post-port/assert-adapter!
   {:reserve!
    (fn [{:keys [account-id idempotency-key fingerprint route] :as reservation}]
      (locking state
        (let [scope [account-id (or route "posts.create") idempotency-key]
              existing (get-in @state [:idempotency scope])]
          (cond
            (and existing (not (Arrays/equals ^bytes fingerprint ^bytes (:fingerprint existing))))
            {:state :conflict}

            (= :completed (:status existing))
            {:state :completed :post (content/public-post (get-in @state [:posts (:resource-id existing)]))}

            (= :processing (:status existing))
            {:state :processing}

            :else
            (do
              (swap! state assoc-in [:idempotency scope]
                     {:status :processing :fingerprint fingerprint})
              {:state :claimed :reservation (assoc reservation :scope scope)})))))

    :publish!
    (fn [{:keys [reservation post ai-run]}]
      (locking state
        (swap! state (fn [snapshot]
                       (let [attached (reduce (fn [value media-id]
                                                (assoc-in value [:media media-id :status] "attached"))
                                              snapshot (:media_ids post))]
                         (-> attached
                             (assoc-in [:posts (:id post)] post)
                             (update :post-order #(vec (cons (:id post) %)))
                             (update :ai-runs (fnil conj []) ai-run)
                             (assoc-in [:idempotency (:scope reservation)]
                                       {:status :completed
                                        :fingerprint (:fingerprint reservation)
                                        :resource-id (:id post)})))))
        (content/public-post post)))

    :fail-reservation!
    (fn [{:keys [scope fingerprint]}]
      (swap! state assoc-in [:idempotency scope]
             {:status :failed-retryable :fingerprint fingerprint})
      true)

    :feed
    (fn [{:keys [mode viewer-character-id cursor as-of limit]}]
      (let [snapshot @state
            following (->> (or (:follows snapshot) #{})
                           (keep (fn [[follower followee]]
                                   (when (= viewer-character-id follower) followee)))
                           set)
            feed-post
            (fn [internal]
              (let [author-followed? (contains? following (:character_id internal))
                    repost (latest-repost snapshot (:id internal) viewer-character-id
                                          nil
                                          as-of)]
                (when (or (not= "following" mode) author-followed?)
                  (let [public (public-post snapshot internal viewer-character-id)
                        reaction (get-in snapshot [:reactions
                                                   ["post" (:id internal) viewer-character-id]])
                        feed-at (or (:created_at repost) (:created_at internal))]
                    (cond-> (assoc public :_published_at feed-at)
                      reaction (assoc :viewer_reaction (assoc reaction :restored false))
                      repost (assoc :repost_context {:character (:character repost)
                                                     :reposted_at (:created_at repost)}))))))
            posts (->> (:post-order snapshot)
                       (keep #(get-in snapshot [:posts %]))
                       (filter #(and (= "published" (:status %))
                                     (not (.isAfter (Instant/parse (:created_at %))
                                                    (Instant/parse as-of)))
                                     (= "active" (get-in snapshot [:characters (:character_id %) :status]))
                                     (not (blocked? snapshot viewer-character-id (:character_id %)))))
                       (keep feed-post))]
        (if (= "hot" mode)
          (->> posts
               (map #(assoc % :_hot_score
                            (hot-score snapshot
                                       (get-in snapshot [:posts (:id %)])
                                       viewer-character-id as-of)))
               (filter #(if cursor
                          (neg? (compare [(:_hot_score %) (:id %)]
                                         [(:score cursor) (:id cursor)]))
                          true))
               (sort-by (juxt :_hot_score :id) #(compare %2 %1))
               (take (or limit 20))
               vec)
          (->> posts
               (filter #(if cursor
                          (neg? (compare [(:_published_at %) (:id %)]
                                         [(:published_at cursor) (:id cursor)]))
                          true))
               (sort-by (juxt :_published_at :id) #(compare %2 %1))
               (take (or limit 20))
               vec))))

    :find-post
    (fn [{:keys [id viewer-character-id]}]
      (let [snapshot @state
            internal (get-in snapshot [:posts id])]
        (when (and (= "published" (:status internal))
                   (= "active" (get-in snapshot [:characters (:character_id internal) :status]))
                   (not (blocked? snapshot viewer-character-id (:character_id internal))))
          (let [post (public-post snapshot internal viewer-character-id)]
            (if-let [reaction (get-in snapshot [:reactions ["post" id viewer-character-id]])]
              (assoc post :viewer_reaction (assoc reaction :restored false))
              post)))))

    :find-internal
    (fn [id] (get-in @state [:posts id]))

    :delete!
    (fn [{:keys [post-id]}]
      (locking state
        (when (= "published" (get-in @state [:posts post-id :status]))
          (let [media-ids (get-in @state [:posts post-id :media_ids])
                purges (mapv (fn [media-id]
                               {:id media-id
                                :keys (mapv :key (get-in @state [:media media-id :variants]))})
                             media-ids)]
            (swap! state
                   (fn [snapshot]
                     (reduce (fn [value media-id]
                               (update-in value [:media media-id]
                                          #(assoc % :status "deleted"
                                                  :purge_available_at
                                                  (when (seq (:variants %))
                                                    (str (Instant/now))))))
                             (update-in snapshot [:posts post-id]
                                        #(assoc % :status "deleted" :text nil :media []))
                             media-ids)))
            {:purges purges}))))

    :profile-posts
    (fn [{:keys [character-id viewer-character-id cursor limit]}]
      (let [before? (fn [post]
                      (if-not cursor
                        true
                        (neg? (compare [(:created_at post) (:id post)]
                                       [(:published-at cursor) (:id cursor)]))))]
        (->> (:posts @state)
             vals
             (filter #(and (= character-id (:character_id %))
                           (= "published" (:status %))
                           (not (blocked? @state viewer-character-id (:character_id %)))
                           (before? %)))
             (sort-by (juxt :created_at :id) #(compare %2 %1))
             (take limit)
             (mapv (fn [post]
                     (let [reaction (get-in @state [:reactions ["post" (:id post)
                                                               viewer-character-id]])]
                       (cond-> (assoc (public-post @state post viewer-character-id)
                                      :_published_at (:created_at post))
                         reaction (assoc :viewer_reaction
                                         (assoc reaction :restored false)))))))))

    :profile-counts
    (fn [character-id]
      {:followers (count (filter #(= character-id (second %)) (or (:follows @state) #{})))
       :following (count (filter #(= character-id (first %)) (or (:follows @state) #{})))
       :posts (count (filter #(and (= character-id (:character_id %))
                                   (= "published" (:status %)))
                             (vals (:posts @state))))})}))
