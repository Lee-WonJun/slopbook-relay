(ns slopbook.publishing.adapters.out.memory-comments
  (:require [slopbook.publishing.domain.comment :as comment]
            [slopbook.publishing.ports.comments :as comment-port])
  (:import [java.util Arrays]))

(defn- purge-items [snapshot media-ids]
  (->> media-ids
       (keep (fn [media-id]
               (let [keys (mapv :key (get-in snapshot [:media media-id :variants]))]
                 (when (seq keys) {:id media-id :keys keys}))))
       vec))

(defn- mark-media-deleted [snapshot media-ids deleted-at]
  (reduce (fn [value media-id]
            (let [variants (get-in value [:media media-id :variants])]
              (update-in value [:media media-id]
                         #(cond-> (assoc % :status "deleted" :deleted_at deleted-at)
                            (seq variants) (assoc :purge_available_at deleted-at)))))
          snapshot media-ids))

(defn- public-by-id [state id]
  (some-> (get-in @state [:comments id]) comment/public-comment))

(defn- blocked? [snapshot viewer-character-id author-character-id]
  (when (and viewer-character-id author-character-id)
    (let [viewer-account (get-in snapshot [:characters viewer-character-id :account_id])
          author-account (get-in snapshot [:characters author-character-id :account_id])
          blocks (or (:blocks snapshot) #{})]
      (and viewer-account author-account
           (or (contains? blocks [viewer-account author-account])
               (contains? blocks [author-account viewer-account]))))))

(def reaction-types ["like" "love" "insightful" "celebrate" "support" "funny"])

(defn- visible-reaction-counts [snapshot comment-id viewer-character-id]
  (let [totals (->> (:reactions snapshot)
                    (keep (fn [[[type target-id reactor-id] row]]
                            (when (and (= "comment" type) (= comment-id target-id)
                                       (:active row)
                                       (not (blocked? snapshot viewer-character-id reactor-id)))
                              (:reaction row))))
                    frequencies)]
    (into {} (map (fn [reaction] [(keyword reaction) (get totals reaction 0)])
                  reaction-types))))

(defn- add-notification
  [snapshot new-id now target-id actor anonymous-persona comment-type comment-id post-id]
  (if (or (nil? target-id) (= target-id (:id actor)))
    snapshot
    (let [notification-id (new-id)
          event-id (new-id)
          created-at (now)]
      (-> snapshot
          (assoc-in [:notifications notification-id]
                    {:id notification-id :target_character_id target-id
                     :type comment-type :resource_type "comment" :resource_id comment-id
                     :post_id post-id
                     :_actor actor :actor_anonymous anonymous-persona
                     :unavailable false :read_at nil :created_at created-at})
          (assoc-in [:outbox event-id]
                    {:id event-id :event_type "notification.created"
                     :aggregate_id notification-id :target_character_id target-id
                     :created_at created-at :published_at nil :attempt_count 0})))))

(defn adapter [state new-id now]
  (comment-port/assert-adapter!
   {:reserve!
    (fn [{:keys [account-id scope-id idempotency-key fingerprint] :as reservation}]
      (locking state
        (let [scope [(or scope-id account-id) "comments.create" idempotency-key]
              existing (get-in @state [:idempotency scope])]
          (cond
            (and existing (not (Arrays/equals ^bytes fingerprint ^bytes (:fingerprint existing))))
            {:state :conflict}

            (= :completed (:status existing))
            {:state :completed :comment (public-by-id state (:resource-id existing))}

            (= :processing (:status existing)) {:state :processing}

            :else
            (do
              (swap! state assoc-in [:idempotency scope]
                     {:status :processing :fingerprint fingerprint})
              {:state :claimed :reservation (assoc reservation :scope scope)})))))

    :publish!
    (fn [{:keys [reservation comment ai-run]}]
      (locking state
        (let [parent (get-in @state [:comments (:parent_comment_id comment)])
              root-id (if parent (or (:thread_root_id parent) (:id parent)) (:id comment))
              value (assoc comment
                           :thread_root_id root-id
                           :reply_to (when parent
                                       (or (:character parent)
                                           {:anonymous true
                                            :anonymous_persona (:anonymous_persona parent)})))]
          (swap! state (fn [snapshot]
                         (let [post (get-in snapshot [:posts (:post_id value)])
                               target (or (get-in parent [:character :id])
                                          (get-in post [:character :id]))
                               attached (reduce (fn [state media-id]
                                                  (assoc-in state [:media media-id :status] "attached"))
                                                snapshot (:media_ids value))]
                           (-> attached
                               (assoc-in [:comments (:id value)] value)
                               (update :comment-order (fnil conj []) (:id value))
                               (update :ai-runs (fnil conj []) ai-run)
                               (update-in [:posts (:post_id value) :comment_count] (fnil inc 0))
                               (assoc-in [:idempotency (:scope reservation)]
                                         {:status :completed
                                          :fingerprint (:fingerprint reservation)
                                          :resource-id (:id value)})
                               (add-notification new-id now target (:character value)
                                                 (:anonymous_persona value)
                                                 (if parent "reply" "comment") (:id value)
                                                 (:post_id value))))))
          (comment/public-comment value))))

    :fail-reservation!
    (fn [{:keys [scope fingerprint]}]
      (swap! state assoc-in [:idempotency scope]
             {:status :failed-retryable :fingerprint fingerprint})
      true)

    :thread
    (fn [{:keys [post-id cursor viewer-character-id limit]}]
      (let [snapshot @state
            all (->> (:comment-order snapshot)
                     (keep #(get-in snapshot [:comments %]))
                     (filter #(and (= post-id (:post_id %))
                                   (not (blocked? snapshot viewer-character-id
                                                  (:character_id %))))))
            roots (->> all
                       (filter #(nil? (:parent_comment_id %)))
                       (filter #(or (= "published" (:status %))
                                    (some (fn [reply]
                                            (and (= (:id %) (:thread_root_id reply))
                                                 (= "published" (:status reply))))
                                          all)))
                       (sort-by (juxt :created_at :id))
                       (filter #(if-not cursor
                                  true
                                  (pos? (compare [(:created_at %) (:id %)]
                                                 [(:created_at cursor) (:id cursor)]))))
                       (take limit))
            viewer (fn [value]
                     (let [parent (get-in snapshot [:comments (:parent_comment_id value)])
                           public (cond-> (assoc (comment/public-comment value)
                                                :reaction_counts
                                                (visible-reaction-counts snapshot (:id value)
                                                                         viewer-character-id))
                                    (blocked? snapshot viewer-character-id (:character_id parent))
                                    (assoc :reply_to {:unavailable true}))
                           reaction (get-in snapshot [:reactions ["comment" (:id value)
                                                                 viewer-character-id]])]
                       (cond-> public
                         reaction (assoc :viewer_reaction
                                         (assoc reaction :restored false)))))
            items (mapv (fn [root]
                          {:root (-> root
                                     (assoc :tombstone (= "deleted" (:status root)))
                                     viewer)
                           :replies (->> all
                                         (filter #(and (= (:id root) (:thread_root_id %))
                                                       (not= (:id root) (:id %))
                                                       (= "published" (:status %))))
                                         (mapv viewer))})
                        roots)]
        {:items items}))

    :find-internal (fn [id] (get-in @state [:comments id]))

    :delete!
    (fn [{:keys [comment-id character-id]}]
      (locking state
        (let [current (get-in @state [:comments comment-id])
              reply-count (count (filter #(and (= comment-id (:thread_root_id %))
                                               (not= comment-id (:id %))
                                               (= "published" (:status %)))
                                         (vals (:comments @state))))]
          (when (and current
                     (= "published" (:status current))
                     (= character-id (:character_id current)))
            (let [deleted-at (now)
                  next (comment/deletion-transition current reply-count deleted-at)
                  media-ids (:media_ids current)
                  purges (purge-items @state media-ids)]
              (swap! state (fn [snapshot]
                             (mark-media-deleted
                              (-> snapshot
                                  (assoc-in [:comments comment-id] (assoc next :media []))
                                  (update-in [:posts (:post_id current) :comment_count]
                                             #(max 0 (dec (or % 0)))))
                              media-ids deleted-at)))
              (cond-> {:tombstone (pos? reply-count)}
                (seq purges) (assoc :purges purges)))))))

    :delete-anonymous!
    (fn [{:keys [comment-id anonymous-session-id]}]
      (locking state
        (let [current (get-in @state [:comments comment-id])
              reply-count (count (filter #(and (= comment-id (:thread_root_id %))
                                               (not= comment-id (:id %))
                                               (= "published" (:status %)))
                                         (vals (:comments @state))))]
          (when (and current (= "published" (:status current))
                     (= anonymous-session-id (:anonymous_session_id current)))
            (let [deleted-at (now)
                  next (comment/deletion-transition current reply-count deleted-at)
                  media-ids (:media_ids current)
                  purges (purge-items @state media-ids)]
              (swap! state (fn [snapshot]
                             (mark-media-deleted
                              (-> snapshot
                                  (assoc-in [:comments comment-id] (assoc next :media []))
                                  (update-in [:posts (:post_id current) :comment_count]
                                             #(max 0 (dec (or % 0)))))
                              media-ids deleted-at)))
              (cond-> {:tombstone (pos? reply-count)}
                (seq purges) (assoc :purges purges)))))))}))
