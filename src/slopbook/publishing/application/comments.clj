(ns slopbook.publishing.application.comments
  (:require [slopbook.identity.application.anonymous-sessions :as anonymous-sessions]
            [slopbook.identity.application.characters :as characters]
            [slopbook.media.application.assets :as media-assets]
            [slopbook.platform.cursor :as cursor]
            [slopbook.platform.errors :as errors]
            [slopbook.platform.rate-limit :as rate-limit]
            [slopbook.publishing.domain.comment :as comment]
            [slopbook.publishing.domain.content :as content]
            [slopbook.publishing.ports.comments :as comment-port]
            [slopbook.publishing.ports.posts :as post-port]
            [slopbook.transformation.application.rewrites :as rewrites]))

(def create-keys #{:actor_character_id :input_text :parent_comment_id :media_ids})
(def anonymous-create-keys
  #{:anonymous_session_id :input_text :parent_comment_id :anonymous_persona :turnstile_token})
(def delete-keys #{:actor_character_id})
(def page-size 20)

(defn- assert-content-and-parent!
  [{:keys [comments store]} post-id input]
  (when-not (comment/valid-input? (:input_text input))
    (errors/fail! 400 "CONTENT_GRAPHEME_LIMIT" :validation
                  {:field "input_text" :rule "graphemes" :maximum 180}))
  (when-not (post-port/find-internal store post-id)
    (errors/fail! 404 "POST_NOT_FOUND" :not-found))
  (when-let [parent-id (:parent_comment_id input)]
    (let [parent (comment-port/find-internal comments parent-id)]
      (when-not (and parent
                     (= post-id (:post_id parent))
                     (= "published" (:status parent)))
        (errors/fail! 400 "COMMENT_PARENT_INVALID" :validation)))))

(defn create!
  [{:keys [comments store identity transformation new-id now]}
  account-id idempotency-key post-id input]
  (errors/assert-allowed-keys! input create-keys)
  (when (seq (:media_ids input))
    (errors/fail! 400 "COMMENT_MEDIA_DISABLED" :validation))
  (assert-content-and-parent! {:comments comments :store store} post-id input)
  (let [actor (characters/require-owned-internal identity account-id (:actor_character_id input))
        reservation {:account-id account-id
                     :idempotency-key idempotency-key
                     :scope-type "account"
                     :scope-id account-id
                     :fingerprint (content/idempotency-fingerprint
                                   "comments.create" (:id actor) (:persona_version actor)
                                   [post-id (or (:parent_comment_id input) "root")])}
        decision (comment-port/reserve! comments reservation)]
    (case (:state decision)
      :completed (:comment decision)
      :processing (errors/fail! 409 "REQUEST_IN_PROGRESS" :conflict)
      :conflict (errors/fail! 409 "IDEMPOTENCY_CONFLICT" :conflict)
      :claimed
      (let [claimed (or (:reservation decision) reservation)]
        (try
          (let [result (rewrites/rewrite! transformation
                                          {:operation "rewrite.comment"
                                           :grapheme-limit 180
                                           :character actor
                                           :input-text (:input_text input)})
                value (comment/comment-value
                       {:id (new-id)
                        :post-id post-id
                        :parent-id (:parent_comment_id input)
                        :now (now)
                        :character (characters/public-projection actor)
                        :media []
                        :transformed-text (:text result)})]
            (comment-port/publish! comments
                                   {:reservation claimed
                                    :comment value
                                    :ai-run (rewrites/run-metadata "rewrite.comment" result)}))
          (catch Exception exception
            (comment-port/fail-reservation! comments claimed)
            (throw exception)))))))

(defn create-anonymous!
  [{:keys [comments store identity transformation new-id now] :as deps}
   {:keys [remote-ip]} idempotency-key post-id input]
  (errors/assert-allowed-keys! input anonymous-create-keys)
  (assert-content-and-parent! {:comments comments :store store} post-id input)
  (when-not (anonymous-sessions/valid-persona? (:anonymous_persona input))
    (errors/fail! 400 "ANONYMOUS_PERSONA_INVALID" :validation))
  (let [session-id (:anonymous_session_id input)
        _session (anonymous-sessions/require-active! identity session-id)
        reservation {:scope-type "anonymous_session"
                     :scope-id session-id
                     :idempotency-key idempotency-key
                     :fingerprint (content/idempotency-fingerprint
                                   "comments.create" session-id 1
                                   [post-id (or (:parent_comment_id input) "root")])}
        decision (comment-port/reserve! comments reservation)]
    (case (:state decision)
      :completed (:comment decision)
      :processing (errors/fail! 409 "REQUEST_IN_PROGRESS" :conflict)
      :conflict (errors/fail! 409 "IDEMPOTENCY_CONFLICT" :conflict)
      :claimed
      (let [claimed (or (:reservation decision) reservation)]
        (try
          (rate-limit/enforce! (:anonymous-rate identity)
                               :anonymous-comment-ip remote-ip
                               20 (* 10 60 1000) "ANON_RATE_LIMITED")
          (rate-limit/enforce! (:anonymous-rate identity)
                               :anonymous-comment-session session-id
                               10 (* 10 60 1000) "ANON_RATE_LIMITED")
          (anonymous-sessions/verify-human! identity
                                            {:token (:turnstile_token input)
                                             :remote-ip remote-ip
                                             :idempotency-key idempotency-key})
          (let [actor (anonymous-sessions/transformation-character (:anonymous_persona input))
                persona (anonymous-sessions/snapshot (:anonymous_persona input))
                result (rewrites/rewrite! transformation
                                          {:operation "rewrite.comment"
                                           :grapheme-limit 180
                                           :character actor
                                           :input-text (:input_text input)})
                value (comment/comment-value
                       {:id (new-id) :post-id post-id :parent-id (:parent_comment_id input)
                        :now (now) :anonymous-session-id session-id
                        :anonymous-persona persona :transformed-text (:text result) :media []})]
            (let [published (comment-port/publish!
                             comments {:reservation claimed :comment value
                                       :ai-run (rewrites/run-metadata "rewrite.comment" result)})]
              (anonymous-sessions/touch! identity session-id)
              published))
          (catch Exception exception
            (comment-port/fail-reservation! comments claimed)
            (throw exception)))))))

(defn create-request!
  [deps context idempotency-key post-id input]
  (let [authenticated? (contains? input :actor_character_id)
        anonymous? (contains? input :anonymous_session_id)]
    (when (= authenticated? anonymous?)
      (errors/fail! 400 "COMMENT_ACTOR_INVALID" :validation))
    (if authenticated?
      (create! deps (:account-id context) idempotency-key post-id input)
      (create-anonymous! deps context idempotency-key post-id input))))

(defn thread [{:keys [comments store cursor-secret identity]} account-id post-id
              viewer-character-id encoded-cursor]
  (when viewer-character-id
    (when-not account-id (errors/fail! 401 "AUTH_REQUIRED" :unauthorized))
    (characters/require-owned-internal identity account-id viewer-character-id))
  (when-not (post-port/find-post store post-id viewer-character-id)
    (errors/fail! 404 "POST_NOT_FOUND" :not-found))
  (let [scope (str "comments:" post-id)
        boundary (cursor/decode-for cursor-secret encoded-cursor scope)
        rows (:items (comment-port/thread comments {:post-id post-id :cursor boundary
                                                    :viewer-character-id viewer-character-id
                                                    :limit (inc page-size)}))
        page (vec (take page-size rows))
        more? (> (count rows) page-size)
        last-root (:root (peek page))]
    {:items page
     :next_cursor (when (and more? last-root)
                    (cursor/encode cursor-secret
                                   {:scope scope
                                    :created_at (:created_at last-root)
                                    :id (:id last-root)}))}))

(defn delete!
  [{:keys [comments identity media]} account-id comment-id body]
  (errors/assert-allowed-keys! body delete-keys)
  (let [actor-id (:actor_character_id body)
        _actor (characters/require-owned-internal identity account-id actor-id)
        existing (comment-port/find-internal comments comment-id)]
    (when-not (and existing (= "published" (:status existing)))
      (errors/fail! 404 "COMMENT_NOT_FOUND" :not-found))
    (when-not (= actor-id (:character_id existing))
      (errors/fail! 403 "CHARACTER_NOT_OWNED" :forbidden))
    (let [result (or (comment-port/delete! comments {:comment-id comment-id
                                                     :character-id actor-id})
                     (errors/fail! 404 "COMMENT_NOT_FOUND" :not-found))]
      (media-assets/purge-keys! media (or (:purges result) (:keys result)))
      (select-keys result [:tombstone]))))

(defn delete-anonymous!
  [{:keys [comments identity media]} comment-id deletion-token body]
  (errors/assert-allowed-keys! body #{:anonymous_session_id})
  (let [session-id (:anonymous_session_id body)
        _session (anonymous-sessions/authorize-delete! identity session-id deletion-token)
        existing (comment-port/find-internal comments comment-id)]
    (when-not (and existing (= "published" (:status existing)))
      (errors/fail! 404 "COMMENT_NOT_FOUND" :not-found))
    (when-not (= session-id (:anonymous_session_id existing))
      (errors/fail! 403 "ANON_DELETE_FORBIDDEN" :forbidden))
    (let [result (or (comment-port/delete-anonymous!
                      comments {:comment-id comment-id :anonymous-session-id session-id})
                     (errors/fail! 404 "COMMENT_NOT_FOUND" :not-found))]
      (media-assets/purge-keys! media (or (:purges result) (:keys result)))
      (select-keys result [:tombstone]))))
