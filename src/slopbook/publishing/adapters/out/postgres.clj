(ns slopbook.publishing.adapters.out.postgres
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as result-set]
            [slopbook.platform.json :as json]
            [slopbook.publishing.ports.posts :as post-port]
            [slopbook.shared.domain.media :as shared-media])
  (:import [java.sql Timestamp]
           [java.util Arrays]
           [org.postgresql.util PGobject]))

(def query-options {:builder-fn result-set/as-unqualified-lower-maps})

(defn- json-value [value]
  (cond
    (instance? PGobject value) (json/read-value (.getValue ^PGobject value))
    (string? value) (json/read-value value)
    :else value))

(defn- instant-string [value]
  (if (instance? Timestamp value)
    (str (.toInstant ^Timestamp value))
    (str value)))

(defn- row->public [row]
  (when row
    {:id (str (:id row))
     :type "post"
     :character (json-value (:persona_snapshot row))
     :text (:text row)
     :media (mapv #(shared-media/public-asset (assoc % :status "ready"))
                  (or (json-value (:media row)) []))
     :created_at (instant-string (:created_at row))
     :comment_count (or (:comment_count row) 0)
     :reaction_counts {:like (or (:like_count row) 0)
                       :love (or (:love_count row) 0)
                       :insightful (or (:insightful_count row) 0)
                       :celebrate (or (:celebrate_count row) 0)
                       :support (or (:support_count row) 0)
                       :funny (or (:funny_count row) 0)}
     :viewer_reaction (when-let [viewer-reaction (:viewer_reaction row)]
                        {:reaction viewer-reaction
                         :active (boolean (:viewer_active row))
                         :restored false})
     :repost_count (or (:repost_count row) 0)
     :viewer_repost (when (some? (:viewer_repost_active row))
                      {:active (boolean (:viewer_repost_active row))
                       :restored false})
     :repost_context (when-let [snapshot (:repost_persona_snapshot row)]
                       {:character (json-value snapshot)
                        :reposted_at (instant-string (:reposted_at row))})}))

(def public-select
  "SELECT p.id, p.character_id, author.account_id AS author_account_id,
          viewer.account_id AS viewer_account_id,
          p.text, p.persona_snapshot, p.created_at, p.published_at,
          media_items.media,
          comment_stats.comment_count,
          reaction_stats.like_count,
          reaction_stats.love_count,
          reaction_stats.insightful_count,
          reaction_stats.celebrate_count,
          reaction_stats.support_count,
          reaction_stats.funny_count,
          reaction_stats.reaction_total,
          reaction_stats.external_reactor_accounts,
          comment_stats.external_commenter_accounts,
          repost_stats.repost_count,
          repost_stats.external_reposter_accounts,
          viewer_reaction.reaction AS viewer_reaction,
          viewer_reaction.active AS viewer_active,
          viewer_repost.active AS viewer_repost_active
     FROM posts p
     JOIN characters author ON author.id = p.character_id
     LEFT JOIN characters viewer ON viewer.id = ?::uuid
     LEFT JOIN post_reactions viewer_reaction
       ON viewer_reaction.target_id = p.id AND viewer_reaction.character_id = viewer.id
     LEFT JOIN post_reposts viewer_repost
       ON viewer_repost.target_id = p.id AND viewer_repost.character_id = viewer.id
     LEFT JOIN LATERAL (
       SELECT coalesce(jsonb_agg(
                jsonb_build_object(
                  'id', m.id, 'purpose', m.purpose, 'width', m.width, 'height', m.height,
                  'variants', m.variants, 'average_color', m.average_color,
                  'alt_text', m.alt_text, 'alt_language', m.alt_language,
                  'created_at', m.created_at)
                ORDER BY cm.position), '[]'::jsonb) AS media
         FROM content_media cm
         JOIN media m ON m.id = cm.media_id AND m.status = 'attached'
        WHERE cm.content_type = 'post' AND cm.content_id = p.id
     ) media_items ON true
     LEFT JOIN LATERAL (
       SELECT count(*) FILTER (WHERE c.status = 'published')::int AS comment_count,
              count(DISTINCT commenter.account_id)
                FILTER (WHERE c.status = 'published' AND commenter.account_id IS NOT NULL
                              AND commenter.account_id <> author.account_id)::int
                AS external_commenter_accounts
         FROM comments c
         LEFT JOIN characters commenter ON commenter.id = c.character_id
        WHERE c.post_id = p.id
          AND (commenter.id IS NULL OR viewer.id IS NULL OR NOT EXISTS (
            SELECT 1 FROM blocks b
             WHERE (b.blocker_account_id = viewer.account_id
                    AND b.blocked_account_id = commenter.account_id)
                OR (b.blocker_account_id = commenter.account_id
                    AND b.blocked_account_id = viewer.account_id)))
     ) comment_stats ON true
     LEFT JOIN LATERAL (
       SELECT count(*) FILTER (WHERE r.active AND r.reaction = 'like')::int AS like_count,
              count(*) FILTER (WHERE r.active AND r.reaction = 'love')::int AS love_count,
              count(*) FILTER (WHERE r.active AND r.reaction = 'insightful')::int AS insightful_count,
              count(*) FILTER (WHERE r.active AND r.reaction = 'celebrate')::int AS celebrate_count,
              count(*) FILTER (WHERE r.active AND r.reaction = 'support')::int AS support_count,
              count(*) FILTER (WHERE r.active AND r.reaction = 'funny')::int AS funny_count,
              count(*) FILTER (WHERE r.active)::int AS reaction_total,
              count(DISTINCT reactor.account_id)
                FILTER (WHERE r.active AND reactor.account_id <> author.account_id)::int
                AS external_reactor_accounts
         FROM post_reactions r
         JOIN characters reactor ON reactor.id = r.character_id
        WHERE r.target_id = p.id
          AND (viewer.id IS NULL OR NOT EXISTS (
            SELECT 1 FROM blocks b
             WHERE (b.blocker_account_id = viewer.account_id
                    AND b.blocked_account_id = reactor.account_id)
                OR (b.blocker_account_id = reactor.account_id
                    AND b.blocked_account_id = viewer.account_id)))
     ) reaction_stats ON true
     LEFT JOIN LATERAL (
       SELECT count(*) FILTER (WHERE r.active)::int AS repost_count,
              count(DISTINCT reposter.account_id)
                FILTER (WHERE r.active AND reposter.account_id <> author.account_id)::int
                AS external_reposter_accounts
         FROM post_reposts r
         JOIN characters reposter ON reposter.id = r.character_id AND reposter.status = 'active'
        WHERE r.target_id = p.id
          AND (viewer.id IS NULL OR NOT EXISTS (
            SELECT 1 FROM blocks b
             WHERE (b.blocker_account_id = viewer.account_id
                    AND b.blocked_account_id = reposter.account_id)
                OR (b.blocker_account_id = reposter.account_id
                    AND b.blocked_account_id = viewer.account_id)))
     ) repost_stats ON true
    WHERE p.status = 'published' AND author.status = 'active'
      AND (viewer.id IS NULL OR NOT EXISTS (
        SELECT 1 FROM blocks b
         WHERE (b.blocker_account_id = viewer.account_id AND b.blocked_account_id = author.account_id)
            OR (b.blocker_account_id = author.account_id AND b.blocked_account_id = viewer.account_id)))")

(defn- public-by-id
  ([connectable post-id] (public-by-id connectable post-id nil))
  ([connectable post-id viewer-character-id]
   (some-> (jdbc/execute-one!
            connectable
            [(str public-select " AND p.id = ?::uuid") viewer-character-id post-id]
            query-options)
           row->public)))

(defn adapter [datasource]
  (post-port/assert-adapter!
   {:reserve!
    (fn [{:keys [account-id idempotency-key fingerprint route] :as reservation}]
      (let [route (or route "posts.create")]
        (jdbc/with-transaction [tx datasource]
          (if (jdbc/execute-one!
               tx
               ["INSERT INTO idempotency_keys
                 (scope_type, scope_id, route_name, idempotency_key, metadata_fingerprint,
                  status, expires_at)
               VALUES ('account', ?::uuid, ?, ?, ?, 'processing', now() + interval '24 hours')
               ON CONFLICT (scope_type, scope_id, route_name, idempotency_key) DO NOTHING
               RETURNING id"
                account-id route idempotency-key fingerprint]
               query-options)
            {:state :claimed :reservation (assoc reservation :route route)}
            (let [existing (jdbc/execute-one!
                            tx
                            ["SELECT metadata_fingerprint, status, resource_id
                             FROM idempotency_keys
                            WHERE scope_type = 'account' AND scope_id = ?::uuid
                              AND route_name = ? AND idempotency_key = ?
                            FOR UPDATE"
                             account-id route idempotency-key]
                            query-options)]
              (cond
                (not (Arrays/equals ^bytes fingerprint ^bytes (:metadata_fingerprint existing)))
                {:state :conflict}

                (= "completed" (:status existing))
                {:state :completed :post (public-by-id tx (:resource_id existing))}

                (= "failed_retryable" (:status existing))
                (do
                  (jdbc/execute-one!
                   tx
                   ["UPDATE idempotency_keys SET status = 'processing', resource_id = NULL,
                                                     response_code = NULL
                    WHERE scope_type = 'account' AND scope_id = ?::uuid
                      AND route_name = ? AND idempotency_key = ?"
                    account-id route idempotency-key])
                  {:state :claimed :reservation (assoc reservation :route route)})

                :else {:state :processing}))))))

    :publish!
    (fn [{:keys [reservation post ai-run]}]
      (jdbc/with-transaction [tx datasource]
        (let [run (jdbc/execute-one!
                   tx
                   ["INSERT INTO ai_runs
                       (task_kind, model_id, provider_class, prompt_version, schema_version,
                        result_code, latency_ms, prompt_tokens, completion_tokens)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id"
                    (:task_kind ai-run) (:model_id ai-run) (:provider_class ai-run)
                    (:prompt_version ai-run) (:schema_version ai-run) (:result_code ai-run)
                    (:latency_ms ai-run) (:prompt_tokens ai-run) (:completion_tokens ai-run)]
                   query-options)]
          (jdbc/execute-one!
           tx
           ["INSERT INTO posts
               (id, character_id, text, output_language, persona_snapshot, ai_run_id)
             VALUES (?::uuid, ?::uuid, ?, ?, ?::jsonb, ?::uuid)"
            (:id post) (:character_id post) (:text post)
            (get-in post [:character :output_language])
            (json/write-value (:character post)) (:id run)])
          (doseq [[position media-id] (map-indexed vector (:media_ids post))]
            (when-not
             (jdbc/execute-one!
              tx
              ["UPDATE media SET status = 'attached', expires_at = NULL, updated_at = now()
                  WHERE id = ?::uuid AND owner_character_id = ?::uuid
                    AND purpose = 'post' AND status = 'ready' AND expires_at > now()
                  RETURNING id"
               media-id (:character_id post)]
              query-options)
              (throw (ex-info "Post media was no longer attachable" {:media-id media-id})))
            (jdbc/execute-one!
             tx
             ["INSERT INTO content_media (media_id, content_type, content_id, position)
                 VALUES (?::uuid, 'post', ?::uuid, ?)"
              media-id (:id post) position]))
          (jdbc/execute-one!
           tx
           ["INSERT INTO outbox_events
               (event_type, aggregate_type, aggregate_id, target_character_id)
             VALUES ('post.published', 'post', ?::uuid, ?::uuid)"
            (:id post) (:character_id post)])
          (let [{:keys [account-id idempotency-key fingerprint route]} reservation
                route (or route "posts.create")
                completed (jdbc/execute-one!
                           tx
                           ["UPDATE idempotency_keys
                               SET status = 'completed', resource_type = 'post',
                                   resource_id = ?::uuid, response_code = 201
                             WHERE scope_type = 'account' AND scope_id = ?::uuid
                               AND route_name = ? AND idempotency_key = ?
                               AND status = 'processing' AND metadata_fingerprint = ?
                             RETURNING id"
                            (:id post) account-id route idempotency-key fingerprint]
                           query-options)]
            (when-not completed
              (throw (ex-info "Idempotency reservation was lost" {}))))
          (public-by-id tx (:id post)))))

    :fail-reservation!
    (fn [{:keys [account-id idempotency-key fingerprint route]}]
      (boolean
       (jdbc/execute-one!
        datasource
        ["UPDATE idempotency_keys SET status = 'failed_retryable'
          WHERE scope_type = 'account' AND scope_id = ?::uuid
            AND route_name = ? AND idempotency_key = ?
            AND status = 'processing' AND metadata_fingerprint = ?
          RETURNING id"
         account-id (or route "posts.create") idempotency-key fingerprint]
        query-options)))

    :feed
    (fn [{:keys [mode viewer-character-id cursor as-of limit]}]
      (let [following-clause
            (when (= "following" mode)
              " AND EXISTS (
                  SELECT 1 FROM follows f
                   WHERE f.follower_character_id = viewer.id
                     AND f.followee_character_id = p.character_id)")
            feed-select
            (str "SELECT feed.*, latest_repost.persona_snapshot AS repost_persona_snapshot,
                         latest_repost.created_at AS reposted_at,
                         greatest(feed.published_at,
                                  coalesce(latest_repost.created_at, feed.published_at)) AS feed_at
                    FROM (" public-select following-clause ") feed
                    LEFT JOIN LATERAL (
                      SELECT r.persona_snapshot, r.created_at, r.character_id
                        FROM post_reposts r
                        JOIN characters reposter
                          ON reposter.id = r.character_id AND reposter.status = 'active'
                       WHERE r.target_id = feed.id AND r.active
                         AND r.created_at <= ?::timestamptz
                         AND (feed.viewer_account_id IS NULL OR NOT EXISTS (
                           SELECT 1 FROM blocks b
                            WHERE (b.blocker_account_id = feed.viewer_account_id
                                   AND b.blocked_account_id = reposter.account_id)
                               OR (b.blocker_account_id = reposter.account_id
                                   AND b.blocked_account_id = feed.viewer_account_id)))
                       ORDER BY r.created_at DESC, r.character_id DESC
                       LIMIT 1
                    ) latest_repost ON true
                   WHERE feed.published_at <= ?::timestamptz")]
        (if (= "hot" mode)
          (let [ranked (str "SELECT feed.*,
                                    ((coalesce(external_reactor_accounts, 0)
                                       + (coalesce(external_commenter_accounts, 0) * 2)
                                       + (coalesce(external_reposter_accounts, 0) * 3)) + 1)::double precision
                                      / power((greatest(extract(epoch FROM
                                                   (?::timestamptz - published_at)), 0) / 3600) + 2, 1.5)
                                      AS hot_score
                               FROM (" feed-select ") feed")
                boundary (when cursor
                           " WHERE (hot_score, id) < (?::double precision, ?::uuid)")
                sql (str "SELECT * FROM (" ranked ") ranked" boundary
                         " ORDER BY hot_score DESC, id DESC LIMIT ?")
                params (cond-> [as-of viewer-character-id as-of as-of]
                         cursor (conj (:score cursor) (:id cursor))
                         true (conj limit))]
            (mapv (fn [row]
                    (assoc (row->public row)
                           :_published_at (instant-string (:feed_at row))
                           :_hot_score (:hot_score row)))
                  (jdbc/execute! datasource (into [sql] params) query-options)))
          (let [boundary (when cursor
                           " WHERE (feed_at, id) < (?::timestamptz, ?::uuid)")
                sql (str "SELECT * FROM (" feed-select ") feed" boundary
                         " ORDER BY feed_at DESC, id DESC LIMIT ?")
                params (cond-> [viewer-character-id as-of as-of]
                         cursor (conj (:published_at cursor) (:id cursor))
                         true (conj limit))]
            (mapv (fn [row]
                    (assoc (row->public row)
                           :_published_at (instant-string (:feed_at row))))
                  (jdbc/execute! datasource (into [sql] params) query-options))))))

    :find-post
    (fn [{:keys [id viewer-character-id]}]
      (public-by-id datasource id viewer-character-id))

    :find-internal
    (fn [post-id]
      (some-> (jdbc/execute-one!
               datasource
               ["SELECT id, character_id, status FROM posts WHERE id = ?::uuid" post-id]
               query-options)
              (update :id str)
              (update :character_id str)))

    :delete!
    (fn [{:keys [post-id character-id]}]
      (jdbc/with-transaction [tx datasource]
        (when (jdbc/execute-one!
               tx
               ["UPDATE posts SET status = 'deleted', text = NULL, deleted_at = now()
                 WHERE id = ?::uuid AND character_id = ?::uuid AND status = 'published'
                 RETURNING id" post-id character-id]
               query-options)
          (let [media-rows (jdbc/execute!
                            tx
                            ["SELECT m.id, m.variants
                                FROM content_media cm
                                JOIN media m ON m.id = cm.media_id
                               WHERE cm.content_type = 'post' AND cm.content_id = ?::uuid
                               FOR UPDATE OF m" post-id]
                            query-options)
                purges (mapv (fn [row]
                               {:id (str (:id row))
                                :keys (mapv :key (or (json-value (:variants row)) []))})
                             media-rows)]
            (jdbc/execute-one!
             tx
             ["UPDATE media m SET status = 'deleted', deleted_at = now(), updated_at = now(),
                                    purge_available_at = CASE WHEN m.variants IS NULL
                                                              THEN NULL ELSE now() END
                 FROM content_media cm
                WHERE cm.media_id = m.id AND cm.content_type = 'post'
                  AND cm.content_id = ?::uuid AND m.status = 'attached'" post-id])
          (jdbc/execute-one!
           tx
           ["INSERT INTO outbox_events
               (event_type, aggregate_type, aggregate_id, target_character_id)
             VALUES ('post.deleted', 'post', ?::uuid, ?::uuid)"
            post-id character-id])
            {:purges purges}))))

    :profile-posts
    (fn [{:keys [character-id viewer-character-id cursor limit]}]
      (let [base (str public-select " AND p.character_id = ?::uuid")
            [sql params] (if cursor
                           [(str base " AND (p.published_at, p.id) < (?::timestamptz, ?::uuid)
                                      ORDER BY p.published_at DESC, p.id DESC LIMIT ?")
                            [viewer-character-id character-id (:published-at cursor) (:id cursor) limit]]
                           [(str base " ORDER BY p.published_at DESC, p.id DESC LIMIT ?")
                            [viewer-character-id character-id limit]])]
        (mapv (fn [row]
                (assoc (row->public row) :_published_at (instant-string (:published_at row))))
              (jdbc/execute! datasource (into [sql] params) query-options))))

    :profile-counts
    (fn [character-id]
      (let [row (jdbc/execute-one!
                 datasource
                 ["SELECT
                       (SELECT count(*)::int FROM follows WHERE followee_character_id = ?::uuid) AS followers,
                       (SELECT count(*)::int FROM follows WHERE follower_character_id = ?::uuid) AS following,
                       (SELECT count(*)::int FROM posts
                         WHERE character_id = ?::uuid AND status = 'published') AS posts"
                  character-id character-id character-id]
                 query-options)]
        (select-keys row [:followers :following :posts])))}))
