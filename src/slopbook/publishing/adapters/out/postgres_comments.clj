(ns slopbook.publishing.adapters.out.postgres-comments
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as result-set]
            [slopbook.platform.json :as json]
            [slopbook.publishing.domain.comment :as comment]
            [slopbook.publishing.ports.comments :as comment-port]
            [slopbook.shared.domain.media :as shared-media])
  (:import [java.sql Timestamp]
           [java.util Arrays]
           [org.postgresql.util PGobject]))

(def query-options {:builder-fn result-set/as-unqualified-lower-maps})

(defn- json-value [value]
  (cond
    (nil? value) nil
    (instance? PGobject value) (json/read-value (.getValue ^PGobject value))
    (string? value) (json/read-value value)
    :else value))

(defn- instant-string [value]
  (when value
    (if (instance? Timestamp value)
      (str (.toInstant ^Timestamp value))
      (str value))))

(defn- row->public [row]
  (when row
    {:id (str (:id row))
     :type "comment"
     :post_id (str (:post_id row))
     :parent_comment_id (some-> (:parent_comment_id row) str)
     :thread_root_id (some-> (:thread_root_id row) str)
     :character (json-value (:persona_snapshot row))
     :anonymous (some? (:anonymous_session_id row))
     :anonymous_persona (json-value (:anonymous_persona_snapshot row))
     :text (:text row)
     :status (:status row)
     :created_at (instant-string (:created_at row))
     :reply_to (json-value (:reply_to_snapshot row))
     :reaction_counts {:like (or (:like_count row) 0)
                       :love (or (:love_count row) 0)
                       :insightful (or (:insightful_count row) 0)
                       :celebrate (or (:celebrate_count row) 0)
                       :support (or (:support_count row) 0)
                       :funny (or (:funny_count row) 0)}
     :viewer_reaction (when-let [reaction (:viewer_reaction row)]
                        {:reaction reaction :active (boolean (:viewer_active row))
                         :restored false})
     :media (mapv #(shared-media/public-asset (assoc % :status "ready"))
                  (or (json-value (:media row)) []))}))

(def public-select
  "SELECT c.id, c.post_id, c.parent_comment_id, c.thread_root_id,
          c.persona_snapshot, c.anonymous_session_id, c.anonymous_persona_snapshot,
          c.text, c.status, c.created_at, media_items.media,
          CASE WHEN parent.anonymous_session_id IS NOT NULL
               THEN jsonb_build_object('anonymous', true,
                                       'anonymous_persona', parent.anonymous_persona_snapshot)
               ELSE parent.persona_snapshot END AS reply_to_snapshot,
          count(*) FILTER (WHERE cr.active AND cr.reaction = 'like')::int AS like_count,
          count(*) FILTER (WHERE cr.active AND cr.reaction = 'love')::int AS love_count,
          count(*) FILTER (WHERE cr.active AND cr.reaction = 'insightful')::int AS insightful_count,
          count(*) FILTER (WHERE cr.active AND cr.reaction = 'celebrate')::int AS celebrate_count,
          count(*) FILTER (WHERE cr.active AND cr.reaction = 'support')::int AS support_count,
          count(*) FILTER (WHERE cr.active AND cr.reaction = 'funny')::int AS funny_count
     FROM comments c
     LEFT JOIN comments parent ON parent.id = c.parent_comment_id
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
        WHERE cm.content_type = 'comment' AND cm.content_id = c.id
     ) media_items ON true
     LEFT JOIN comment_reactions cr ON cr.target_id = c.id")

(def public-group
  " GROUP BY c.id, parent.id, media_items.media")

(defn- public-by-id [connectable id]
  (some-> (jdbc/execute-one!
           connectable
           [(str public-select " WHERE c.id = ?::uuid AND c.status = 'published'" public-group) id]
           query-options)
          row->public))

(defn- insert-ai-run! [tx ai-run]
  (:id
   (jdbc/execute-one!
    tx
    ["INSERT INTO ai_runs
        (task_kind, model_id, provider_class, prompt_version, schema_version,
         result_code, latency_ms, prompt_tokens, completion_tokens)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id"
     (:task_kind ai-run) (:model_id ai-run) (:provider_class ai-run)
     (:prompt_version ai-run) (:schema_version ai-run) (:result_code ai-run)
     (:latency_ms ai-run) (:prompt_tokens ai-run) (:completion_tokens ai-run)]
    query-options)))

(defn adapter [datasource]
  (comment-port/assert-adapter!
   {:reserve!
    (fn [{:keys [account-id scope-type scope-id idempotency-key fingerprint] :as reservation}]
      (jdbc/with-transaction [tx datasource]
        (let [scope-type (or scope-type "account")
              scope-id (or scope-id account-id)]
        (if (jdbc/execute-one!
             tx
             ["INSERT INTO idempotency_keys
                 (scope_type, scope_id, route_name, idempotency_key, metadata_fingerprint,
                  status, expires_at)
               VALUES (?, ?::uuid, 'comments.create', ?, ?, 'processing', now() + interval '24 hours')
               ON CONFLICT (scope_type, scope_id, route_name, idempotency_key) DO NOTHING
               RETURNING id"
              scope-type scope-id idempotency-key fingerprint]
             query-options)
          {:state :claimed :reservation (assoc reservation :scope-type scope-type :scope-id scope-id)}
          (let [existing (jdbc/execute-one!
                          tx
                          ["SELECT metadata_fingerprint, status, resource_id
                              FROM idempotency_keys
                             WHERE scope_type = ? AND scope_id = ?::uuid
                               AND route_name = 'comments.create' AND idempotency_key = ?
                             FOR UPDATE"
                           scope-type scope-id idempotency-key]
                          query-options)]
            (cond
              (not (Arrays/equals ^bytes fingerprint ^bytes (:metadata_fingerprint existing)))
              {:state :conflict}

              (= "completed" (:status existing))
              {:state :completed :comment (public-by-id tx (:resource_id existing))}

              (= "failed_retryable" (:status existing))
              (do
                (jdbc/execute-one!
                 tx
                 ["UPDATE idempotency_keys
                     SET status = 'processing', resource_id = NULL, response_code = NULL
                   WHERE scope_type = ? AND scope_id = ?::uuid
                     AND route_name = 'comments.create' AND idempotency_key = ?"
                  scope-type scope-id idempotency-key])
                {:state :claimed :reservation reservation})

              :else {:state :processing}))))))

    :publish!
    (fn [{:keys [reservation comment ai-run]}]
      (jdbc/with-transaction [tx datasource]
        (let [post (jdbc/execute-one!
                    tx
                    ["SELECT id, character_id FROM posts
                       WHERE id = ?::uuid AND status = 'published' FOR UPDATE"
                     (:post_id comment)]
                    query-options)
              parent (when-let [parent-id (:parent_comment_id comment)]
                       (jdbc/execute-one!
                        tx
                        ["SELECT id, post_id, thread_root_id, character_id,
                                 anonymous_session_id, anonymous_persona_snapshot, status
                           FROM comments WHERE id = ?::uuid FOR UPDATE" parent-id]
                        query-options))]
          (when-not post
            (throw (ex-info "Post disappeared during comment transaction" {})))
          (when (and (:parent_comment_id comment)
                     (not (and parent
                               (= "published" (:status parent))
                               (= (str (:post_id comment)) (str (:post_id parent))))))
            (throw (ex-info "Comment parent changed during transaction" {})))
          (let [run-id (insert-ai-run! tx ai-run)
                root-id (if parent (or (:thread_root_id parent) (:id parent)) (:id comment))
                output-language (or (get-in comment [:character :output_language])
                                    (get-in comment [:anonymous_persona :output_language]))]
            (jdbc/execute-one!
             tx
             ["INSERT INTO comments
                 (id, post_id, parent_comment_id, thread_root_id, character_id,
                  anonymous_session_id, anonymous_persona_snapshot, text,
                  output_language, persona_snapshot, reply_to_character_id, ai_run_id)
               VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?::uuid,
                       ?::uuid, ?::jsonb, ?, ?, ?::jsonb, ?::uuid, ?::uuid)"
              (:id comment) (:post_id comment) (:parent_comment_id comment) root-id
              (:character_id comment) (:anonymous_session_id comment)
              (when (:anonymous_persona comment)
                (json/write-value (:anonymous_persona comment)))
              (:text comment) output-language
              (when (:character comment) (json/write-value (:character comment)))
              (:character_id parent) run-id])
            (doseq [[position media-id] (map-indexed vector (:media_ids comment))]
              (when-not
               (jdbc/execute-one!
                tx
                ["UPDATE media SET status = 'attached', expires_at = NULL, updated_at = now()
                    WHERE id = ?::uuid AND owner_character_id = ?::uuid
                      AND purpose = 'comment' AND status = 'ready' AND expires_at > now()
                    RETURNING id"
                 media-id (:character_id comment)]
                query-options)
                (throw (ex-info "Comment media was no longer attachable" {:media-id media-id})))
              (jdbc/execute-one!
               tx
               ["INSERT INTO content_media (media_id, content_type, content_id, position)
                   VALUES (?::uuid, 'comment', ?::uuid, ?)"
                media-id (:id comment) position]))
            (let [target-id (or (:character_id parent) (:character_id post))
                  notification-type (if parent "reply" "comment")]
              (when (and target-id (not= (str target-id) (str (:character_id comment))))
                (let [notification-id
                      (:id
                       (jdbc/execute-one!
                        tx
                        ["INSERT INTO notifications
                            (target_character_id, actor_character_id, actor_anonymous_snapshot,
                             type, resource_type, resource_id)
                          VALUES (?::uuid, ?::uuid, ?::jsonb, ?, 'comment', ?::uuid) RETURNING id"
                         target-id (:character_id comment)
                         (when (:anonymous_persona comment)
                           (json/write-value (:anonymous_persona comment)))
                         notification-type (:id comment)]
                        query-options))]
                  (jdbc/execute-one!
                   tx
                   ["INSERT INTO outbox_events
                       (event_type, aggregate_type, aggregate_id, target_character_id)
                     VALUES ('notification.created', 'notification', ?::uuid, ?::uuid)"
                    notification-id target-id]))))
            (jdbc/execute-one!
             tx
             ["INSERT INTO outbox_events
                 (event_type, aggregate_type, aggregate_id, target_character_id)
               VALUES ('comment.published', 'comment', ?::uuid, ?::uuid)"
              (:id comment) (:character_id comment)])
            (let [{:keys [account-id scope-type scope-id idempotency-key fingerprint]} reservation
                  scope-type (or scope-type "account")
                  scope-id (or scope-id account-id)
                  completed (jdbc/execute-one!
                             tx
                             ["UPDATE idempotency_keys
                                 SET status = 'completed', resource_type = 'comment',
                                     resource_id = ?::uuid, response_code = 201
                               WHERE scope_type = ? AND scope_id = ?::uuid
                                 AND route_name = 'comments.create' AND idempotency_key = ?
                                 AND status = 'processing' AND metadata_fingerprint = ?
                               RETURNING id"
                              (:id comment) scope-type scope-id idempotency-key fingerprint]
                             query-options)]
              (when-not completed
                (throw (ex-info "Comment idempotency reservation was lost" {}))))
            (public-by-id tx (:id comment))))))

    :fail-reservation!
    (fn [{:keys [account-id scope-type scope-id idempotency-key fingerprint]}]
      (boolean
       (jdbc/execute-one!
        datasource
        ["UPDATE idempotency_keys SET status = 'failed_retryable'
          WHERE scope_type = ? AND scope_id = ?::uuid
            AND route_name = 'comments.create' AND idempotency_key = ?
            AND status = 'processing' AND metadata_fingerprint = ?
          RETURNING id"
         (or scope-type "account") (or scope-id account-id) idempotency-key fingerprint]
        query-options)))

    :thread
    (fn [{:keys [post-id cursor viewer-character-id limit]}]
      (let [boundary (when cursor
                       " AND (root.created_at, root.id) > (?::timestamptz, ?::uuid)")
            params (cond-> [viewer-character-id post-id]
                     cursor (conj (:created_at cursor) (:id cursor))
                     true (conj limit))
            rows
            (jdbc/execute!
             datasource
             (into [(str
               "WITH viewer_context AS (
                  SELECT id, account_id FROM characters WHERE id = ?::uuid
                ), roots AS (
                  SELECT id, created_at
                    FROM comments root
                   WHERE post_id = ?::uuid AND parent_comment_id IS NULL
                     AND (status = 'published' OR EXISTS (
                           SELECT 1 FROM comments reply
                            WHERE reply.thread_root_id = root.id AND reply.status = 'published'
                              AND (reply.character_id IS NULL OR NOT EXISTS (
                                SELECT 1
                                  FROM viewer_context viewer
                                  JOIN characters actor ON actor.id = reply.character_id
                                  JOIN blocks block
                                    ON (block.blocker_account_id = viewer.account_id
                                        AND block.blocked_account_id = actor.account_id)
                                    OR (block.blocker_account_id = actor.account_id
                                        AND block.blocked_account_id = viewer.account_id)))))
                     AND (root.character_id IS NULL OR NOT EXISTS (
                       SELECT 1
                         FROM viewer_context viewer
                         JOIN characters actor ON actor.id = root.character_id
                         JOIN blocks block
                           ON (block.blocker_account_id = viewer.account_id
                               AND block.blocked_account_id = actor.account_id)
                           OR (block.blocker_account_id = actor.account_id
                               AND block.blocked_account_id = viewer.account_id)))
                  " boundary "
                   ORDER BY created_at, id
                   LIMIT ?
                ), selected AS (
                  SELECT c.*, r.created_at AS root_created_at, r.id AS selected_root_id,
                         CASE WHEN c.id = r.id THEN 0 ELSE 1 END AS depth
                    FROM roots r
                    JOIN comments c ON c.id = r.id OR c.thread_root_id = r.id
                   WHERE (c.status = 'published' OR (c.id = r.id AND c.status = 'deleted'))
                     AND (c.character_id IS NULL OR NOT EXISTS (
                       SELECT 1
                         FROM viewer_context viewer
                         JOIN characters actor ON actor.id = c.character_id
                         JOIN blocks block
                           ON (block.blocker_account_id = viewer.account_id
                               AND block.blocked_account_id = actor.account_id)
                           OR (block.blocker_account_id = actor.account_id
                               AND block.blocked_account_id = viewer.account_id)))
                )
                SELECT c.id, c.post_id, c.parent_comment_id, c.thread_root_id,
                       c.persona_snapshot, c.anonymous_session_id,
                       c.anonymous_persona_snapshot, c.text, c.status, c.created_at,
                       media_items.media,
                       CASE WHEN parent.character_id IS NOT NULL AND EXISTS (
                                  SELECT 1
                                    FROM viewer_context viewer
                                    JOIN characters actor ON actor.id = parent.character_id
                                    JOIN blocks block
                                      ON (block.blocker_account_id = viewer.account_id
                                          AND block.blocked_account_id = actor.account_id)
                                      OR (block.blocker_account_id = actor.account_id
                                          AND block.blocked_account_id = viewer.account_id))
                            THEN jsonb_build_object('unavailable', true)
                            WHEN parent.anonymous_session_id IS NOT NULL
                            THEN jsonb_build_object('anonymous', true,
                                                    'anonymous_persona', parent.anonymous_persona_snapshot)
                            ELSE parent.persona_snapshot END AS reply_to_snapshot,
                       c.selected_root_id, c.root_created_at, c.depth,
                       viewer_reaction.reaction AS viewer_reaction,
                       viewer_reaction.active AS viewer_active,
                       reaction_stats.like_count, reaction_stats.love_count,
                       reaction_stats.insightful_count, reaction_stats.celebrate_count,
                       reaction_stats.support_count, reaction_stats.funny_count
                  FROM selected c
                  LEFT JOIN comments parent ON parent.id = c.parent_comment_id
                  LEFT JOIN viewer_context viewer_actor ON true
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
                     WHERE cm.content_type = 'comment' AND cm.content_id = c.id
                  ) media_items ON true
                  LEFT JOIN LATERAL (
                    SELECT count(*) FILTER (WHERE cr.active AND cr.reaction = 'like')::int AS like_count,
                           count(*) FILTER (WHERE cr.active AND cr.reaction = 'love')::int AS love_count,
                           count(*) FILTER (WHERE cr.active AND cr.reaction = 'insightful')::int AS insightful_count,
                           count(*) FILTER (WHERE cr.active AND cr.reaction = 'celebrate')::int AS celebrate_count,
                           count(*) FILTER (WHERE cr.active AND cr.reaction = 'support')::int AS support_count,
                           count(*) FILTER (WHERE cr.active AND cr.reaction = 'funny')::int AS funny_count
                      FROM comment_reactions cr
                      JOIN characters reactor ON reactor.id = cr.character_id
                     WHERE cr.target_id = c.id
                       AND NOT EXISTS (
                         SELECT 1 FROM viewer_context viewer
                         JOIN blocks block
                           ON (block.blocker_account_id = viewer.account_id
                               AND block.blocked_account_id = reactor.account_id)
                           OR (block.blocker_account_id = reactor.account_id
                               AND block.blocked_account_id = viewer.account_id))
                  ) reaction_stats ON true
                  LEFT JOIN comment_reactions viewer_reaction
                    ON viewer_reaction.target_id = c.id
                   AND viewer_reaction.character_id = viewer_actor.id
                 ORDER BY c.root_created_at, c.selected_root_id, c.depth, c.created_at, c.id")]
                   params)
             query-options)
            grouped (reduce (fn [{:keys [order by-id] :as acc} row]
                              (let [root-id (str (:selected_root_id row))
                                    value (row->public row)]
                                (if (zero? (:depth row))
                                  {:order (conj order root-id)
                                   :by-id (assoc by-id root-id {:root value :replies []})}
                                  (update-in acc [:by-id root-id :replies] (fnil conj []) value))))
                            {:order [] :by-id {}}
                            rows)]
        {:items (mapv (:by-id grouped) (:order grouped))}))

    :find-internal
    (fn [id]
      (some-> (jdbc/execute-one!
               datasource
               ["SELECT id, post_id, parent_comment_id, thread_root_id, character_id,
                        anonymous_session_id, status
                   FROM comments WHERE id = ?::uuid" id]
               query-options)
              (update :id str)
              (update :post_id str)
              (update :parent_comment_id #(some-> % str))
              (update :thread_root_id #(some-> % str))
              (update :character_id #(some-> % str))
              (update :anonymous_session_id #(some-> % str))))

    :delete!
    (fn [{:keys [comment-id character-id]}]
      (jdbc/with-transaction [tx datasource]
        (when-let [row (jdbc/execute-one!
                        tx
                        ["SELECT id, post_id,
                                 (SELECT count(*)::int FROM comments replies
                                   WHERE replies.thread_root_id = comments.id
                                     AND replies.id <> comments.id
                                     AND replies.status = 'published') AS reply_count
                           FROM comments
                          WHERE id = ?::uuid AND character_id = ?::uuid AND status = 'published'
                          FOR UPDATE"
                         comment-id character-id]
                        query-options)]
          (let [media-rows (jdbc/execute!
                            tx
                            ["SELECT m.id, m.variants
                                FROM content_media cm JOIN media m ON m.id = cm.media_id
                               WHERE cm.content_type = 'comment' AND cm.content_id = ?::uuid
                               FOR UPDATE OF m" comment-id]
                            query-options)
                purges (mapv (fn [media-row]
                               {:id (str (:id media-row))
                                :keys (mapv :key (or (json-value (:variants media-row)) []))})
                             media-rows)]
            (jdbc/execute-one!
             tx ["UPDATE comments SET status = 'deleted', text = NULL, deleted_at = now()
                    WHERE id = ?::uuid" comment-id])
            (jdbc/execute-one!
             tx ["UPDATE media m SET status = 'deleted', deleted_at = now(), updated_at = now(),
                                    purge_available_at = CASE WHEN m.variants IS NULL
                                                              THEN NULL ELSE now() END
                    FROM content_media cm
                   WHERE cm.media_id = m.id AND cm.content_type = 'comment'
                     AND cm.content_id = ?::uuid AND m.status = 'attached'" comment-id])
            (jdbc/execute-one!
             tx ["INSERT INTO outbox_events
                    (event_type, aggregate_type, aggregate_id, target_character_id)
                  VALUES ('comment.deleted', 'comment', ?::uuid, ?::uuid)"
                 comment-id character-id])
            (cond-> {:tombstone (pos? (:reply_count row))}
              (seq purges) (assoc :purges purges))))))

    :delete-anonymous!
    (fn [{:keys [comment-id anonymous-session-id]}]
      (jdbc/with-transaction [tx datasource]
        (when-let [row (jdbc/execute-one!
                        tx
                        ["SELECT id,
                                 (SELECT count(*)::int FROM comments replies
                                   WHERE replies.thread_root_id = comments.id
                                     AND replies.id <> comments.id
                                     AND replies.status = 'published') AS reply_count
                           FROM comments
                          WHERE id = ?::uuid AND anonymous_session_id = ?::uuid
                            AND status = 'published' FOR UPDATE"
                         comment-id anonymous-session-id]
                        query-options)]
          (let [media-rows (jdbc/execute!
                            tx
                            ["SELECT m.id, m.variants
                                FROM content_media cm JOIN media m ON m.id = cm.media_id
                               WHERE cm.content_type = 'comment' AND cm.content_id = ?::uuid
                               FOR UPDATE OF m" comment-id]
                            query-options)
                purges (mapv (fn [media-row]
                               {:id (str (:id media-row))
                                :keys (mapv :key (or (json-value (:variants media-row)) []))})
                             media-rows)]
            (jdbc/execute-one!
             tx ["UPDATE comments SET status = 'deleted', text = NULL, deleted_at = now()
                    WHERE id = ?::uuid" comment-id])
            (jdbc/execute-one!
             tx ["UPDATE media m SET status = 'deleted', deleted_at = now(), updated_at = now(),
                                      purge_available_at = CASE WHEN m.variants IS NULL
                                                                THEN NULL ELSE now() END
                    FROM content_media cm
                   WHERE cm.media_id = m.id AND cm.content_type = 'comment'
                     AND cm.content_id = ?::uuid AND m.status = 'attached'" comment-id])
            (jdbc/execute-one!
             tx ["INSERT INTO outbox_events
                    (event_type, aggregate_type, aggregate_id)
                  VALUES ('comment.deleted', 'comment', ?::uuid)" comment-id])
            (cond-> {:tombstone (pos? (:reply_count row))}
              (seq purges) (assoc :purges purges))))))}))
