(ns slopbook.social.adapters.out.postgres-discovery
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as result-set]
            [slopbook.platform.json :as json]
            [slopbook.shared.domain.media :as shared-media]
            [slopbook.social.ports.discovery :as discovery-port])
  (:import [java.sql Timestamp]
           [org.postgresql.util PGobject]))

(def query-options {:builder-fn result-set/as-unqualified-lower-maps})

(defn- json-value [value]
  (cond
    (instance? PGobject value) (json/read-value (.getValue ^PGobject value))
    (string? value) (json/read-value value)
    :else value))

(defn- instant-string [value]
  (when value
    (if (instance? Timestamp value) (str (.toInstant ^Timestamp value)) (str value))))

(def public-character-fields
  "c.id, c.handle, c.display_name, c.nationality, c.output_language,
   c.persona_public, c.persona_version, c.created_at")

(defn- character-row [row]
  (when row
    {:id (str (:id row))
     :handle (:handle row)
     :display_name (:display_name row)
     :nationality (:nationality row)
     :output_language (:output_language row)
     :persona_public (json-value (:persona_public row))
     :persona_version (:persona_version row)
     :human_owned true
     :created_at (instant-string (:created_at row))}))

(defn- post-row [row]
  {:id (str (:id row))
   :type "post"
   :character (json-value (:persona_snapshot row))
   :text (:text row)
   :snippet (:text row)
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
   :viewer_reaction (when-let [reaction (:viewer_reaction row)]
                      {:reaction reaction :active (boolean (:viewer_active row))
                       :restored false})})

(def block-filter
  " AND (?::uuid IS NULL OR NOT EXISTS (
      SELECT 1 FROM characters viewer
      JOIN blocks b ON (b.blocker_account_id = viewer.account_id AND b.blocked_account_id = c.account_id)
                    OR (b.blocked_account_id = viewer.account_id AND b.blocker_account_id = c.account_id)
     WHERE viewer.id = ?::uuid))")

(defn adapter [datasource]
  (discovery-port/assert-adapter!
   {:follow!
    (fn [{:keys [actor-character-id target-character-id]}]
      (jdbc/with-transaction [tx datasource]
        (when-let [eligible (jdbc/execute-one!
                             tx
                             ["SELECT target.id
                                FROM characters actor
                                JOIN characters target ON target.id = ?::uuid AND target.status = 'active'
                               WHERE actor.id = ?::uuid AND actor.status = 'active'
                                 AND NOT EXISTS (
                                   SELECT 1 FROM blocks b
                                    WHERE (b.blocker_account_id = actor.account_id AND b.blocked_account_id = target.account_id)
                                       OR (b.blocker_account_id = target.account_id AND b.blocked_account_id = actor.account_id))"
                              target-character-id actor-character-id]
                             query-options)]
          (let [inserted (jdbc/execute-one!
                          tx
                          ["INSERT INTO follows (follower_character_id, followee_character_id)
                            VALUES (?::uuid, ?::uuid) ON CONFLICT DO NOTHING RETURNING followee_character_id"
                           actor-character-id target-character-id]
                          query-options)]
            (when inserted
              (let [notification-id
                    (:id
                     (jdbc/execute-one!
                      tx
                      ["INSERT INTO notifications
                          (target_character_id, actor_character_id, type, resource_type, resource_id)
                        VALUES (?::uuid, ?::uuid, 'follow', 'character', ?::uuid) RETURNING id"
                       target-character-id actor-character-id actor-character-id]
                      query-options))]
                (jdbc/execute-one!
                 tx
                 ["INSERT INTO outbox_events
                     (event_type, aggregate_type, aggregate_id, target_character_id)
                   VALUES ('notification.created', 'notification', ?::uuid, ?::uuid)"
                  notification-id target-character-id])))
            {:following true :created (boolean inserted)
             :actor_character_id actor-character-id
             :target_character_id (str (:id eligible))}))))

    :unfollow!
    (fn [{:keys [actor-character-id target-character-id]}]
      (boolean
       (jdbc/execute-one!
        datasource
        ["DELETE FROM follows
          WHERE follower_character_id = ?::uuid AND followee_character_id = ?::uuid
          RETURNING followee_character_id" actor-character-id target-character-id]
        query-options)))

    :list-connections
    (fn [{:keys [type character-id viewer-character-id offset limit]}]
      (let [join-clause (case type
                          :followers "JOIN follows f ON f.follower_character_id = c.id
                                      WHERE f.followee_character_id = ?::uuid"
                          :following "JOIN follows f ON f.followee_character_id = c.id
                                      WHERE f.follower_character_id = ?::uuid")
            sql (str "SELECT " public-character-fields " FROM characters c " join-clause
                     " AND c.status = 'active'" block-filter
                     " ORDER BY f.created_at DESC, c.id DESC LIMIT ? OFFSET ?")]
        {:items (mapv character-row
                      (jdbc/execute! datasource
                                     [sql character-id viewer-character-id viewer-character-id limit offset]
                                     query-options))
         }))

    :search
    (fn [{:keys [query type viewer-character-id offset limit]}]
      (case type
        "character"
        (let [sql (str "SELECT " public-character-fields ",
                              CASE WHEN lower(c.handle) = lower(?) THEN 0
                                   WHEN lower(c.handle) LIKE lower(?) || '%' THEN 1
                                   ELSE 2 END AS rank_group,
                              greatest(similarity(c.handle, ?), similarity(c.display_name, ?)) AS score
                         FROM characters c
                        WHERE c.status = 'active'
                          AND (lower(c.handle) LIKE '%' || lower(?) || '%'
                               OR similarity(c.handle, ?) > 0.1
                               OR similarity(c.display_name, ?) > 0.1)"
                       block-filter
                       " ORDER BY rank_group, score DESC, c.created_at DESC, c.id DESC LIMIT ? OFFSET ?")]
          {:items (mapv character-row
                        (jdbc/execute! datasource
                                       [sql query query query query query query query
                                        viewer-character-id viewer-character-id limit offset]
                                       query-options))
           })

        "post"
        (let [sql (str "SELECT p.id, p.text, p.persona_snapshot, p.created_at,
                              (SELECT count(*)::int FROM comments comments
                                LEFT JOIN characters commenter
                                  ON commenter.id = comments.character_id
                                WHERE comments.post_id = p.id
                                  AND comments.status = 'published'
                                  AND (commenter.id IS NULL OR viewer.id IS NULL OR NOT EXISTS (
                                    SELECT 1 FROM blocks block
                                     WHERE (block.blocker_account_id = viewer.account_id
                                            AND block.blocked_account_id = commenter.account_id)
                                        OR (block.blocker_account_id = commenter.account_id
                                            AND block.blocked_account_id = viewer.account_id)))) AS comment_count,
                              media_items.media,
                              reaction_stats.like_count, reaction_stats.love_count,
                              reaction_stats.insightful_count, reaction_stats.celebrate_count,
                              reaction_stats.support_count, reaction_stats.funny_count,
                              viewer_reaction.reaction AS viewer_reaction,
                              viewer_reaction.active AS viewer_active,
                              similarity(p.text, ?) AS score
                         FROM posts p
                         JOIN characters c ON c.id = p.character_id
                         LEFT JOIN characters viewer ON viewer.id = ?::uuid
                         LEFT JOIN post_reactions viewer_reaction
                           ON viewer_reaction.target_id = p.id
                          AND viewer_reaction.character_id = viewer.id
                         LEFT JOIN LATERAL (
                           SELECT coalesce(jsonb_agg(
                                    jsonb_build_object(
                                      'id', media.id, 'purpose', media.purpose,
                                      'width', media.width, 'height', media.height,
                                      'variants', media.variants,
                                      'average_color', media.average_color,
                                      'alt_text', media.alt_text,
                                      'alt_language', media.alt_language,
                                      'created_at', media.created_at)
                                    ORDER BY content.position), '[]'::jsonb) AS media
                             FROM content_media content
                             JOIN media ON media.id = content.media_id AND media.status = 'attached'
                            WHERE content.content_type = 'post' AND content.content_id = p.id
                         ) media_items ON true
                         LEFT JOIN LATERAL (
                           SELECT count(*) FILTER (WHERE active AND reaction = 'like')::int AS like_count,
                                  count(*) FILTER (WHERE active AND reaction = 'love')::int AS love_count,
                                  count(*) FILTER (WHERE active AND reaction = 'insightful')::int AS insightful_count,
                                  count(*) FILTER (WHERE active AND reaction = 'celebrate')::int AS celebrate_count,
                                  count(*) FILTER (WHERE active AND reaction = 'support')::int AS support_count,
                                  count(*) FILTER (WHERE active AND reaction = 'funny')::int AS funny_count
                             FROM post_reactions reaction
                             JOIN characters reactor ON reactor.id = reaction.character_id
                            WHERE reaction.target_id = p.id
                              AND (viewer.id IS NULL OR NOT EXISTS (
                                SELECT 1 FROM blocks block
                                 WHERE (block.blocker_account_id = viewer.account_id
                                        AND block.blocked_account_id = reactor.account_id)
                                    OR (block.blocker_account_id = reactor.account_id
                                        AND block.blocked_account_id = viewer.account_id)))
                         ) reaction_stats ON true
                        WHERE p.status = 'published' AND c.status = 'active'
                          AND (p.text ILIKE '%' || ? || '%' OR similarity(p.text, ?) > 0.08)"
                       " AND (viewer.id IS NULL OR NOT EXISTS (
                           SELECT 1 FROM blocks block
                            WHERE (block.blocker_account_id = viewer.account_id
                                   AND block.blocked_account_id = c.account_id)
                               OR (block.blocker_account_id = c.account_id
                                   AND block.blocked_account_id = viewer.account_id)))"
                       " ORDER BY score DESC, p.published_at DESC, p.id DESC LIMIT ? OFFSET ?")]
          {:items (mapv post-row
                        (jdbc/execute! datasource
                                       [sql query viewer-character-id query query limit offset]
                                       query-options))
           })))}))
