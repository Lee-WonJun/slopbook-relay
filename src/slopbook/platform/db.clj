(ns slopbook.platform.db
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc])
  (:import [com.zaxxer.hikari HikariConfig HikariDataSource]
           [java.net URI URLDecoder URLEncoder]
           [java.nio.charset StandardCharsets]))

(defn- decode [value]
  (when value
    (URLDecoder/decode value (.name StandardCharsets/UTF_8))))

(defn- database-url-parts [database-url]
  (when (and database-url
             (or (str/starts-with? database-url "postgres://")
                 (str/starts-with? database-url "postgresql://")))
    (let [uri (URI/create database-url)
          user-info (.getUserInfo uri)
          [username password] (when user-info (str/split user-info #":" 2))
          port (if (neg? (.getPort uri)) 5432 (.getPort uri))]
      {:jdbc-url (str "jdbc:postgresql://" (.getHost uri) ":" port (.getPath uri)
                      (when-let [query (.getRawQuery uri)] (str "?" query)))
       :username (decode username)
       :password (decode password)})))

(defn normalize-database-url [database-url]
  (if (str/starts-with? (or database-url "") "jdbc:postgresql:")
    {:jdbc-url database-url}
    (or (database-url-parts database-url)
        (throw (ex-info "DATABASE_URL must be a PostgreSQL URL" {})))))

(defn with-current-schema [jdbc-url schema]
  (if (str/blank? schema)
    jdbc-url
    (let [[base query] (str/split jdbc-url #"\?" 2)
          existing (if query (str/split query #"&") [])
          without-schema (remove #(str/starts-with? % "currentSchema=") existing)
          ;; The application schema stays first so unqualified tables are isolated.
          ;; public remains visible because PostgreSQL extensions such as pg_trgm
          ;; install their operators and operator classes there.
          search-path (str/join "," (distinct [schema "public"]))
          encoded (URLEncoder/encode search-path (.name StandardCharsets/UTF_8))]
      (str base "?" (str/join "&" (concat without-schema [(str "currentSchema=" encoded)]))))))

(defn datasource [{:keys [database-url db-schema db-pool-max]}]
  (let [{:keys [jdbc-url username password]} (normalize-database-url database-url)
        hikari (doto (HikariConfig.)
                 (.setJdbcUrl (with-current-schema jdbc-url db-schema))
                 (.setMaximumPoolSize (int db-pool-max))
                 (.setMinimumIdle 0)
                 (.setConnectionTimeout 5000)
                 (.setValidationTimeout 3000)
                 (.setIdleTimeout 60000)
                 (.setMaxLifetime 600000)
                 (.setPoolName "slopbook-db"))]
    (when username (.setUsername hikari username))
    (when password (.setPassword hikari password))
    (HikariDataSource. hikari)))

(defn healthy? [datasource]
  (try
    (= 1 (:result (jdbc/execute-one! datasource ["SELECT 1 AS result"])))
    (catch Exception _ false)))

(defn close! [datasource]
  (when (instance? HikariDataSource datasource)
    (.close ^HikariDataSource datasource)))
