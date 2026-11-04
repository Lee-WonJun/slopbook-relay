(ns slopbook.platform.migrations
  (:require [clojure.string :as str]
            [migratus.core :as migratus]
            [next.jdbc :as jdbc]
            [slopbook.platform.config :as config]
            [slopbook.platform.db :as db])
  (:import [java.sql DriverManager]))

(defn- migration-db [{:keys [database-url db-schema]}]
  (let [{:keys [jdbc-url username password]} (db/normalize-database-url database-url)]
    (cond-> {:connection-uri (db/with-current-schema jdbc-url db-schema)}
      username (assoc :user username)
      password (assoc :password password))))

(defn- quote-identifier [identifier]
  (str "\"" (str/replace identifier "\"" "\"\"") "\""))

(defn ensure-schema! [{:keys [database-url db-schema]}]
  (when db-schema
    (let [{:keys [jdbc-url username password]} (db/normalize-database-url database-url)]
      (with-open [connection (if username
                               (DriverManager/getConnection jdbc-url username password)
                               (DriverManager/getConnection jdbc-url))]
        (jdbc/execute! connection
                       [(str "CREATE SCHEMA IF NOT EXISTS "
                             (quote-identifier db-schema))])))))

(defn migration-config
  ([] (migration-config (config/load-config)))
  ([runtime-config]
   (when-not (:database-url runtime-config)
     (throw (ex-info "DATABASE_URL is required for migrations" {})))
   {:store :database
    :migration-dir "migrations"
    ;; A dedicated ledger prevents a legacy public.schema_migrations table from
    ;; making a fresh application schema look as though it is already migrated.
    :migration-table-name (if (:db-schema runtime-config)
                            "slopbook_schema_migrations"
                            "schema_migrations")
    :db (migration-db runtime-config)}))

(defn migrate! [runtime-config]
  (ensure-schema! runtime-config)
  (migratus/migrate (migration-config runtime-config)))

(defn rollback! [runtime-config]
  (ensure-schema! runtime-config)
  (migratus/rollback (migration-config runtime-config)))

(defn pending-list [runtime-config]
  (ensure-schema! runtime-config)
  (migratus/pending-list (migration-config runtime-config)))

(defn -main [& args]
  (let [runtime-config (config/load-config)
        command (first args)]
    (case command
      "up" (do (migrate! runtime-config) (println "Migrations applied."))
      "down" (do (rollback! runtime-config) (println "Latest migration rolled back."))
      "pending" (println (pr-str (pending-list runtime-config)))
      (throw (ex-info "Usage: clojure -M:migrate [up|down|pending]" {})))))
