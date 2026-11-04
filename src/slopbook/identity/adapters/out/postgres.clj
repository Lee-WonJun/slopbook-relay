(ns slopbook.identity.adapters.out.postgres
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as result-set]
            [slopbook.identity.domain.character :as character]
            [slopbook.identity.ports.characters :as character-port]
            [slopbook.platform.errors :as errors]
            [slopbook.platform.json :as json]
            [slopbook.shared.domain.media :as shared-media])
  (:import [java.sql Timestamp]
           [org.postgresql.util PGobject PSQLException]))

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

(defn- row->internal [row]
  (when row
    (let [avatar (when (:avatar_id row)
                   {:id (str (:avatar_id row)) :purpose "avatar"
                    :variants (json-value (:avatar_variants row))
                    :width (:avatar_width row) :height (:avatar_height row)
                    :average_color (:avatar_average_color row)
                    :alt_text (:avatar_alt_text row) :alt_language (:avatar_alt_language row)
                    :created_at (:avatar_created_at row)})]
    (-> row
        (update :id str)
        (update :avatar_media_id #(when % (str %)))
        (update :persona_public json-value)
        (update :persona_behavior json-value)
        (update :created_at instant-string)
        (update :updated_at instant-string)
        (assoc :human_owned true :avatar_upload (shared-media/avatar-upload avatar))
        (dissoc :avatar_id :avatar_variants :avatar_width :avatar_height
                :avatar_average_color :avatar_alt_text :avatar_alt_language
                :avatar_created_at)))))

(def select-fields
  "c.id, c.account_id, c.handle, c.display_name, c.avatar_media_id, c.nationality,
   c.output_language, c.persona_public, c.persona_behavior, c.persona_version,
   c.status, c.created_at, c.updated_at,
   avatar.id AS avatar_id, avatar.variants AS avatar_variants,
   avatar.width AS avatar_width, avatar.height AS avatar_height,
   avatar.average_color AS avatar_average_color, avatar.alt_text AS avatar_alt_text,
   avatar.alt_language AS avatar_alt_language, avatar.created_at AS avatar_created_at")

(def avatar-join
  " LEFT JOIN media avatar ON avatar.id = c.avatar_media_id AND avatar.status = 'attached'")

(defn- internal-by-id [connectable id]
  (some-> (jdbc/execute-one!
           connectable
           [(str "SELECT " select-fields " FROM characters c" avatar-join
                 " WHERE c.id = ?::uuid") id]
           query-options)
          row->internal))

(defn- handle-conflict! [exception]
  (if (= "23505" (.getSQLState ^PSQLException exception))
    (errors/fail! 409 "HANDLE_TAKEN" :conflict)
    (throw exception)))

(defn adapter [datasource]
  (character-port/assert-adapter!
   {:list-owned
    (fn [account-id]
      (->> (jdbc/execute!
            datasource
            [(str "SELECT " select-fields " FROM characters c" avatar-join
                  " WHERE c.account_id = ?::uuid AND c.status = 'active'"
                  " ORDER BY c.created_at, c.id") account-id]
            query-options)
           (mapv (comp character/owned-character row->internal))))

    :find-internal
    (fn [character-id]
      (internal-by-id datasource character-id))

    :find-public-by-handle
    (fn [handle]
      (some-> (jdbc/execute-one!
               datasource
               [(str "SELECT " select-fields " FROM characters c" avatar-join
                     " WHERE c.handle = ? AND c.status = 'active'") handle]
               query-options)
              row->internal
              character/public-character))

    :handle-exists?
    (fn [handle]
      (boolean
       (:present
        (jdbc/execute-one!
         datasource
         ["SELECT EXISTS (SELECT 1 FROM characters WHERE handle = ?) AS present" handle]
         query-options))))

    :insert-if-below-limit!
    (fn [account-id limit value]
      (try
        (jdbc/with-transaction [tx datasource]
          (when-not (jdbc/execute-one!
                     tx
                     ["SELECT id FROM accounts WHERE id = ?::uuid FOR UPDATE" account-id]
                     query-options)
            (throw (ex-info "Character owner account was not found" {:account-id account-id})))
          (let [active-count (:active_count
                              (jdbc/execute-one!
                               tx
                               ["SELECT count(*)::int AS active_count
                                   FROM characters
                                  WHERE account_id = ?::uuid AND status = 'active'"
                                account-id]
                               query-options))]
            (when (< active-count limit)
              (let [row (jdbc/execute-one!
                         tx
                         [(str "INSERT INTO characters
                                 (id, account_id, handle, display_name, avatar_media_id, nationality,
                                  output_language, persona_public, persona_behavior, persona_version, status)
                                 VALUES (?::uuid, ?::uuid, ?, ?, ?::uuid, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
                                 RETURNING id")
                          (:id value) (:account_id value) (:handle value) (:display_name value)
                          (:avatar_media_id value) (:nationality value) (:output_language value)
                          (json/write-value (:persona_public value))
                          (json/write-value (:persona_behavior value))
                          (:persona_version value) (:status value)]
                         query-options)]
                (internal-by-id tx (:id row))))))
        (catch PSQLException exception
          (handle-conflict! exception))))

    :update!
    (fn [value]
      (jdbc/with-transaction [tx datasource]
        (let [current (jdbc/execute-one!
                       tx
                       ["SELECT avatar_media_id FROM characters
                           WHERE id = ?::uuid AND status = 'active' FOR UPDATE" (:id value)]
                       query-options)
              old-id (some-> (:avatar_media_id current) str)
              new-id (:avatar_media_id value)
              changed? (not= old-id new-id)
              old-media (when (and changed? old-id)
                          (jdbc/execute-one!
                           tx ["SELECT id, variants FROM media WHERE id = ?::uuid FOR UPDATE" old-id]
                           query-options))]
          (when (and changed? new-id)
            (when-not (jdbc/execute-one!
                       tx
                       ["UPDATE media SET status = 'attached', expires_at = NULL, updated_at = now()
                           WHERE id = ?::uuid AND owner_character_id = ?::uuid
                             AND purpose = 'avatar' AND status = 'ready' AND expires_at > now()
                           RETURNING id" new-id (:id value)]
                       query-options)
              (throw (ex-info "Avatar media was no longer attachable" {:media-id new-id}))))
          (jdbc/execute-one!
           tx
           [(str "UPDATE characters c
                   SET display_name = ?, avatar_media_id = ?::uuid, nationality = ?,
                       output_language = ?, persona_public = ?::jsonb,
                       persona_behavior = ?::jsonb, persona_version = ?, updated_at = now()
                   WHERE id = ?::uuid AND status = 'active'")
            (:display_name value) (:avatar_media_id value) (:nationality value)
            (:output_language value) (json/write-value (:persona_public value))
            (json/write-value (:persona_behavior value)) (:persona_version value) (:id value)]
           query-options)
          (when (and changed? old-id)
            (jdbc/execute-one!
             tx ["UPDATE media SET status = 'deleted', deleted_at = now(), updated_at = now(),
                                  purge_available_at = CASE WHEN variants IS NULL THEN NULL ELSE now() END
                    WHERE id = ?::uuid AND status = 'attached'" old-id]))
          (cond-> (internal-by-id tx (:id value))
            (seq (json-value (:variants old-media)))
            (assoc :_purges
                   [{:id (str (:id old-media))
                     :keys (mapv :key (json-value (:variants old-media)))}])))))

   :delete!
    (fn [character-id _now]
      (jdbc/with-transaction [tx datasource]
        (when-let [current (jdbc/execute-one!
                            tx
                            ["SELECT id, avatar_media_id FROM characters
                                WHERE id = ?::uuid AND status = 'active' FOR UPDATE"
                             character-id]
                            query-options)]
          (let [media (when-let [media-id (:avatar_media_id current)]
                        (jdbc/execute-one!
                         tx ["SELECT id, variants FROM media WHERE id = ?::uuid FOR UPDATE"
                             media-id]
                         query-options))]
            (jdbc/execute-one!
             tx
             ["UPDATE characters
                 SET status = 'deleted', deleted_at = now(), updated_at = now()
               WHERE id = ?::uuid" character-id]
             query-options)
            (when media
              (jdbc/execute-one!
               tx
               ["UPDATE media
                   SET status = 'deleted', deleted_at = now(), updated_at = now(),
                       purge_available_at = CASE WHEN variants IS NULL THEN NULL ELSE now() END
                 WHERE id = ?::uuid AND status = 'attached'" (:id media)]
               query-options))
            (cond-> {:deleted true}
              (seq (json-value (:variants media)))
              (assoc :purges
                     [{:id (str (:id media))
                       :keys (mapv :key (json-value (:variants media)))}]))))))}))
