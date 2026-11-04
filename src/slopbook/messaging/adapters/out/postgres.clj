(ns slopbook.messaging.adapters.out.postgres
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as result-set]
            [slopbook.messaging.domain.conversation :as conversation]
            [slopbook.messaging.ports.messages :as message-port]
            [slopbook.platform.json :as json])
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
    (if (instance? Timestamp value) (str (.toInstant ^Timestamp value)) (str value))))

(def other-fields
  "other.id AS other_id, other.handle AS other_handle,
   other.display_name AS other_display_name, other.nationality AS other_nationality,
   other.output_language AS other_output_language,
   other.persona_public AS other_persona_public,
   other.persona_version AS other_persona_version,
   other.status AS other_status, other.created_at AS other_created_at")

(defn- other-character [row]
  (when (:other_id row)
    {:id (str (:other_id row))
     :handle (:other_handle row)
     :display_name (:other_display_name row)
     :nationality (:other_nationality row)
     :output_language (:other_output_language row)
     :persona_public (json-value (:other_persona_public row))
     :persona_version (:other_persona_version row)
     :human_owned true
     :status (:other_status row)
     :created_at (instant-string (:other_created_at row))}))

(defn- context-row [row]
  (when row
    {:id (str (:id row))
     :status (:status row)
     :created_at (instant-string (:created_at row))
     :member_ids [(str (:character_low_id row)) (str (:character_high_id row))]
     :other_last_read_at (instant-string (:other_last_read_at row))
     :_other (other-character row)}))

(defn- list-row [row]
  {:id (str (:id row))
   :status (:status row)
   :created_at (instant-string (:created_at row))
   :last_message_at (instant-string (:sort_at row))
   :preview (:preview row)
   :unread_count (or (:unread_count row) 0)
   :_sort_at (instant-string (:sort_at row))
   :_other (other-character row)})

(defn- message-row [row]
  {:id (str (:id row))
   :conversation_id (str (:conversation_id row))
   :sender_character_id (str (:sender_character_id row))
   :sender (json-value (:persona_snapshot row))
   :text (:text row)
   :status (:status row)
   :created_at (instant-string (:created_at row))
   :_created_at (instant-string (:created_at row))
   :read (boolean (:read row))})

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

(defn- public-message [connectable message-id]
  (some-> (jdbc/execute-one!
           connectable
           ["SELECT id, conversation_id, sender_character_id, persona_snapshot,
                   text, status, created_at, false AS read
              FROM messages WHERE id = ?::uuid" message-id]
           query-options)
          message-row
          (dissoc :_created_at)))

(def context-select
  (str "SELECT conv.id, conv.character_low_id, conv.character_high_id,
               conv.status, conv.created_at, other_member.last_read_at AS other_last_read_at, "
       other-fields
       " FROM conversations conv
          JOIN characters actor ON actor.id = ?::uuid
            AND actor.id IN (conv.character_low_id, conv.character_high_id)
          JOIN characters other ON other.id = CASE WHEN conv.character_low_id = actor.id
                                                   THEN conv.character_high_id ELSE conv.character_low_id END
          JOIN conversation_members actor_member
            ON actor_member.conversation_id = conv.id AND actor_member.character_id = actor.id
          JOIN conversation_members other_member
            ON other_member.conversation_id = conv.id AND other_member.character_id = other.id
         WHERE conv.id = ?::uuid AND conv.status = 'active'
           AND actor.status = 'active' AND other.status = 'active'
           AND NOT EXISTS (
             SELECT 1 FROM blocks b
              WHERE (b.blocker_account_id = actor.account_id AND b.blocked_account_id = other.account_id)
                 OR (b.blocker_account_id = other.account_id AND b.blocked_account_id = actor.account_id))"))

(defn adapter [datasource]
  (message-port/assert-adapter!
   {:create-conversation!
    (fn [{:keys [actor-character-id recipient-character-id]}]
      (let [[low high] (conversation/canonical-pair actor-character-id recipient-character-id)]
        (jdbc/with-transaction [tx datasource]
          (when-let [eligible
                     (jdbc/execute-one!
                      tx
                      [(str "SELECT " other-fields "
                               FROM characters actor
                               JOIN characters other ON other.id = ?::uuid
                              WHERE actor.id = ?::uuid
                                AND actor.status = 'active' AND other.status = 'active'
                                AND NOT EXISTS (
                                  SELECT 1 FROM blocks b
                                   WHERE (b.blocker_account_id = actor.account_id AND b.blocked_account_id = other.account_id)
                                      OR (b.blocker_account_id = other.account_id AND b.blocked_account_id = actor.account_id))")
                       recipient-character-id actor-character-id]
                      query-options)]
            (let [row (jdbc/execute-one!
                       tx
                       ["INSERT INTO conversations (character_low_id, character_high_id)
                         VALUES (?::uuid, ?::uuid)
                         ON CONFLICT (character_low_id, character_high_id)
                         DO UPDATE SET status = CASE WHEN conversations.status = 'closed'
                                                     THEN 'active' ELSE conversations.status END
                         RETURNING id, character_low_id, character_high_id, status, created_at,
                                   (xmax = 0) AS created"
                        low high]
                       query-options)]
              (when (= "active" (:status row))
                (jdbc/execute!
                 tx
                 ["INSERT INTO conversation_members (conversation_id, character_id)
                   VALUES (?::uuid, ?::uuid), (?::uuid, ?::uuid)
                   ON CONFLICT (conversation_id, character_id)
                   DO UPDATE SET hidden_at = NULL"
                  (:id row) low (:id row) high])
                (assoc (context-row (merge row eligible)) :created (boolean (:created row)))))))))

    :list-conversations
    (fn [{:keys [actor-character-id cursor limit]}]
      (let [boundary (if cursor
                       " AND (COALESCE(last_message.created_at, conv.created_at), conv.id)
                               < (?::timestamptz, ?::uuid)"
                       "")
            params (cond-> [actor-character-id]
                     cursor (conj (:sort_at cursor) (:id cursor))
                     true (conj limit))
            sql (str "SELECT conv.id, conv.status, conv.created_at, " other-fields ",
                             COALESCE(last_message.created_at, conv.created_at) AS sort_at,
                             last_message.text AS preview,
                             (SELECT count(*)::int FROM messages unread
                               LEFT JOIN message_visibility unread_visibility
                                 ON unread_visibility.message_id = unread.id
                                AND unread_visibility.character_id = actor.id
                              WHERE unread.conversation_id = conv.id AND unread.status = 'sent'
                                AND unread.sender_character_id <> actor.id
                                AND unread_visibility.message_id IS NULL
                                AND unread.created_at > COALESCE(actor_member.last_read_at, 'epoch')) AS unread_count
                        FROM conversations conv
                        JOIN characters actor ON actor.id = ?::uuid
                          AND actor.id IN (conv.character_low_id, conv.character_high_id)
                        JOIN characters other ON other.id = CASE WHEN conv.character_low_id = actor.id
                                                                 THEN conv.character_high_id ELSE conv.character_low_id END
                        JOIN conversation_members actor_member
                          ON actor_member.conversation_id = conv.id AND actor_member.character_id = actor.id
                        LEFT JOIN LATERAL (
                          SELECT message.text, message.created_at
                            FROM messages message
                            LEFT JOIN message_visibility visibility
                              ON visibility.message_id = message.id AND visibility.character_id = actor.id
                           WHERE message.conversation_id = conv.id AND message.status = 'sent'
                             AND visibility.message_id IS NULL
                           ORDER BY message.created_at DESC, message.id DESC LIMIT 1
                        ) last_message ON true
                       WHERE conv.status = 'active' AND actor.status = 'active' AND other.status = 'active'
                         AND actor_member.hidden_at IS NULL
                         AND NOT EXISTS (
                           SELECT 1 FROM blocks b
                            WHERE (b.blocker_account_id = actor.account_id AND b.blocked_account_id = other.account_id)
                               OR (b.blocker_account_id = other.account_id AND b.blocked_account_id = actor.account_id))"
                     boundary
                     " ORDER BY sort_at DESC, conv.id DESC LIMIT ?")]
        (mapv list-row (jdbc/execute! datasource (into [sql] params) query-options))))

    :conversation-context
    (fn [{:keys [conversation-id actor-character-id]}]
      (some-> (jdbc/execute-one! datasource [context-select actor-character-id conversation-id]
                                 query-options)
              context-row))

    :messages
    (fn [{:keys [conversation-id actor-character-id cursor limit]}]
      (let [boundary (if cursor
                       " AND (message.created_at, message.id) < (?::timestamptz, ?::uuid)"
                       "")
            params (cond-> [actor-character-id conversation-id]
                     cursor (conj (:created_at cursor) (:id cursor))
                     true (conj limit))
            sql (str "SELECT message.id, message.conversation_id, message.sender_character_id,
                             message.persona_snapshot, message.text, message.status, message.created_at,
                             CASE WHEN message.sender_character_id = actor.id
                                  THEN other_member.last_read_at >= message.created_at
                                  ELSE actor_member.last_read_at >= message.created_at END AS read
                        FROM conversations conv
                        JOIN characters actor ON actor.id = ?::uuid
                          AND actor.id IN (conv.character_low_id, conv.character_high_id)
                        JOIN characters other ON other.id = CASE WHEN conv.character_low_id = actor.id
                                                                 THEN conv.character_high_id ELSE conv.character_low_id END
                        JOIN conversation_members actor_member
                          ON actor_member.conversation_id = conv.id AND actor_member.character_id = actor.id
                        JOIN conversation_members other_member
                          ON other_member.conversation_id = conv.id AND other_member.character_id = other.id
                        JOIN messages message ON message.conversation_id = conv.id
                        LEFT JOIN message_visibility visibility
                          ON visibility.message_id = message.id AND visibility.character_id = actor.id
                       WHERE conv.id = ?::uuid AND conv.status = 'active'
                         AND actor.status = 'active' AND other.status = 'active'
                         AND message.status = 'sent' AND visibility.message_id IS NULL
                         AND NOT EXISTS (
                           SELECT 1 FROM blocks b
                            WHERE (b.blocker_account_id = actor.account_id AND b.blocked_account_id = other.account_id)
                               OR (b.blocker_account_id = other.account_id AND b.blocked_account_id = actor.account_id))"
                     boundary
                     " ORDER BY message.created_at DESC, message.id DESC LIMIT ?")]
        (mapv message-row (jdbc/execute! datasource (into [sql] params) query-options))))

    :reserve!
    (fn [{:keys [account-id idempotency-key fingerprint] :as reservation}]
      (jdbc/with-transaction [tx datasource]
        (if (jdbc/execute-one!
             tx
             ["INSERT INTO idempotency_keys
                 (scope_type, scope_id, route_name, idempotency_key, metadata_fingerprint,
                  status, expires_at)
               VALUES ('account', ?::uuid, 'messages.create', ?, ?, 'processing', now() + interval '24 hours')
               ON CONFLICT (scope_type, scope_id, route_name, idempotency_key) DO NOTHING
               RETURNING id" account-id idempotency-key fingerprint]
             query-options)
          {:state :claimed :reservation reservation}
          (let [existing (jdbc/execute-one!
                          tx
                          ["SELECT metadata_fingerprint, status, resource_id FROM idempotency_keys
                             WHERE scope_type = 'account' AND scope_id = ?::uuid
                               AND route_name = 'messages.create' AND idempotency_key = ? FOR UPDATE"
                           account-id idempotency-key]
                          query-options)]
            (cond
              (not (Arrays/equals ^bytes fingerprint ^bytes (:metadata_fingerprint existing)))
              {:state :conflict}
              (= "completed" (:status existing))
              {:state :completed :message (public-message tx (:resource_id existing))}
              (= "failed_retryable" (:status existing))
              (do (jdbc/execute-one!
                   tx
                   ["UPDATE idempotency_keys SET status = 'processing', resource_id = NULL,
                                                response_code = NULL
                      WHERE scope_type = 'account' AND scope_id = ?::uuid
                        AND route_name = 'messages.create' AND idempotency_key = ?"
                    account-id idempotency-key])
                  {:state :claimed :reservation reservation})
              :else {:state :processing})))))

    :publish!
    (fn [{:keys [reservation message ai-run]}]
      (jdbc/with-transaction [tx datasource]
        (let [actor-id (:sender_character_id message)
              context (jdbc/execute-one!
                       tx
                       [(str context-select " FOR UPDATE OF conv") actor-id (:conversation_id message)]
                       query-options)]
          (when-not context
            (throw (ex-info "Conversation unavailable during message transaction" {})))
          (let [receiver-id (if (= (str (:character_low_id context)) actor-id)
                              (str (:character_high_id context))
                              (str (:character_low_id context)))
                run-id (insert-ai-run! tx ai-run)]
            (jdbc/execute-one!
             tx
             ["INSERT INTO messages
                 (id, conversation_id, sender_character_id, text, output_language,
                  persona_snapshot, ai_run_id)
               VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, ?::jsonb, ?::uuid)"
              (:id message) (:conversation_id message) actor-id (:text message)
              (get-in message [:sender :output_language])
              (json/write-value (:sender message)) run-id])
            (jdbc/execute-one! tx ["UPDATE conversations SET last_message_at = now() WHERE id = ?::uuid"
                                   (:conversation_id message)])
            (let [notification-id
                  (:id (jdbc/execute-one!
                        tx
                        ["INSERT INTO notifications
                            (target_character_id, actor_character_id, type, resource_type, resource_id)
                          VALUES (?::uuid, ?::uuid, 'dm', 'message', ?::uuid) RETURNING id"
                         receiver-id actor-id (:id message)]
                        query-options))]
              (jdbc/execute-one!
               tx
               ["INSERT INTO outbox_events
                   (event_type, aggregate_type, aggregate_id, target_character_id)
                 VALUES ('notification.created', 'notification', ?::uuid, ?::uuid),
                        ('dm.message.created', 'message', ?::uuid, ?::uuid)"
                notification-id receiver-id (:id message) receiver-id]))
            (let [{:keys [account-id idempotency-key fingerprint]} reservation
                  completed (jdbc/execute-one!
                             tx
                             ["UPDATE idempotency_keys
                                 SET status = 'completed', resource_type = 'message',
                                     resource_id = ?::uuid, response_code = 201
                               WHERE scope_type = 'account' AND scope_id = ?::uuid
                                 AND route_name = 'messages.create' AND idempotency_key = ?
                                 AND status = 'processing' AND metadata_fingerprint = ? RETURNING id"
                              (:id message) account-id idempotency-key fingerprint]
                             query-options)]
              (when-not completed
                (throw (ex-info "Message idempotency reservation was lost" {}))))
            (public-message tx (:id message))))))

    :fail-reservation!
    (fn [{:keys [account-id idempotency-key fingerprint]}]
      (boolean
       (jdbc/execute-one!
        datasource
        ["UPDATE idempotency_keys SET status = 'failed_retryable'
          WHERE scope_type = 'account' AND scope_id = ?::uuid
            AND route_name = 'messages.create' AND idempotency_key = ?
            AND status = 'processing' AND metadata_fingerprint = ? RETURNING id"
         account-id idempotency-key fingerprint]
        query-options)))

    :mark-read!
    (fn [{:keys [conversation-id actor-character-id message-id]}]
      (jdbc/with-transaction [tx datasource]
        (when-let [target (jdbc/execute-one!
                           tx
                           [(str "SELECT message.id, message.created_at,
                                         CASE WHEN conv.character_low_id = actor.id
                                              THEN conv.character_high_id ELSE conv.character_low_id END AS other_id
                                    FROM conversations conv
                                    JOIN characters actor ON actor.id = ?::uuid
                                      AND actor.id IN (conv.character_low_id, conv.character_high_id)
                                    JOIN characters other ON other.id = CASE WHEN conv.character_low_id = actor.id
                                                                             THEN conv.character_high_id ELSE conv.character_low_id END
                                    JOIN messages message ON message.id = ?::uuid AND message.conversation_id = conv.id
                                   WHERE conv.id = ?::uuid AND conv.status = 'active'
                                     AND actor.status = 'active' AND other.status = 'active'
                                     AND message.status = 'sent'
                                     AND NOT EXISTS (
                                       SELECT 1 FROM blocks b
                                        WHERE (b.blocker_account_id = actor.account_id AND b.blocked_account_id = other.account_id)
                                           OR (b.blocker_account_id = other.account_id AND b.blocked_account_id = actor.account_id))")
                            actor-character-id message-id conversation-id]
                           query-options)]
          (let [updated (jdbc/execute-one!
                         tx
                         ["UPDATE conversation_members member
                             SET last_read_message_id = ?::uuid, last_read_at = now()
                           WHERE member.conversation_id = ?::uuid AND member.character_id = ?::uuid
                             AND (member.last_read_message_id IS NULL OR
                                  (SELECT (current.created_at, current.id) < (?::timestamptz, ?::uuid)
                                     FROM messages current WHERE current.id = member.last_read_message_id))
                           RETURNING last_read_message_id, last_read_at"
                          message-id conversation-id actor-character-id
                          (instant-string (:created_at target)) message-id]
                         query-options)]
            (when updated
              (jdbc/execute-one!
               tx
               ["INSERT INTO outbox_events
                   (event_type, aggregate_type, aggregate_id, target_character_id)
                 VALUES ('dm.message.read', 'conversation', ?::uuid, ?::uuid)"
                conversation-id (:other_id target)]))
            (let [watermark (or updated
                                (jdbc/execute-one!
                                 tx
                                 ["SELECT last_read_message_id, last_read_at
                                     FROM conversation_members
                                    WHERE conversation_id = ?::uuid AND character_id = ?::uuid"
                                  conversation-id actor-character-id]
                                 query-options))]
              {:conversation_id conversation-id
               :last_read_message_id (some-> (:last_read_message_id watermark) str)
               :last_read_at (instant-string (:last_read_at watermark))})))))

    :hide-message!
    (fn [{:keys [message-id actor-character-id]}]
      (boolean
       (jdbc/execute-one!
        datasource
        ["INSERT INTO message_visibility (message_id, character_id)
          SELECT message.id, ?::uuid FROM messages message
          JOIN conversation_members member
            ON member.conversation_id = message.conversation_id AND member.character_id = ?::uuid
         WHERE message.id = ?::uuid AND message.sender_character_id = ?::uuid
         ON CONFLICT (message_id, character_id) DO UPDATE SET hidden_at = message_visibility.hidden_at
         RETURNING message_id"
         actor-character-id actor-character-id message-id actor-character-id]
        query-options)))}))
