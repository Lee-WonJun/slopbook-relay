(ns slopbook.media.adapters.out.postgres-media-integration-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [slopbook.identity.adapters.out.postgres :as postgres-characters]
            [slopbook.identity.application.avatars :as avatars]
            [slopbook.media.adapters.out.postgres :as postgres-media]
            [slopbook.media.application.assets :as assets]
            [slopbook.platform.json :as json]
            [slopbook.platform.migrations :as migrations]
            [slopbook.platform.schema-audit :as schema-audit]
            [slopbook.publishing.adapters.out.postgres :as postgres-posts]
            [slopbook.publishing.application.posts :as posts])
  (:import [java.io File]
           [java.nio.file Files]
           [java.time Instant]
           [java.util UUID]))

(def database-url
  (or (System/getenv "DATABASE_URL")
      "jdbc:postgresql://127.0.0.1:15432/slopbook?user=slopbook&password=local-slopbook-only"))

(def datasource (schema-audit/datasource database-url))
(def query-options {:builder-fn rs/as-unqualified-lower-maps})

(use-fixtures :once
  (fn [test-fn]
    (migrations/migrate! {:database-url database-url})
    (test-fn)))

(def public-persona
  {:schema_version 1 :catalog_version 1
   :what_are_you [{:id "helicopter"}]
   :archetypes [{:id "archivist"}]
   :traits [{:id "warm"} {:id "curious"} {:id "meticulous"}]})

(def behavior-persona
  {:schema_version 1 :voice_tones [{:id "warm"}]
   :sentence_shape "short" :formality "neutral"
   :custom_rule "OWNER_ONLY_MEDIA_CANARY"})

(defn seed-owner! []
  (let [account-id (:id (jdbc/execute-one!
                         datasource
                         ["INSERT INTO accounts (auth_subject) VALUES (?) RETURNING id"
                          (str "media-owner-" (UUID/randomUUID))]
                         query-options))
        handle (str "media_" (subs (str (UUID/randomUUID)) 0 8))
        character-id (str (:id (jdbc/execute-one!
                                datasource
                                ["INSERT INTO characters
                                    (account_id, handle, display_name, output_language,
                                     persona_public, persona_behavior)
                                  VALUES (?::uuid, ?, 'Media Cloud', 'en', ?::jsonb, ?::jsonb)
                                  RETURNING id"
                                 account-id handle (json/write-value public-persona)
                                 (json/write-value behavior-persona)]
                                query-options)))]
    {:account-id account-id :character-id character-id :handle handle}))

(defn temp-upload []
  (let [file (File/createTempFile "slopbook-media-integration-" ".bin")]
    (Files/write (.toPath file) (byte-array [1 2 3 4])
                 (make-array java.nio.file.OpenOption 0))
    {:tempfile file :size 4 :content-type "image/png"}))

(defn error-code [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo exception (:code (ex-data exception)))))

(deftest media-lifecycle-is-transactional-original-free-and-compensating
  (let [{:keys [account-id character-id]} (seed-owner!)
        identity {:store (postgres-characters/adapter datasource)
                  :new-id #(str (UUID/randomUUID)) :now #(str (Instant/now))}
        objects (atom {})
        deleted (atom [])
        ai-calls (atom [])
        processor {:validate! (fn [_] {:verified-type "png" :width 1200 :height 800
                                       :orientation 1})
                   :normalize! (fn [_ descriptor]
                                 {:verified-type (:verified-type descriptor)
                                  :width 1200 :height 800 :average-color "#112233"
                                  :variants [{:width 480 :height 320 :bytes (byte-array [4 8 0])}
                                             {:width 960 :height 640 :bytes (byte-array [9 6 0])}]
                                  :placeholder {:width 24 :height 16
                                                :bytes (byte-array [2 4])}})}
        storage {:put! (fn [{:keys [key bytes]}]
                         (swap! objects assoc key bytes)
                         {:key key :url (str "https://media.example.test/" key)})
                 :delete! (fn [keys]
                            (swap! deleted into keys)
                            (swap! objects #(apply dissoc % keys))
                            true)}
        ai {:rewrite! (fn [envelope]
                        (swap! ai-calls conj (:task_kind envelope))
                        {:status "ok" :text "Published with an image."
                         :output_language "en" :safety_reason "none"
                         :model_id "fixture/rewrite" :provider "fixture"})}
        media {:store (postgres-media/adapter datasource)
               :processor processor :storage storage :identity identity
               :transformation {:ai ai} :new-id #(str (UUID/randomUUID))
               :now #(str (Instant/now)) :executor-submit #(do (%) true)}
        upload! (fn [purpose]
                  (let [upload (temp-upload)
                        accepted (assets/upload!
                                  media account-id
                                  {"actor_character_id" character-id
                                   "purpose" purpose "output_language" "en"
                                   "file" upload})]
                    (is (not (.exists ^File (:tempfile upload))))
                    (assets/find! media account-id character-id (:id accepted))))]
    (testing "normalized variants only become ready; no filename or source object exists"
      (let [ready (upload! "post")
            variant-keys (set (clojure.core/keys @objects))]
        (is (= "ready" (:status ready)))
        (is (= "User-uploaded image" (:alt_text ready)))
        (is (empty? @ai-calls) "media preparation must not call an AI model")
        (is (= #{(str "media/" (:id ready) "/480.webp")
                 (str "media/" (:id ready) "/960.webp")
                 (str "media/" (:id ready) "/24.webp")}
               variant-keys))
        (is (= 2 (count (:variants ready))))
        (is (= 24 (get-in ready [:placeholder :width])))
        (is (not-any? #(.contains % "slopbook-media-integration")
                      (clojure.core/keys @objects)))

        (testing "post attachment and deletion move DB state atomically and purge variants"
          (let [post-deps {:store (postgres-posts/adapter datasource)
                           :identity identity :transformation {:ai ai} :media media
                           :cursor-secret "synthetic-media-cursor-secret"
                           :new-id #(str (UUID/randomUUID)) :now #(str (Instant/now))}
                post (posts/create! post-deps account-id "019f-media-post-0001"
                                    {:actor_character_id character-id
                                     :input_text "Publish with a safe image."
                                     :media_ids [(:id ready)]})]
            (is (= "attached" (:status
                               (jdbc/execute-one! datasource
                                                  ["SELECT status FROM media WHERE id = ?::uuid"
                                                   (:id ready)] query-options))))
            (is (= 1 (:amount
                      (jdbc/execute-one! datasource
                                         ["SELECT count(*)::int AS amount FROM content_media
                                            WHERE content_type = 'post' AND content_id = ?::uuid"
                                          (:id post)] query-options))))
            (is (true? (posts/delete! post-deps account-id (:id post)
                                      {:actor_character_id character-id})))
            (is (= "deleted" (:status
                              (jdbc/execute-one! datasource
                                                 ["SELECT status FROM media WHERE id = ?::uuid"
                                                  (:id ready)] query-options))))
            (is (empty? @objects))
            (is (= variant-keys (set @deleted)))))))

    (testing "avatar replacement and removal purge the previous immutable variants"
      (let [first-avatar (upload! "avatar")
            first-keys (set (filter #(.startsWith % (str "media/" (:id first-avatar) "/"))
                                    (clojure.core/keys @objects)))
            first-owned (avatars/update! identity media account-id character-id
                                         {:actor_character_id character-id
                                          :avatar_media_id (:id first-avatar)})
            second-avatar (upload! "avatar")
            second-keys (set (filter #(.startsWith % (str "media/" (:id second-avatar) "/"))
                                     (clojure.core/keys @objects)))
            second-owned (avatars/update! identity media account-id character-id
                                          {:actor_character_id character-id
                                           :avatar_media_id (:id second-avatar)})]
        (is (= "upload" (get-in first-owned [:public_character :avatar :kind])))
        (is (.contains (get-in second-owned [:public_character :avatar :url])
                       (:id second-avatar)))
        (is (= "deleted" (:status
                          (jdbc/execute-one! datasource
                                             ["SELECT status FROM media WHERE id = ?::uuid"
                                              (:id first-avatar)] query-options))))
        (is (every? #(not (contains? @objects %)) first-keys))
        (is (every? #(contains? @objects %) second-keys))
        (let [removed (avatars/update! identity media account-id character-id
                                       {:actor_character_id character-id
                                        :avatar_media_id nil})]
          (is (= "persona_sigil" (get-in removed [:public_character :avatar :kind])))
          (is (every? #(not (contains? @objects %)) second-keys)))

        (testing "character deletion purges its attached avatar"
          (let [final-avatar (upload! "avatar")
                final-keys (set (filter #(.startsWith % (str "media/" (:id final-avatar) "/"))
                                        (clojure.core/keys @objects)))]
            (avatars/update! identity media account-id character-id
                             {:actor_character_id character-id
                              :avatar_media_id (:id final-avatar)})
            (is (true? (avatars/delete! identity media account-id character-id
                                        {:actor_character_id character-id})))
            (is (= "deleted" (:status
                               (jdbc/execute-one! datasource
                                                  ["SELECT status FROM media WHERE id = ?::uuid"
                                                   (:id final-avatar)] query-options))))
            (is (every? #(not (contains? @objects %)) final-keys))))))))

(deftest expired-assets-and-storage-failures-use-the-durable-purge-queue
  (let [{:keys [character-id]} (seed-owner!)
        media-id (str (UUID/randomUUID))
        object-key (str "media/" media-id "/480.webp")
        objects (atom {object-key (byte-array [4 8 0])})
        fail-delete? (atom true)
        storage {:put! (fn [_] (throw (ex-info "unused" {})))
                 :delete! (fn [keys]
                            (when @fail-delete?
                              (throw (ex-info "synthetic object-store outage" {})))
                            (swap! objects #(apply dissoc % keys))
                            true)}
        media {:store (postgres-media/adapter datasource) :storage storage}]
    (jdbc/execute-one!
     datasource
     ["INSERT INTO media
         (id, owner_character_id, purpose, status, variants, expires_at)
       VALUES (?::uuid, ?::uuid, 'post', 'ready', ?::jsonb, now() - interval '1 second')"
      media-id character-id
      (json/write-value [{:key object-key :url (str "https://media.example.test/" object-key)
                          :width 480 :height 320}])]
     query-options)

    (testing "expiry is logical and a storage outage schedules exponential retry"
      (is (true? (assets/sweep! media)))
      (let [row (jdbc/execute-one!
                 datasource
                 ["SELECT status, variants, purge_attempt_count, purge_available_at,
                          purged_at, last_purge_error_code
                     FROM media WHERE id = ?::uuid" media-id]
                 query-options)]
        (is (= "deleted" (:status row)))
        (is (= 1 (:purge_attempt_count row)))
        (is (= "MEDIA_STORAGE_FAILED" (:last_purge_error_code row)))
        (is (some? (:variants row)))
        (is (nil? (:purged_at row)))
        (is (contains? @objects object-key))))

    (testing "a later sweep completes the idempotent physical purge"
      (reset! fail-delete? false)
      (jdbc/execute-one!
       datasource
       ["UPDATE media SET purge_available_at = now() WHERE id = ?::uuid" media-id]
       query-options)
      (is (true? (assets/sweep! media)))
      (let [row (jdbc/execute-one!
                 datasource
                 ["SELECT variants, purge_available_at, purged_at, last_purge_error_code
                     FROM media WHERE id = ?::uuid" media-id]
                 query-options)]
        (is (nil? (:variants row)))
        (is (nil? (:purge_available_at row)))
        (is (some? (:purged_at row)))
        (is (nil? (:last_purge_error_code row)))
        (is (not (contains? @objects object-key)))))))
