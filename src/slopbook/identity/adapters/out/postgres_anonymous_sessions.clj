(ns slopbook.identity.adapters.out.postgres-anonymous-sessions
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as result-set]
            [slopbook.identity.ports.anonymous-sessions :as session-port]))

(def query-options {:builder-fn result-set/as-unqualified-lower-maps})

(defn adapter [datasource]
  (session-port/assert-adapter!
   {:insert! (fn [value]
               (some-> (jdbc/execute-one!
                        datasource
                        ["INSERT INTO anonymous_sessions (id, deletion_token_digest)
                            VALUES (?::uuid, ?) RETURNING id, deletion_token_digest, status"
                         (:id value) (:deletion_token_digest value)]
                        query-options)
                       (update :id str)))
    :find! (fn [id]
             (some-> (jdbc/execute-one!
                      datasource
                      ["SELECT id, deletion_token_digest, status
                          FROM anonymous_sessions WHERE id = ?::uuid" id]
                      query-options)
                     (update :id str)))
    :touch! (fn [id]
              (boolean
               (jdbc/execute-one!
                datasource
                ["UPDATE anonymous_sessions SET last_used_at = now()
                    WHERE id = ?::uuid AND status = 'active' RETURNING id" id]
                query-options)))}))
