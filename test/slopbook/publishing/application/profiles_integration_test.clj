(ns slopbook.publishing.application.profiles-integration-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [next.jdbc :as jdbc]
            [slopbook.identity.adapters.out.postgres :as postgres-characters]
            [slopbook.identity.adapters.out.postgres-accounts :as postgres-accounts]
            [slopbook.identity.application.characters :as characters]
            [slopbook.platform.json :as json]
            [slopbook.platform.migrations :as migrations]
            [slopbook.platform.schema-audit :as schema-audit]
            [slopbook.publishing.adapters.out.postgres :as postgres-posts]
            [slopbook.publishing.application.profiles :as profiles])
  (:import [java.util UUID]))

(def database-url
  (or (System/getenv "DATABASE_URL")
      "jdbc:postgresql://127.0.0.1:15432/slopbook?user=slopbook&password=local-slopbook-only"))

(def datasource (schema-audit/datasource database-url))

(use-fixtures :once
  (fn [test-fn]
    (migrations/migrate! {:database-url database-url})
    (test-fn)))

(defn suffix [] (subs (str/replace (str (UUID/randomUUID)) "-" "") 0 8))

(defn persona-draft [handle display-name]
  {:handle handle
   :display_name display-name
   :nationality "KR"
   :output_language "en"
   :persona_public {:schema_version 1 :catalog_version 1
                    :what_are_you [{:id "helicopter"}]
                    :archetypes [{:id "archivist"}]
                    :traits [{:id "warm"} {:id "curious"} {:id "meticulous"}]
                    :interests [{:id "weather"}]
                    :motto "Keep the public signal clear."}
   :persona_behavior {:schema_version 1 :voice_tones [{:id "warm"}]
                      :sentence_shape "short" :formality "neutral"
                      :custom_rule "OWNER_ONLY_PROFILE_CANARY"}})

(defn error-code [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo exception (:code (ex-data exception)))))

(deftest public-profile-pagination-counts-and-owner-isolation
  (let [token (suffix)
        account-store (postgres-accounts/adapter datasource)
        account ((:find-or-create! account-store) (str "profile-subject-" token))
        character-store (postgres-characters/adapter datasource)
        identity {:store character-store :new-id #(str (UUID/randomUUID))
                  :now #(str (java.time.Instant/now))}
        target (characters/create! identity (:id account)
                                   (persona-draft (str "profile_" token) "Profile Rotor"))
        sibling (characters/create! identity (:id account)
                                    (persona-draft (str "sibling_" token) "Hidden Sibling"))
        target-public (:public_character target)
        target-id (:id target-public)
        sibling-id (get-in sibling [:public_character :id])
        deps {:store (postgres-posts/adapter datasource) :identity identity
              :cursor-secret "synthetic-profile-cursor-secret"}]
    (jdbc/execute-one!
     datasource
     ["INSERT INTO follows (follower_character_id, followee_character_id)
       VALUES (?::uuid, ?::uuid), (?::uuid, ?::uuid)"
      sibling-id target-id target-id sibling-id])
    (doseq [index (range 21)]
      (jdbc/execute-one!
       datasource
       ["INSERT INTO posts
           (id, character_id, text, output_language, persona_snapshot, published_at, created_at)
         VALUES (gen_random_uuid(), ?::uuid, ?, 'en', ?::jsonb,
                 now() - (? * interval '1 second'), now() - (? * interval '1 second'))"
        target-id (str "Profile post " index)
        (json/write-value target-public) index index]))
    (let [first-page (profiles/public-by-handle deps (:handle target-public) nil)
          rendered (pr-str first-page)
          cursor (get-in first-page [:posts :next_cursor])
          second-page (profiles/public-by-handle deps (:handle target-public) cursor)]
      (is (= "KR" (:nationality first-page)))
      (is (= {:followers 1 :following 1 :posts 21} (:counts first-page)))
      (is (= 20 (count (get-in first-page [:posts :items]))))
      (is (string? cursor))
      (is (= 1 (count (get-in second-page [:posts :items]))))
      (is (nil? (get-in second-page [:posts :next_cursor])))
      (is (not (.contains rendered "OWNER_ONLY_PROFILE_CANARY")))
      (is (not (.contains rendered (:handle (:public_character sibling)))))
      (is (not-any? #(contains? first-page %)
                    [:account_id :auth_subject :email :persona_behavior])))
    (is (= "CURSOR_INVALID"
           (error-code #(profiles/public-by-handle deps (:handle target-public) "not-a-cursor"))))
    (jdbc/execute-one! datasource
                       ["UPDATE characters SET status = 'suspended' WHERE id = ?::uuid" target-id])
    (is (= "CHARACTER_NOT_FOUND"
           (error-code #(profiles/public-by-handle deps (:handle target-public) nil))))))

(deftest deleted-profile-uses-the-same-private-404-policy
  (let [token (suffix)
        account-store (postgres-accounts/adapter datasource)
        account ((:find-or-create! account-store) (str "deleted-profile-subject-" token))
        identity {:store (postgres-characters/adapter datasource)
                  :new-id #(str (UUID/randomUUID)) :now #(str (java.time.Instant/now))}
        owned (characters/create! identity (:id account)
                                  (persona-draft (str "deleted_" token) "Deleted Rotor"))
        public (:public_character owned)
        deps {:store (postgres-posts/adapter datasource) :identity identity
              :cursor-secret "synthetic-profile-cursor-secret"}]
    (characters/delete! identity (:id account) (:id public) {:actor_character_id (:id public)})
    (is (= "CHARACTER_NOT_FOUND"
           (error-code #(profiles/public-by-handle deps (:handle public) nil))))))
