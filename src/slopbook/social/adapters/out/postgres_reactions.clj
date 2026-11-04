(ns slopbook.social.adapters.out.postgres-reactions
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as result-set]
            [slopbook.platform.json :as json]
            [slopbook.social.domain.reaction :as reaction]
            [slopbook.social.ports.reactions :as reaction-port])
  (:import [org.postgresql.util PGobject]))

(def query-options {:builder-fn result-set/as-unqualified-lower-maps})

(defn- json-value [value]
  (cond
    (instance? PGobject value) (json/read-value (.getValue ^PGobject value))
    (string? value) (json/read-value value)
    :else value))

(defn- table-for [type]
  (case type "post" "post_reactions" "comment" "comment_reactions"))

(defn- target-query [type]
  (case type
    "post" "SELECT p.id, p.text, p.character_id, p.persona_snapshot
                FROM posts p
                JOIN characters actor ON actor.id = ?::uuid
                JOIN characters target ON target.id = p.character_id
               WHERE p.id = ?::uuid AND p.status = 'published'
                 AND NOT EXISTS (
                   SELECT 1 FROM blocks b
                    WHERE (b.blocker_account_id = actor.account_id AND b.blocked_account_id = target.account_id)
                       OR (b.blocker_account_id = target.account_id AND b.blocked_account_id = actor.account_id))"
    "comment" "SELECT c.id, c.text, c.character_id, c.persona_snapshot,
                       c.anonymous_persona_snapshot
                   FROM comments c
                   JOIN characters actor ON actor.id = ?::uuid
                   LEFT JOIN characters target ON target.id = c.character_id
                  WHERE c.id = ?::uuid AND c.status = 'published'
                    AND (target.id IS NULL OR NOT EXISTS (
                      SELECT 1 FROM blocks b
                       WHERE (b.blocker_account_id = actor.account_id AND b.blocked_account_id = target.account_id)
                          OR (b.blocker_account_id = target.account_id AND b.blocked_account_id = actor.account_id)))"))

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

(defn- aggregate [connectable table target-id viewer-character-id]
  (let [row (jdbc/execute-one!
             connectable
             [(str "SELECT
                       count(*) FILTER (WHERE r.active AND r.reaction = 'like')::int AS like,
                       count(*) FILTER (WHERE r.active AND r.reaction = 'love')::int AS love,
                       count(*) FILTER (WHERE r.active AND r.reaction = 'insightful')::int AS insightful,
                       count(*) FILTER (WHERE r.active AND r.reaction = 'celebrate')::int AS celebrate,
                       count(*) FILTER (WHERE r.active AND r.reaction = 'support')::int AS support,
                       count(*) FILTER (WHERE r.active AND r.reaction = 'funny')::int AS funny
                      FROM " table " r
                      JOIN characters reactor ON reactor.id = r.character_id
                      JOIN characters viewer ON viewer.id = ?::uuid
                     WHERE r.target_id = ?::uuid
                       AND NOT EXISTS (
                         SELECT 1 FROM blocks b
                          WHERE (b.blocker_account_id = viewer.account_id
                                 AND b.blocked_account_id = reactor.account_id)
                             OR (b.blocker_account_id = reactor.account_id
                                 AND b.blocked_account_id = viewer.account_id))")
               viewer-character-id target-id]
             query-options)]
    (select-keys row [:like :love :insightful :celebrate :support :funny])))

(defn adapter [datasource]
  (reaction-port/assert-adapter!
   {:target-public
    (fn [{:keys [type id actor-character-id]}]
      (when-let [row (jdbc/execute-one!
                      datasource
                      [(target-query type) actor-character-id id]
                      query-options)]
        {:id (str (:id row))
         :type type
         :text (:text row)
         :character (json-value (:persona_snapshot row))
         :anonymous-persona (json-value (:anonymous_persona_snapshot row))
         :character-id (some-> (:character_id row) str)}))

    :toggle!
    (fn [{:keys [type target actor-character-id decide]}]
      (let [table (table-for type)
            target-id (:id target)]
        (jdbc/with-transaction [tx datasource]
          (jdbc/execute-one!
           tx
           ["SELECT pg_advisory_xact_lock(hashtextextended(?, 0))"
            (str "reaction:" type ":" target-id ":" actor-character-id)])
          (let [existing (jdbc/execute-one!
                          tx
                          [(str "SELECT reaction, active FROM " table
                                " WHERE target_id = ?::uuid AND character_id = ?::uuid FOR UPDATE")
                           target-id actor-character-id]
                          query-options)
                decision (when-not existing (decide))
                transition (reaction/transition existing (:reaction decision))]
            (if existing
              (jdbc/execute-one!
               tx
               [(str "UPDATE " table " SET active = ?, updated_at = now()
                       WHERE target_id = ?::uuid AND character_id = ?::uuid")
                (:active transition) target-id actor-character-id])
              (let [run-id (insert-ai-run! tx (:ai-run decision))]
                (jdbc/execute-one!
                 tx
                 [(str "INSERT INTO " table
                       " (target_id, character_id, reaction, active, ai_run_id)
                         VALUES (?::uuid, ?::uuid, ?, true, ?::uuid)")
                  target-id actor-character-id (:reaction transition) run-id])
                (when (and (:character-id target)
                           (not= actor-character-id (:character-id target)))
                  (let [notification-id
                        (:id
                         (jdbc/execute-one!
                          tx
                          ["INSERT INTO notifications
                              (target_character_id, actor_character_id, type, resource_type,
                               resource_id, reaction)
                            VALUES (?::uuid, ?::uuid, 'reaction', ?, ?::uuid, ?) RETURNING id"
                           (:character-id target) actor-character-id type target-id
                           (:reaction transition)]
                          query-options))]
                    (jdbc/execute-one!
                     tx
                     ["INSERT INTO outbox_events
                         (event_type, aggregate_type, aggregate_id, target_character_id)
                       VALUES ('notification.created', 'notification', ?::uuid, ?::uuid)"
                      notification-id (:character-id target)])))))
            (assoc (select-keys transition [:reaction :active :restored])
                   :counts (aggregate tx table target-id actor-character-id))))))}))
