(ns slopbook.platform.schema-audit
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def forbidden-column-pattern
  "(^|_)(raw|original_text|prompt_body|input_hash)($|_)")

(defn datasource [database-url]
  (jdbc/get-datasource {:jdbcUrl database-url}))

(defn forbidden-columns [connectable]
  (jdbc/execute!
   connectable
   ["SELECT table_name, column_name
       FROM information_schema.columns
      WHERE table_schema = 'public'
        AND column_name ~* ?
      ORDER BY table_name, column_name"
    forbidden-column-pattern]
   {:builder-fn rs/as-unqualified-lower-maps}))

(defn required-schema-facts [connectable]
  (let [constraints (jdbc/execute!
                     connectable
                     ["SELECT conname
                         FROM pg_constraint
                        WHERE conname IN ('comments_actor_xor', 'follows_pkey', 'blocks_pkey')"]
                     {:builder-fn rs/as-unqualified-lower-maps})
        indexes (jdbc/execute!
                 connectable
                 ["SELECT indexname
                     FROM pg_indexes
                    WHERE schemaname = 'public'
                      AND indexname IN ('posts_text_trgm_idx', 'characters_handle_trgm_idx',
                                        'post_reactions_unique_actor')"]
                 {:builder-fn rs/as-unqualified-lower-maps})]
    {:constraints (set (map :conname constraints))
     :indexes (set (map :indexname indexes))}))

(defn audit! [connectable]
  (let [forbidden (forbidden-columns connectable)
        facts (required-schema-facts connectable)
        expected-constraints #{"comments_actor_xor" "follows_pkey" "blocks_pkey"}
        expected-indexes #{"posts_text_trgm_idx" "characters_handle_trgm_idx"
                           "post_reactions_unique_actor"}]
    (when (or (seq forbidden)
              (not= expected-constraints (:constraints facts))
              (not= expected-indexes (:indexes facts)))
      (throw (ex-info "Schema audit failed" {:forbidden forbidden :facts facts})))
    {:forbidden_columns 0
     :required_constraints (count expected-constraints)
     :required_indexes (count expected-indexes)}))
