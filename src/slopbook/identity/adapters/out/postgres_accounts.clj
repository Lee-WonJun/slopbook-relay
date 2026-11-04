(ns slopbook.identity.adapters.out.postgres-accounts
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as result-set]
            [slopbook.identity.ports.accounts :as account-port]))

(def query-options {:builder-fn result-set/as-unqualified-lower-maps})

(defn adapter [datasource]
  (account-port/assert-adapter!
   {:find-or-create!
    (fn [auth-subject]
      (jdbc/execute-one!
       datasource
       ["INSERT INTO accounts (auth_subject)
         VALUES (?)
         ON CONFLICT (auth_subject) DO UPDATE
           SET auth_subject = EXCLUDED.auth_subject
         RETURNING id, status, created_at, updated_at"
        auth-subject]
       query-options))

    :find-by-id
    (fn [account-id]
      (jdbc/execute-one!
       datasource
       ["SELECT id, status, created_at, updated_at FROM accounts WHERE id::text = ?" (str account-id)]
       query-options))

    :request-deletion!
    (fn [account-id]
      (jdbc/with-transaction [tx datasource]
        (when-let [account
                   (jdbc/execute-one!
                    tx
                    ["UPDATE accounts
                         SET status = 'deletion_pending', updated_at = now()
                       WHERE id::text = ? AND status = 'active'
                       RETURNING id, status, created_at, updated_at, deleted_at"
                     (str account-id)]
                    query-options)]
          (jdbc/execute-one!
           tx
           ["UPDATE characters
               SET status = 'deleted', deleted_at = now(), updated_at = now()
             WHERE account_id = ?::uuid AND status <> 'deleted'"
            (str account-id)])
          account)))}))
