(ns slopbook.identity.adapters.out.postgres-accounts-integration-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as result-set]
            [slopbook.identity.adapters.out.postgres-accounts :as postgres-accounts]
            [slopbook.identity.ports.accounts :as account-port]
            [slopbook.platform.migrations :as migrations]
            [slopbook.platform.schema-audit :as schema-audit]))

(def database-url
  (or (System/getenv "DATABASE_URL")
      "jdbc:postgresql://127.0.0.1:15432/slopbook?user=slopbook&password=local-slopbook-only"))

(def datasource (schema-audit/datasource database-url))
(def account-store (postgres-accounts/adapter datasource))
(def query-options {:builder-fn result-set/as-unqualified-lower-maps})

(use-fixtures :once
  (fn [test-fn]
    (migrations/migrate! {:database-url database-url})
    (test-fn)))

(deftest concurrent-first-request-creates-one-account
  (let [subject "concurrent-auth-subject-fixture"]
    (jdbc/execute! datasource ["DELETE FROM accounts WHERE auth_subject = ?" subject])
    (let [accounts (->> (range 16)
                        (mapv (fn [_]
                                (future (account-port/find-or-create! account-store subject))))
                        (mapv deref))]
      (is (= 1 (count (set (map :id accounts)))))
      (is (= 1 (:account_count
                (jdbc/execute-one!
                 datasource
                 ["SELECT count(*)::int AS account_count FROM accounts WHERE auth_subject = ?" subject]
                 query-options)))))))

(deftest account-table-does-not-copy-provider-profile
  (let [columns (set (map :column_name
                          (jdbc/execute!
                           datasource
                           ["SELECT column_name FROM information_schema.columns
                              WHERE table_schema = 'public' AND table_name = 'accounts'"]
                           query-options)))]
    (is (contains? columns "auth_subject"))
    (is (not (contains? columns "email")))
    (is (not (contains? columns "name")))))

(deftest deletion-request-deactivates-the-account-and-every-owned-character
  (let [subject "deletion-request-subject-fixture"
        delete-characters (jdbc/execute! datasource ["DELETE FROM characters WHERE handle IN ('delete_fixture_one', 'delete_fixture_two')"])
        delete-account (jdbc/execute! datasource ["DELETE FROM accounts WHERE auth_subject = ?" subject])
        account (account-port/find-or-create! account-store subject)
        account-id (str (:id account))]
    (jdbc/execute! datasource
                   ["INSERT INTO characters
                       (account_id, handle, display_name, output_language,
                        persona_public, persona_behavior)
                     VALUES (?::uuid, 'delete_fixture_one', 'Delete One', 'en', '{}'::jsonb, '{}'::jsonb),
                            (?::uuid, 'delete_fixture_two', 'Delete Two', 'ko', '{}'::jsonb, '{}'::jsonb)"
                    account-id account-id])
    (let [result (account-port/request-deletion! account-store account-id)
          characters (jdbc/execute!
                      datasource
                      ["SELECT status FROM characters WHERE account_id = ?::uuid" account-id]
                      query-options)]
      (is (= "deletion_pending" (:status result)))
      (is (= #{"deleted"} (set (map :status characters)))))))
