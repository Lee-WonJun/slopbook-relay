(ns slopbook.publishing.application.posts
  (:require [slopbook.identity.application.characters :as characters]
            [slopbook.media.application.assets :as media-assets]
            [slopbook.platform.cursor :as cursor]
            [slopbook.platform.errors :as errors]
            [slopbook.publishing.domain.content :as content]
            [slopbook.publishing.ports.posts :as post-port]
            [slopbook.transformation.application.rewrites :as rewrites])
  (:import [java.time Instant]
           [java.util UUID]))

(def post-keys #{:actor_character_id :input_text :media_ids})
(def delete-keys #{:actor_character_id})

(defn create!
  [{:keys [store identity transformation media new-id now] :as deps}
   account-id idempotency-key input]
  (errors/assert-allowed-keys! input post-keys)
  (when-not (content/valid-input? (:input_text input) 280)
    (errors/fail! 400 "CONTENT_GRAPHEME_LIMIT" :validation
                  {:field "input_text" :rule "graphemes"}))
  (let [actor (characters/require-owned-internal identity account-id (:actor_character_id input))
        reservation {:account-id account-id
                     :idempotency-key idempotency-key
                     :fingerprint (content/idempotency-fingerprint
                                   (:id actor) (:persona_version actor) (:media_ids input))}
        decision (post-port/reserve! store reservation)]
    (case (:state decision)
      :completed (:post decision)
      :processing (errors/fail! 409 "REQUEST_IN_PROGRESS" :conflict)
      :conflict (errors/fail! 409 "IDEMPOTENCY_CONFLICT" :conflict)
      :claimed
      (let [claimed (or (:reservation decision) reservation)]
        (try
          (let [attachments (media-assets/ready-assets!
                             media (:id actor) "post" (:media_ids input))
                result (rewrites/rewrite! transformation
                                          {:operation "rewrite.post"
                                           :grapheme-limit 280
                                           :character actor
                                           :input-text (:input_text input)})
                post (content/post-value {:id (new-id)
                                          :now (now)
                                          :character (characters/public-projection actor)
                                          :media attachments
                                          :transformed-text (:text result)})]
            (post-port/publish! store {:reservation claimed
                                       :post post
                                       :ai-run (rewrites/run-metadata "rewrite.post" result)}))
          (catch Exception exception
            (post-port/fail-reservation! store claimed)
            (throw exception)))))))

(def feed-modes #{"latest" "hot" "following"})

(def feed-page-size 20)

(defn- valid-feed-cursor
  [cursor-secret mode viewer-character-id encoded]
  (when encoded
    (let [value (cursor/decode cursor-secret encoded)
          expected-scope (str "feed:" mode ":" (or viewer-character-id "public"))]
      (try
        (when-not (and (= expected-scope (:scope value))
                       (= mode (:mode value))
                       (string? (:as_of value))
                       (string? (:id value)))
          (throw (IllegalArgumentException. "invalid feed cursor shape")))
        (Instant/parse (:as_of value))
        (UUID/fromString (:id value))
        (if (= "hot" mode)
          (when-not (and (number? (:score value))
                         (Double/isFinite (double (:score value))))
            (throw (IllegalArgumentException. "invalid hot cursor score")))
          (Instant/parse (:published_at value)))
        value
        (catch Exception _
          (errors/fail! 400 "CURSOR_INVALID" :invalid-request))))))

(defn- next-feed-cursor [cursor-secret scope mode as-of last-row]
  (when last-row
    (cursor/encode
     cursor-secret
     (cond-> {:scope scope :mode mode :as_of as-of :id (:id last-row)}
       (= "hot" mode) (assoc :score (:_hot_score last-row))
       (not= "hot" mode) (assoc :published_at (:_published_at last-row))))))

(defn feed
  ([deps mode account-id actor-character-id]
   (feed deps mode account-id actor-character-id nil))
  ([{:keys [store identity cursor-secret now]} mode account-id actor-character-id encoded-cursor]
  (when-not (contains? feed-modes mode)
    (errors/fail! 400 "FEED_MODE_INVALID" :validation))
  (when (and (= "following" mode) (nil? actor-character-id))
    (errors/fail! 401 "AUTH_REQUIRED" :unauthorized))
  (when actor-character-id
    (when-not account-id
      (errors/fail! 401 "AUTH_REQUIRED" :unauthorized))
    (characters/require-owned-internal identity account-id actor-character-id))
  (let [scope (str "feed:" mode ":" (or actor-character-id "public"))
        boundary (valid-feed-cursor cursor-secret mode actor-character-id encoded-cursor)
        as-of (or (:as_of boundary) (now))
        rows (post-port/feed store {:mode mode :viewer-character-id actor-character-id
                                    :cursor boundary :as-of as-of
                                    :limit (inc feed-page-size)})
        page (vec (take feed-page-size rows))
        more? (> (count rows) feed-page-size)
        last-row (peek page)]
    {:items (mapv #(dissoc % :_published_at :_hot_score) page)
     :next_cursor (when more?
                    (next-feed-cursor cursor-secret scope mode as-of last-row))})))

(defn find-post
  ([deps id] (find-post deps nil nil id))
  ([{:keys [store identity]} account-id viewer-character-id id]
   (when viewer-character-id
     (when-not account-id (errors/fail! 401 "AUTH_REQUIRED" :unauthorized))
     (characters/require-owned-internal identity account-id viewer-character-id))
   (or (post-port/find-post store id viewer-character-id)
       (errors/fail! 404 "POST_NOT_FOUND" :not-found))))

(defn delete! [{:keys [store identity media]} account-id post-id body]
  (errors/assert-allowed-keys! body delete-keys)
  (let [actor-id (:actor_character_id body)
        _actor (characters/require-owned-internal identity account-id actor-id)
        post (post-port/find-internal store post-id)]
    (when-not (and post (= "published" (:status post)))
      (errors/fail! 404 "POST_NOT_FOUND" :not-found))
    (when-not (= actor-id (:character_id post))
      (errors/fail! 403 "CHARACTER_NOT_OWNED" :forbidden))
    (let [result (or (post-port/delete! store {:post-id post-id :character-id actor-id})
                     (errors/fail! 404 "POST_NOT_FOUND" :not-found))]
      (media-assets/purge-keys! media (or (:purges result) (:keys result))))
    true))
