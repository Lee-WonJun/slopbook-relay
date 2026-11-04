(ns slopbook.social.adapters.out.postgres-social-integration-test
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [slopbook.identity.adapters.out.postgres :as postgres-characters]
            [slopbook.platform.json :as json]
            [slopbook.platform.migrations :as migrations]
            [slopbook.platform.schema-audit :as schema-audit]
            [slopbook.publishing.adapters.out.postgres :as postgres-posts]
            [slopbook.publishing.adapters.out.postgres-comments :as postgres-comments]
            [slopbook.publishing.application.comments :as comments]
            [slopbook.publishing.application.posts :as posts]
            [slopbook.social.adapters.out.postgres-discovery :as postgres-discovery]
            [slopbook.social.adapters.out.postgres-notifications :as postgres-notifications]
            [slopbook.social.adapters.out.postgres-reactions :as postgres-reactions]
            [slopbook.social.adapters.out.postgres-reposts :as postgres-reposts]
            [slopbook.social.application.discovery :as discovery]
            [slopbook.social.application.notifications :as notifications]
            [slopbook.social.application.reposts :as reposts]
            [slopbook.social.ports.discovery :as discovery-port]
            [slopbook.social.ports.reactions :as reaction-port])
  (:import [java.time Instant]
           [java.util UUID]
           [java.util.concurrent CountDownLatch TimeUnit]))

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
   :what_are_you [{:id "signal"}]
   :archetypes [{:id "observer"}]
   :traits [{:id "calm"}]})

(def behavior-persona
  {:schema_version 1 :voice_tones [{:id "calm"}]
   :sentence_shape "short" :formality "neutral"
   :custom_rule "OWNER_ONLY_SOCIAL_CANARY"})

(defn insert-account! [label]
  (:id (jdbc/execute-one!
        datasource
        ["INSERT INTO accounts (auth_subject) VALUES (?) RETURNING id"
         (str label "-" (UUID/randomUUID))]
        query-options)))

(defn insert-character! [account-id handle display-name]
  (str (:id (jdbc/execute-one!
             datasource
             ["INSERT INTO characters
                 (account_id, handle, display_name, output_language,
                  persona_public, persona_behavior)
               VALUES (?::uuid, ?, ?, 'en', ?::jsonb, ?::jsonb) RETURNING id"
              account-id handle display-name
              (json/write-value public-persona) (json/write-value behavior-persona)]
             query-options))))

(defn snapshot [id handle display-name]
  {:id id :handle handle :display_name display-name :nationality nil
   :output_language "en" :persona_public public-persona :persona_version 1
   :human_owned true :created_at "2026-07-16T00:00:00Z"})

(defn insert-post! [character-id handle display-name text language]
  (str (:id (jdbc/execute-one!
             datasource
             ["INSERT INTO posts
                 (character_id, text, output_language, persona_snapshot)
               VALUES (?::uuid, ?, ?, ?::jsonb) RETURNING id"
              character-id text language
              (json/write-value (assoc (snapshot character-id handle display-name)
                                       :output_language language))]
             query-options))))

(defn error-code [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo exception (:code (ex-data exception)))))

(def ai-run
  {:task_kind "reaction.choose" :model_id "fixture/reaction" :provider_class "fixture"
   :prompt_version 1 :schema_version 1 :result_code "ok" :latency_ms 1
   :prompt_tokens 1 :completion_tokens 1})

(deftest persona-reposts-toggle-once-notify-once-and-resurface-in-feed
  (let [actor-account (insert-account! "repost-actor")
        author-account (insert-account! "repost-author")
        suffix (subs (str (UUID/randomUUID)) 0 8)
        actor-handle (str "relay_" suffix)
        author-handle (str "source_" suffix)
        actor-id (insert-character! actor-account actor-handle "Relay Persona")
        author-id (insert-character! author-account author-handle "Source Persona")
        target-id (insert-post! author-id author-handle "Source Persona" "Repost source" "en")
        own-post-id (insert-post! actor-id actor-handle "Relay Persona" "Own source" "en")
        identity {:store (postgres-characters/adapter datasource)}
        social {:reposts (postgres-reposts/adapter datasource) :identity identity}
        publishing {:store (postgres-posts/adapter datasource) :identity identity
                    :cursor-secret "synthetic-repost-feed-secret"
                    :now #(str (Instant/now))}
        body {:actor_character_id actor-id}
        added (reposts/toggle! social actor-account target-id body)
        removed (reposts/toggle! social actor-account target-id body)
        restored (reposts/toggle! social actor-account target-id body)
        feed (posts/feed publishing "latest" actor-account actor-id)]
    (is (= {:active true :restored false :repost_count 1} added))
    (is (= {:active false :restored false :repost_count 0} removed))
    (is (= {:active true :restored true :repost_count 1} restored))
    (is (= 1 (:amount
              (jdbc/execute-one!
               datasource
               ["SELECT count(*)::int AS amount FROM notifications
                  WHERE type = 'repost' AND resource_id = ?::uuid" target-id]
               query-options))))
    (let [item (some #(when (= target-id (:id %)) %) (:items feed))]
      (is (= 1 (:repost_count item)))
      (is (true? (get-in item [:viewer_repost :active])))
      (is (= actor-id (get-in item [:repost_context :character :id]))))
    (is (= "REPOST_OWN_POST"
           (error-code #(reposts/toggle! social actor-account own-post-id body))))))

(deftest discovery-follow-search-block-notifications-and-reactions-use-real-postgres
  (let [actor-account (insert-account! "social-actor")
        target-account (insert-account! "social-target")
        actor-id (insert-character! actor-account (str "actor_" (subs (str (UUID/randomUUID)) 0 8))
                                    "Actor Signal")
        target-handle (str "exact_" (subs (str (UUID/randomUUID)) 0 8))
        target-id (insert-character! target-account target-handle "Exact Target")
        post-id (insert-post! target-id target-handle "Exact Target" "서울의 비가 잔잔합니다" "ko")
        reaction-model-id (str "fixture/reaction-" (UUID/randomUUID))
        discovery-store (postgres-discovery/adapter datasource)
        notification-store (postgres-notifications/adapter datasource)
        reaction-store (postgres-reactions/adapter datasource)
        identity {:store (postgres-characters/adapter datasource)}
        social {:discovery discovery-store :notifications notification-store
                :identity identity :cursor-secret "synthetic-social-cursor-secret"
                :now #(str (Instant/now))}]
    (testing "follow is idempotent and notification/outbox are created only once"
      (is (true? (:created (discovery-port/follow!
                            discovery-store {:actor-character-id actor-id
                                             :target-character-id target-id}))))
      (is (false? (:created (discovery-port/follow!
                             discovery-store {:actor-character-id actor-id
                                              :target-character-id target-id}))))
      (is (= 1 (:amount
                (jdbc/execute-one! datasource
                                   ["SELECT count(*)::int AS amount FROM follows
                                      WHERE follower_character_id = ?::uuid
                                        AND followee_character_id = ?::uuid"
                                    actor-id target-id] query-options))))
      (is (= 1 (:amount
                (jdbc/execute-one! datasource
                                   ["SELECT count(*)::int AS amount FROM notifications
                                      WHERE target_character_id = ?::uuid AND type = 'follow'
                                        AND actor_character_id = ?::uuid"
                                    target-id actor-id] query-options))))
      (is (= [target-id]
             (mapv :id (:items (discovery/connections
                                social :following actor-id actor-id nil))))))

    (testing "exact handle and multilingual transformed post search work"
      (let [character-result (discovery/search social target-handle "character" actor-id nil)
            post-result (discovery/search social "서울" "post" actor-id nil)]
        (is (= target-id (get-in character-result [:items 0 :id])))
        (is (= post-id (get-in post-result [:items 0 :id])))
        (is (not (.contains (pr-str character-result) "OWNER_ONLY_SOCIAL_CANARY")))))

    (testing "a reaction decision is persisted once and subsequent calls toggle/restore"
      (let [calls (atom 0)
            target (reaction-port/target-public reaction-store
                                                {:type "post" :id post-id
                                                 :actor-character-id actor-id})
            decide #(do (swap! calls inc)
                        {:reaction "like"
                         :ai-run (assoc ai-run :model_id reaction-model-id)})
            first-result (reaction-port/toggle!
                          reaction-store {:type "post" :target target
                                          :actor-character-id actor-id :decide decide})
            second-result (reaction-port/toggle!
                           reaction-store {:type "post" :target target
                                           :actor-character-id actor-id :decide decide})
            third-result (reaction-port/toggle!
                          reaction-store {:type "post" :target target
                                          :actor-character-id actor-id :decide decide})]
        (is (= {:reaction "like" :active true :restored false}
               (select-keys first-result [:reaction :active :restored])))
        (is (false? (:active second-result)))
        (is (and (:active third-result) (:restored third-result)))
        (is (= 1 @calls))
        (is (= 1 (:amount
                  (jdbc/execute-one! datasource
                                     ["SELECT count(*)::int AS amount FROM ai_runs
                                        WHERE model_id = ?" reaction-model-id]
                                     query-options))))))

    (testing "notification pages are signed, resource-bound, readable, and tickets are one-shot"
      (doseq [index (range 21)]
        (jdbc/execute-one!
         datasource
         ["INSERT INTO notifications
             (target_character_id, actor_character_id, type, resource_type, resource_id, created_at)
           VALUES (?::uuid, ?::uuid, 'follow', 'character', ?::uuid,
                   now() + (? * interval '1 millisecond'))"
          actor-id target-id target-id index]))
      (let [first-page (notifications/list! social actor-account actor-id nil)
            cursor (:next_cursor first-page)
            second-page (notifications/list! social actor-account actor-id cursor)
            ticket (notifications/issue-ticket! social actor-account
                                                {:actor_character_id actor-id})]
        (is (= 20 (count (:items first-page))))
        (is (string? cursor))
        (is (<= 1 (count (:items second-page))))
        (is (= "CURSOR_INVALID"
               (error-code #(discovery/search social "서울" "post" actor-id cursor))))
        (is (= actor-id (notifications/consume-ticket! social (:ticket ticket))))
        (is (= "WEBSOCKET_AUTH_REQUIRED"
               (error-code #(notifications/consume-ticket! social (:ticket ticket)))))))

    (testing "account block removes follow/search/notification exposure in both directions"
      (jdbc/execute-one!
       datasource
       ["INSERT INTO blocks (blocker_account_id, blocked_account_id, created_by_character_id)
         VALUES (?::uuid, ?::uuid, ?::uuid)"
        actor-account target-account actor-id])
      (is (empty? (:items (discovery/connections social :following actor-id actor-id nil))))
      (is (not-any? #(= target-id (:id %))
                    (:items (discovery/search social target-handle "character" actor-id nil))))
      (is (not-any? #(= post-id (:id %))
                    (:items (discovery/search social "서울" "post" actor-id nil))))
      (is (nil? (discovery-port/follow!
                 discovery-store {:actor-character-id actor-id
                                  :target-character-id target-id})))
      (is (empty? (:items (notifications/list! social actor-account actor-id nil)))))))

(deftest character-search-cursor-has-no-duplicates-and-is-query-bound
  (let [_cleanup (jdbc/execute! datasource
                                ["DELETE FROM characters
                                   WHERE handle LIKE 'cx%' OR handle LIKE 'cursorx_%'"])
        prefix (str "cx" (subs (str (UUID/randomUUID)) 0 6))
        actor-account (insert-account! "search-page-actor")
        result-account (insert-account! "search-page-results")
        actor-id (insert-character! actor-account (str "viewer_" (subs (str (UUID/randomUUID)) 0 8))
                                    "Search Viewer")
        expected (mapv (fn [index]
                         (insert-character! result-account
                                            (format "%s_%02d" prefix index)
                                            (str "Cursor Result " index)))
                       (range 23))
        social {:discovery (postgres-discovery/adapter datasource)
                :cursor-secret "synthetic-search-page-secret"}
        first-page (discovery/search social prefix "character" actor-id nil)
        returned (loop [page first-page values []]
                   (let [values (into values (map :id) (:items page))]
                     (if-let [cursor (:next_cursor page)]
                       (recur (discovery/search social prefix "character" actor-id cursor) values)
                       values)))]
    (is (= 20 (count (:items first-page))))
    (is (set/subset? (set expected) (set returned)))
    (is (= (count returned) (count (distinct returned))))
    (is (= "CURSOR_INVALID"
           (error-code #(discovery/search social "different" "character" actor-id
                                          (:next_cursor first-page)))))))

(deftest blocks-hide-comment-and-reaction-aggregates-from-every-public-projection
  (let [viewer-account (insert-account! "privacy-viewer")
        author-account (insert-account! "privacy-author")
        blocked-account (insert-account! "privacy-blocked-reactor")
        visible-account (insert-account! "privacy-visible-reactor")
        suffix (subs (str (UUID/randomUUID)) 0 6)
        viewer-id (insert-character! viewer-account (str "pv_" suffix) "Privacy Viewer")
        author-id (insert-character! author-account (str "pa_" suffix) "Privacy Author")
        blocked-id (insert-character! blocked-account (str "pb_" suffix) "Blocked Reactor")
        visible-id (insert-character! visible-account (str "px_" suffix) "Visible Reactor")
        phrase (str "privacyaggregate" suffix)
        post-id (insert-post! author-id (str "pa_" suffix) "Privacy Author" phrase "en")
        visible-comment-id (str (UUID/randomUUID))
        blocked-comment-id (str (UUID/randomUUID))
        post-store (postgres-posts/adapter datasource)
        comment-store (postgres-comments/adapter datasource)
        identity {:store (postgres-characters/adapter datasource)}
        publishing {:store post-store :comments comment-store :identity identity
                    :cursor-secret "synthetic-privacy-projection-secret"}
        social {:discovery (postgres-discovery/adapter datasource)
                :cursor-secret "synthetic-privacy-search-secret"}]
    (doseq [reactor-id [viewer-id blocked-id visible-id]]
      (jdbc/execute-one!
       datasource
       ["INSERT INTO post_reactions (target_id, character_id, reaction, active)
         VALUES (?::uuid, ?::uuid, 'like', true)" post-id reactor-id]
       query-options))
    (doseq [[comment-id actor-id handle name text]
            [[visible-comment-id visible-id (str "px_" suffix) "Visible Reactor" "visible comment"]
             [blocked-comment-id blocked-id (str "pb_" suffix) "Blocked Reactor" "blocked comment"]]]
      (jdbc/execute-one!
       datasource
       ["INSERT INTO comments
           (id, post_id, thread_root_id, character_id, text, output_language, persona_snapshot)
         VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?, 'en', ?::jsonb)"
        comment-id post-id comment-id actor-id text
        (json/write-value (snapshot actor-id handle name))]
       query-options))
    (doseq [reactor-id [blocked-id visible-id]]
      (jdbc/execute-one!
       datasource
       ["INSERT INTO comment_reactions (target_id, character_id, reaction, active)
         VALUES (?::uuid, ?::uuid, 'like', true)" visible-comment-id reactor-id]
       query-options))
    (jdbc/execute-one!
     datasource
     ["INSERT INTO blocks (blocker_account_id, blocked_account_id, created_by_character_id)
       VALUES (?::uuid, ?::uuid, ?::uuid)" viewer-account blocked-account viewer-id]
     query-options)

    (testing "post detail and post search expose only visible aggregate contributors"
      (let [detail (posts/find-post publishing viewer-account viewer-id post-id)
            result (first (:items (discovery/search social phrase "post" viewer-id nil)))]
        (is (= 1 (:comment_count detail)))
        (is (= 2 (get-in detail [:reaction_counts :like])))
        (is (= 1 (:comment_count result)))
        (is (= 2 (get-in result [:reaction_counts :like])))))

    (testing "comment thread hides the blocked comment and its reaction contribution"
      (let [thread (comments/thread publishing viewer-account post-id viewer-id nil)
            root (get-in thread [:items 0 :root])]
        (is (= [visible-comment-id] (mapv #(get-in % [:root :id]) (:items thread))))
        (is (= 1 (get-in root [:reaction_counts :like])))
        (is (not (.contains (pr-str thread) "blocked comment")))))

    (testing "reaction mutation returns viewer-filtered totals too"
      (let [store (postgres-reactions/adapter datasource)
            target (reaction-port/target-public
                    store {:type "post" :id post-id :actor-character-id viewer-id})
            toggled (reaction-port/toggle!
                     store {:type "post" :target target :actor-character-id viewer-id
                            :decide #(throw (ex-info "existing reaction must not call AI" {}))})]
        (is (false? (:active toggled)))
        (is (= 1 (get-in toggled [:counts :like])))))))
