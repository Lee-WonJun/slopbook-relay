(ns slopbook.platform.migration-integration-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [slopbook.platform.migrations :as migrations]
            [slopbook.platform.schema-audit :as schema-audit])
  (:import [java.sql SQLException]
           [org.postgresql.util PSQLException]))

(def database-url
  (or (System/getenv "DATABASE_URL")
      "jdbc:postgresql://127.0.0.1:15432/slopbook?user=slopbook&password=local-slopbook-only"))

(def datasource (schema-audit/datasource database-url))

(defn migrate-once [test-fn]
  (migrations/migrate! {:database-url database-url})
  (test-fn))

(use-fixtures :once migrate-once)

(defn rejected? [sql-params]
  (try
    (jdbc/execute! datasource sql-params)
    false
    (catch SQLException _ true)))

(defn rejected-constraint [sql-params]
  (try
    (jdbc/execute! datasource sql-params)
    nil
    (catch PSQLException exception
      (some-> exception .getServerErrorMessage .getConstraint))))

(defn one [sql-params]
  (jdbc/execute-one! datasource sql-params {:builder-fn rs/as-unqualified-lower-maps}))

(def persona-public
  "{\"what_are_you\":\"signal\",\"archetypes\":[],\"traits\":[]}")

(def persona-behavior
  "{\"voice_tones\":[],\"sentence_shape\":\"short\",\"formality\":\"casual\"}")

(def public-snapshot
  "{\"persona_public\":{},\"sigil\":{\"version\":1}}")

(defn seed-identities! []
  (jdbc/execute! datasource ["INSERT INTO accounts (id, auth_subject) VALUES
                              ('00000000-0000-0000-0000-000000000001', 'fixture-1'),
                              ('00000000-0000-0000-0000-000000000002', 'fixture-2')
                              ON CONFLICT DO NOTHING"])
  (jdbc/execute! datasource ["INSERT INTO characters
                              (id, account_id, handle, display_name, output_language,
                               persona_public, persona_behavior)
                              VALUES
                              ('10000000-0000-0000-0000-000000000001',
                               '00000000-0000-0000-0000-000000000001',
                               'fixture_one', 'Fixture One', 'en', ?::jsonb, ?::jsonb),
                              ('10000000-0000-0000-0000-000000000002',
                               '00000000-0000-0000-0000-000000000002',
                               'fixture_two', 'Fixture Two', 'ko', ?::jsonb, ?::jsonb)
                              ON CONFLICT DO NOTHING"
                             persona-public persona-behavior persona-public persona-behavior])
  (jdbc/execute! datasource ["INSERT INTO posts
                              (id, character_id, text, output_language, persona_snapshot)
                              VALUES ('20000000-0000-0000-0000-000000000001',
                                      '10000000-0000-0000-0000-000000000001',
                                      'Transformed fixture.', 'en', ?::jsonb)
                              ON CONFLICT DO NOTHING"
                             public-snapshot]))

(deftest migrations-are-idempotent-and-audited
  (migrations/migrate! {:database-url database-url})
  (is (empty? (migrations/pending-list {:database-url database-url})))
  (is (= {:forbidden_columns 0 :required_constraints 3 :required_indexes 3}
         (schema-audit/audit! datasource)))
  (is (>= (:table_count (one ["SELECT count(*)::int AS table_count
                                FROM information_schema.tables
                               WHERE table_schema = 'public'"]))
          20)))

(deftest named-schema-isolates-an-existing-public-schema
  (let [schema "slopbook_isolation_fixture"
        isolated-config {:database-url database-url :db-schema schema}]
    (try
      (jdbc/execute! datasource [(str "DROP SCHEMA IF EXISTS " schema " CASCADE")])
      (migrations/migrate! isolated-config)
      (is (empty? (migrations/pending-list isolated-config)))
      (is (= "posts"
             (:table_name
              (one ["SELECT table_name
                       FROM information_schema.tables
                      WHERE table_schema = ? AND table_name = 'posts'"
                    schema]))))
      (is (= "slopbook_schema_migrations"
             (:table_name
              (one ["SELECT table_name
                       FROM information_schema.tables
                      WHERE table_schema = ?
                        AND table_name = 'slopbook_schema_migrations'"
                    schema]))))
      (is (= "posts" (:table_name
                       (one ["SELECT table_name
                               FROM information_schema.tables
                              WHERE table_schema = 'public' AND table_name = 'posts'"]))))
      (finally
        (jdbc/execute! datasource [(str "DROP SCHEMA IF EXISTS " schema " CASCADE")])))))

(deftest database-rejects-critical-invariant-conflicts
  (seed-identities!)
  (testing "follow self-check"
    (is (= "follows_no_self"
           (rejected-constraint
            ["INSERT INTO follows (follower_character_id, followee_character_id)
              VALUES ('10000000-0000-0000-0000-000000000001',
                      '10000000-0000-0000-0000-000000000001')"]))))
  (testing "account block self-check"
    (is (= "blocks_no_self"
           (rejected-constraint
            ["INSERT INTO blocks
              (blocker_account_id, blocked_account_id, created_by_character_id)
              VALUES ('00000000-0000-0000-0000-000000000001',
                      '00000000-0000-0000-0000-000000000001',
                      '10000000-0000-0000-0000-000000000001')"]))))
  (testing "comment actor XOR"
    (is (= "comments_actor_xor"
           (rejected-constraint
            ["INSERT INTO comments
              (post_id, character_id, anonymous_session_id, text, output_language,
               persona_snapshot, status)
              VALUES ('20000000-0000-0000-0000-000000000001',
                      '10000000-0000-0000-0000-000000000001',
                      '30000000-0000-0000-0000-000000000001',
                      'invalid', 'en', ?::jsonb, 'published')"
             public-snapshot]))))
  (testing "public persona snapshot rejects behavior"
    (is (rejected? ["INSERT INTO posts
                     (character_id, text, output_language, persona_snapshot)
                     VALUES ('10000000-0000-0000-0000-000000000001',
                             'invalid', 'en',
                             '{\"persona_behavior\":{}}'::jsonb)"])))
  (testing "reaction pair is unique"
    (jdbc/execute! datasource ["INSERT INTO post_reactions
                                (target_id, character_id, reaction)
                                VALUES ('20000000-0000-0000-0000-000000000001',
                                        '10000000-0000-0000-0000-000000000001',
                                        'like')
                                ON CONFLICT DO NOTHING"])
    (is (= "post_reactions_unique_actor"
           (rejected-constraint
            ["INSERT INTO post_reactions
              (target_id, character_id, reaction)
              VALUES ('20000000-0000-0000-0000-000000000001',
                      '10000000-0000-0000-0000-000000000001',
                      'love')"])))))
