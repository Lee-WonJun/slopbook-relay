(ns slopbook.social.adapters.out.postgres-notifications
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as result-set]
            [slopbook.platform.json :as json]
            [slopbook.social.ports.notifications :as notification-port])
  (:import [java.sql Timestamp]
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
    (if (instance? Timestamp value) (str (.toInstant ^Timestamp value)) (str value))))

(defn- actor-row [row]
  (when (and (:actor_id row) (= "active" (:actor_status row)))
    {:id (str (:actor_id row))
     :handle (:actor_handle row)
     :display_name (:actor_display_name row)
     :nationality (:actor_nationality row)
     :output_language (:actor_output_language row)
     :persona_public (json-value (:actor_persona_public row))
     :persona_version (:actor_persona_version row)
     :human_owned true
     :created_at (instant-string (:actor_created_at row))}))

(defn- notification-row [row]
  (let [available? (true? (:resource_available row))
        actor (actor-row row)
        unavailable? (or (not available?)
                         (and (:actor_id row) (nil? actor)))]
    {:id (str (:id row))
     :type (:type row)
     :resource_type (:resource_type row)
     :resource_id (str (:resource_id row))
     :post_id (some-> (:post_id row) str)
     :reaction (:reaction row)
     :actor_anonymous (json-value (:actor_anonymous_snapshot row))
     :unavailable unavailable?
     :read_at (instant-string (:read_at row))
     :created_at (instant-string (:created_at row))
     :_created_at (instant-string (:created_at row))
     :_actor (when-not unavailable? actor)}))

(def notification-select
  "SELECT n.id, n.type, n.resource_type, n.resource_id, n.reaction,
          n.actor_anonymous_snapshot, n.read_at, n.created_at,
          CASE WHEN n.resource_type = 'comment'
               THEN (SELECT c.post_id FROM comments c WHERE c.id = n.resource_id)
               ELSE NULL END AS post_id,
          actor.id AS actor_id, actor.handle AS actor_handle,
          actor.display_name AS actor_display_name,
          actor.nationality AS actor_nationality,
          actor.output_language AS actor_output_language,
          actor.persona_public AS actor_persona_public,
          actor.persona_version AS actor_persona_version,
          actor.status AS actor_status, actor.created_at AS actor_created_at,
          CASE n.resource_type
            WHEN 'post' THEN EXISTS (
              SELECT 1 FROM posts p WHERE p.id = n.resource_id AND p.status = 'published')
            WHEN 'comment' THEN EXISTS (
              SELECT 1 FROM comments c WHERE c.id = n.resource_id AND c.status = 'published')
            WHEN 'message' THEN EXISTS (
              SELECT 1 FROM messages m
              JOIN conversation_members member ON member.conversation_id = m.conversation_id
             WHERE m.id = n.resource_id AND m.status = 'sent'
               AND member.character_id = n.target_character_id AND member.hidden_at IS NULL)
            WHEN 'character' THEN EXISTS (
              SELECT 1 FROM characters c WHERE c.id = n.resource_id AND c.status = 'active')
            ELSE false
          END AS resource_available
     FROM notifications n
     JOIN characters target ON target.id = n.target_character_id
     LEFT JOIN characters actor ON actor.id = n.actor_character_id
    WHERE n.target_character_id = ?::uuid
      AND (actor.id IS NULL OR NOT EXISTS (
        SELECT 1 FROM blocks b
         WHERE (b.blocker_account_id = target.account_id AND b.blocked_account_id = actor.account_id)
            OR (b.blocker_account_id = actor.account_id AND b.blocked_account_id = target.account_id)))")

(defn adapter [datasource]
  (notification-port/assert-adapter!
   {:list-notifications
    (fn [{:keys [character-id cursor limit]}]
      (let [[boundary params]
            (if cursor
              [" AND (n.created_at, n.id) < (?::timestamptz, ?::uuid)"
               [character-id (:created_at cursor) (:id cursor) limit]]
              ["" [character-id limit]])
            sql (str notification-select boundary
                     " ORDER BY n.created_at DESC, n.id DESC LIMIT ?")]
        (mapv notification-row
              (jdbc/execute! datasource (into [sql] params) query-options))))

    :mark-read!
    (fn [{:keys [character-id notification-id]}]
      (jdbc/with-transaction [tx datasource]
        (when-let [row (jdbc/execute-one!
                        tx
                        ["UPDATE notifications
                            SET read_at = COALESCE(read_at, now())
                          WHERE id = ?::uuid AND target_character_id = ?::uuid
                          RETURNING id, read_at, (xmax = 0) AS inserted"
                         notification-id character-id]
                        query-options)]
          (jdbc/execute-one!
           tx
           ["INSERT INTO outbox_events
               (event_type, aggregate_type, aggregate_id, target_character_id)
             SELECT 'notification.read', 'notification', ?::uuid, ?::uuid
              WHERE NOT EXISTS (
                SELECT 1 FROM outbox_events
                 WHERE event_type = 'notification.read' AND aggregate_id = ?::uuid)"
            notification-id character-id notification-id])
          {:id (str (:id row))
           :read_at (instant-string (:read_at row))
           :unread_count (:amount (jdbc/execute-one!
                                   tx
                                   ["SELECT count(*)::int AS amount FROM notifications
                                      WHERE target_character_id = ?::uuid AND read_at IS NULL"
                                    character-id]
                                   query-options))})))

    :mark-all-read!
    (fn [{:keys [character-id before]}]
      (jdbc/with-transaction [tx datasource]
        (let [updated (:amount
                       (jdbc/execute-one!
                        tx
                        ["WITH changed AS (
                            UPDATE notifications SET read_at = now()
                             WHERE target_character_id = ?::uuid AND read_at IS NULL
                               AND created_at <= ?::timestamptz RETURNING id)
                          SELECT count(*)::int AS amount FROM changed"
                         character-id before]
                        query-options))]
          (when (pos? updated)
            (jdbc/execute-one!
             tx
             ["INSERT INTO outbox_events
                 (event_type, aggregate_type, aggregate_id, target_character_id)
               VALUES ('notification.read', 'character', ?::uuid, ?::uuid)"
              character-id character-id]))
          {:updated updated
           :unread_count (:amount (jdbc/execute-one!
                                   tx
                                   ["SELECT count(*)::int AS amount FROM notifications
                                      WHERE target_character_id = ?::uuid AND read_at IS NULL"
                                    character-id]
                                   query-options))})))

    :unread-count
    (fn [character-id]
      (:amount
       (jdbc/execute-one!
        datasource
        ["SELECT count(*)::int AS amount FROM notifications
           WHERE target_character_id = ?::uuid AND read_at IS NULL" character-id]
        query-options)))

    :save-ticket!
    (fn [{:keys [character-id digest expires-at]}]
      (jdbc/with-transaction [tx datasource]
        (jdbc/execute-one!
         tx
         ["DELETE FROM websocket_tickets WHERE expires_at <= now() OR consumed_at IS NOT NULL"])
        (jdbc/execute-one!
         tx
         ["INSERT INTO websocket_tickets (token_digest, character_id, expires_at)
           VALUES (?, ?::uuid, ?::timestamptz)" digest character-id expires-at])
        true))

    :consume-ticket!
    (fn [digest]
      (some-> (jdbc/execute-one!
               datasource
               ["UPDATE websocket_tickets SET consumed_at = now()
                  WHERE token_digest = ? AND consumed_at IS NULL AND expires_at > now()
                  RETURNING character_id" digest]
               query-options)
              :character_id str))}))
