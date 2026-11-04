(ns slopbook.media.adapters.out.postgres
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as result-set]
            [slopbook.media.domain.asset :as asset]
            [slopbook.media.ports.assets :as asset-port]
            [slopbook.platform.json :as json])
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

(defn- row-value [row]
  (when row
    {:id (str (:id row))
     :owner_character_id (str (:owner_character_id row))
     :purpose (:purpose row) :status (:status row)
     :width (:width row) :height (:height row)
     :variants (or (json-value (:variants row)) [])
     :average_color (:average_color row)
     :alt_text (:alt_text row) :alt_language (:alt_language row)
     :error_code (case (:status row)
                   "rejected" "MEDIA_REJECTED"
                   "blocked" "MEDIA_POLICY_BLOCKED"
                   "failed" "MEDIA_PROCESSING_FAILED" nil)
     :created_at (instant-string (:created_at row))}))

(def media-select
  "SELECT id, owner_character_id, purpose, status, width, height, variants,
          average_color, alt_text, alt_language, created_at FROM media")

(defn adapter [datasource]
  (asset-port/assert-adapter!
   {:insert-upload!
    (fn [value]
      (-> (jdbc/execute-one!
           datasource
           [(str "INSERT INTO media (id, owner_character_id, purpose, status, expires_at)
                  VALUES (?::uuid, ?::uuid, ?, 'validating', now() + interval '30 minutes')
                  RETURNING id, owner_character_id, purpose, status, width, height, variants,
                            average_color, alt_text, alt_language, created_at")
            (:id value) (:owner_character_id value) (:purpose value)]
           query-options)
          row-value asset/public-asset))
    :find-owned
    (fn [{:keys [id owner-character-id]}]
      (some-> (jdbc/execute-one!
               datasource
               [(str media-select " WHERE id = ?::uuid AND owner_character_id = ?::uuid
                                      AND status <> 'deleted'") id owner-character-id]
               query-options)
              row-value asset/public-asset))
    :mark-processing!
    (fn [id]
      (boolean (jdbc/execute-one! datasource
                                  ["UPDATE media SET status = 'processing', updated_at = now()
                                     WHERE id = ?::uuid AND status = 'validating' RETURNING id" id]
                                  query-options)))
    :mark-ready!
    (fn [{:keys [id verified-type width height variants average-color alt-text alt-language]}]
      (some-> (jdbc/execute-one!
               datasource
               [(str "UPDATE media SET status = 'ready', verified_input_type = ?,
                                      width = ?, height = ?, variants = ?::jsonb,
                                      placeholder_key = ?, average_color = ?, alt_text = ?,
                                      alt_language = ?, safety_reason = NULL, ai_run_id = NULL,
                                      updated_at = now()
                                WHERE id = ?::uuid AND status = 'processing'
                                RETURNING id, owner_character_id, purpose, status, width, height,
                                          variants, average_color, alt_text, alt_language, created_at")
                verified-type width height (json/write-value variants)
                (:key (some #(when (:placeholder %) %) variants))
                average-color alt-text alt-language id]
               query-options)
              row-value asset/public-asset))
    :mark-failed!
    (fn [{:keys [id status safety-reason purge-keys]}]
      (boolean
       (jdbc/execute-one!
        datasource
        ["UPDATE media SET status = ?, safety_reason = ?, variants = ?::jsonb,
                          placeholder_key = NULL, purge_available_at = ?, updated_at = now()
          WHERE id = ?::uuid AND status NOT IN ('attached', 'deleted') RETURNING id"
         status (or safety-reason "unknown")
         (when (seq purge-keys)
           (json/write-value (mapv (fn [key] {:key key}) purge-keys)))
         (when (seq purge-keys) (str (java.time.Instant/now))) id]
        query-options)))
    :ready-owned
    (fn [{:keys [ids owner-character-id purpose]}]
      (if (empty? ids)
        []
        (let [placeholders (str/join "," (repeat (count ids) "?::uuid"))
              sql (str media-select " WHERE id IN (" placeholders ")
                       AND owner_character_id = ?::uuid AND purpose = ? AND status = 'ready'
                       AND expires_at > now()")
              params (concat ids [owner-character-id purpose])]
          (mapv (comp asset/public-asset row-value)
                (jdbc/execute! datasource (into [sql] params) query-options)))))
    :delete-unattached!
    (fn [{:keys [id owner-character-id]}]
      (when-let [row (jdbc/execute-one!
                      datasource
                      [(str "UPDATE media SET status = 'deleted', deleted_at = now(), updated_at = now(),
                                                purge_available_at = CASE WHEN variants IS NULL
                                                                          THEN NULL ELSE now() END
                              WHERE id = ?::uuid AND owner_character_id = ?::uuid
                                AND status <> 'attached' AND status <> 'deleted'
                              RETURNING id, variants") id owner-character-id]
                      query-options)]
        {:id (str (:id row))
         :keys (mapv :key (or (json-value (:variants row)) []))}))

    :expire-unattached!
    (fn []
      (:amount
       (jdbc/execute-one!
        datasource
        ["WITH expired AS (
            UPDATE media
               SET status = 'deleted', deleted_at = now(), updated_at = now(),
                   purge_available_at = CASE WHEN variants IS NULL THEN NULL ELSE now() END
             WHERE expires_at <= now() AND status NOT IN ('attached', 'deleted')
             RETURNING id)
          SELECT count(*)::int AS amount FROM expired"]
        query-options)))

    :pending-purges
    (fn [limit]
      (mapv (fn [row]
              {:id (str (:id row))
               :keys (mapv :key (or (json-value (:variants row)) []))})
            (jdbc/execute!
             datasource
             ["SELECT id, variants FROM media
                WHERE variants IS NOT NULL AND purged_at IS NULL
                  AND purge_available_at <= now()
                ORDER BY purge_available_at, id LIMIT ?" limit]
             query-options)))

    :mark-purged!
    (fn [id]
      (boolean
       (jdbc/execute-one!
        datasource
        ["UPDATE media
            SET variants = NULL, placeholder_key = NULL, purged_at = now(),
                purge_available_at = NULL, last_purge_error_code = NULL, updated_at = now()
          WHERE id = ?::uuid AND purged_at IS NULL
          RETURNING id" id]
        query-options)))

    :mark-purge-failed!
    (fn [id error-code]
      (boolean
       (jdbc/execute-one!
        datasource
        ["UPDATE media
            SET purge_attempt_count = purge_attempt_count + 1,
                purge_available_at = now() +
                  (least(3600, 5 * power(2, least(purge_attempt_count, 9))) * interval '1 second'),
                last_purge_error_code = ?, updated_at = now()
          WHERE id = ?::uuid AND purged_at IS NULL
          RETURNING id" (or error-code "MEDIA_STORAGE_FAILED") id]
        query-options)))}))
