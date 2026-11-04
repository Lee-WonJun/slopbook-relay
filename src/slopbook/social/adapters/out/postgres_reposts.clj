(ns slopbook.social.adapters.out.postgres-reposts
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as result-set]
            [slopbook.platform.json :as json]
            [slopbook.social.domain.repost :as repost]
            [slopbook.social.ports.reposts :as repost-port]))

(def query-options {:builder-fn result-set/as-unqualified-lower-maps})

(def target-query
  "SELECT p.id, p.character_id, author.account_id AS owner_account_id
     FROM posts p
     JOIN characters actor ON actor.id = ?::uuid
     JOIN characters author ON author.id = p.character_id
    WHERE p.id = ?::uuid AND p.status = 'published' AND author.status = 'active'
      AND NOT EXISTS (
        SELECT 1 FROM blocks b
         WHERE (b.blocker_account_id = actor.account_id AND b.blocked_account_id = author.account_id)
            OR (b.blocker_account_id = author.account_id AND b.blocked_account_id = actor.account_id))")

(defn- visible-count [connectable post-id viewer-character-id]
  (:amount
   (jdbc/execute-one!
    connectable
    ["SELECT count(*)::int AS amount
        FROM post_reposts r
        JOIN characters reposter ON reposter.id = r.character_id
        JOIN characters viewer ON viewer.id = ?::uuid
       WHERE r.target_id = ?::uuid AND r.active AND reposter.status = 'active'
         AND NOT EXISTS (
           SELECT 1 FROM blocks b
            WHERE (b.blocker_account_id = viewer.account_id AND b.blocked_account_id = reposter.account_id)
               OR (b.blocker_account_id = reposter.account_id AND b.blocked_account_id = viewer.account_id))"
     viewer-character-id post-id]
    query-options)))

(defn adapter [datasource]
  (repost-port/assert-adapter!
   {:target-public
    (fn [{:keys [id actor-character-id]}]
      (when-let [row (jdbc/execute-one! datasource [target-query actor-character-id id]
                                        query-options)]
        {:id (str (:id row))
         :character-id (str (:character_id row))
         :owner-account-id (:owner_account_id row)}))

    :toggle!
    (fn [{:keys [target actor actor-character-id persona-snapshot]}]
      (let [post-id (:id target)]
        (jdbc/with-transaction [tx datasource]
          (jdbc/execute-one!
           tx
           ["SELECT pg_advisory_xact_lock(hashtextextended(?, 0))"
            (str "repost:" post-id ":" actor-character-id)])
          (let [existing (jdbc/execute-one!
                          tx
                          ["SELECT active, created_at FROM post_reposts
                             WHERE target_id = ?::uuid AND character_id = ?::uuid FOR UPDATE"
                           post-id actor-character-id]
                          query-options)
                transition (repost/transition existing)]
            (if existing
              (jdbc/execute-one!
               tx
               ["UPDATE post_reposts
                   SET active = ?, persona_snapshot = ?::jsonb, updated_at = now()
                 WHERE target_id = ?::uuid AND character_id = ?::uuid"
                (:active transition) (json/write-value persona-snapshot)
                post-id actor-character-id])
              (do
                (jdbc/execute-one!
                 tx
                 ["INSERT INTO post_reposts
                     (target_id, character_id, persona_snapshot, active)
                   VALUES (?::uuid, ?::uuid, ?::jsonb, true)"
                  post-id actor-character-id (json/write-value persona-snapshot)])
                (when (not= actor-character-id (:character-id target))
                  (let [notification-id
                        (:id
                         (jdbc/execute-one!
                          tx
                          ["INSERT INTO notifications
                              (target_character_id, actor_character_id, type, resource_type, resource_id)
                            VALUES (?::uuid, ?::uuid, 'repost', 'post', ?::uuid)
                            RETURNING id"
                           (:character-id target) actor-character-id post-id]
                          query-options))]
                    (jdbc/execute-one!
                     tx
                     ["INSERT INTO outbox_events
                         (event_type, aggregate_type, aggregate_id, target_character_id)
                       VALUES ('notification.created', 'notification', ?::uuid, ?::uuid)"
                      notification-id (:character-id target)])))))
            {:active (:active transition)
             :restored (:restored transition)
             :repost_count (visible-count tx post-id actor-character-id)}))))}))
