(ns slopbook.publishing.adapters.out.postgres-comments-integration-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [slopbook.platform.json :as json]
            [slopbook.platform.migrations :as migrations]
            [slopbook.platform.schema-audit :as schema-audit]
            [slopbook.publishing.adapters.out.postgres-comments :as postgres-comments]
            [slopbook.publishing.domain.comment :as comment]
            [slopbook.publishing.ports.comments :as comments])
  (:import [java.util UUID]))

(def database-url
  (or (System/getenv "DATABASE_URL")
      "jdbc:postgresql://127.0.0.1:15432/slopbook?user=slopbook&password=local-slopbook-only"))

(def datasource (schema-audit/datasource database-url))
(def query-options {:builder-fn rs/as-unqualified-lower-maps})

(defn migrate-once [test-fn]
  (migrations/migrate! {:database-url database-url})
  (test-fn))

(use-fixtures :once migrate-once)

(def public-persona
  {:schema_version 1 :catalog_version 1
   :what_are_you [{:id "signal"}]
   :archetypes [{:id "observer"}]
   :traits [{:id "calm"}]})

(defn public-character [id handle name]
  {:id id :handle handle :display_name name :output_language "en"
   :nationality nil :persona_public public-persona :persona_version 1
   :human_owned true :avatar {:kind "sigil" :version 1 :seed (apply str (repeat 64 "a"))}})

(def ai-run
  {:task_kind "rewrite.comment" :model_id "fixture/model" :provider_class "fixture"
   :prompt_version 1 :schema_version 1 :result_code "ok" :latency_ms 1
   :prompt_tokens 1 :completion_tokens 1})

(defn seed! [account-id character-a character-b post-id]
  (jdbc/execute-one!
   datasource
   ["INSERT INTO accounts (id, auth_subject) VALUES (?::uuid, ?)"
    account-id (str "comments-" account-id)])
  (doseq [[id handle name] [[character-a (str "commenter_" (subs character-a 0 8)) "Commenter"]
                            [character-b (str "recipient_" (subs character-b 0 8)) "Recipient"]]]
    (jdbc/execute-one!
     datasource
     ["INSERT INTO characters
         (id, account_id, handle, display_name, output_language, persona_public, persona_behavior)
       VALUES (?::uuid, ?::uuid, ?, ?, 'en', ?::jsonb,
               '{\"voice_tones\":[{\"id\":\"calm\"}],\"sentence_shape\":\"short\",\"formality\":\"neutral\"}'::jsonb)"
      id account-id handle name (json/write-value public-persona)]))
  (jdbc/execute-one!
   datasource
   ["INSERT INTO posts (id, character_id, text, output_language, persona_snapshot)
     VALUES (?::uuid, ?::uuid, 'Published fixture', 'en', ?::jsonb)"
    post-id character-b
    (json/write-value (public-character character-b "recipient" "Recipient"))]))

(deftest postgres-comment-thread-notification-and-tombstone-are-atomic
  (let [account-id (str (UUID/randomUUID))
        character-a (str (UUID/randomUUID))
        character-b (str (UUID/randomUUID))
        post-id (str (UUID/randomUUID))
        root-id (str (UUID/randomUUID))
        reply-id (str (UUID/randomUUID))
        adapter (postgres-comments/adapter datasource)
        snapshot-a (public-character character-a "commenter" "Commenter")
        snapshot-b (public-character character-b "recipient" "Recipient")
        fingerprint-a (byte-array (repeat 32 (byte 1)))
        fingerprint-b (byte-array (repeat 32 (byte 2)))
        reservation-a {:account-id account-id :idempotency-key (str (UUID/randomUUID))
                       :fingerprint fingerprint-a}
        reservation-b {:account-id account-id :idempotency-key (str (UUID/randomUUID))
                       :fingerprint fingerprint-b}]
    (seed! account-id character-a character-b post-id)
    (is (= :claimed (:state (comments/reserve! adapter reservation-a))))
    (comments/publish!
     adapter
     {:reservation reservation-a
      :comment (comment/comment-value {:id root-id :post-id post-id :now "2026-07-16T00:00:00Z"
                                       :character snapshot-a :transformed-text "Root transformed"})
      :ai-run ai-run})
    (is (= :claimed (:state (comments/reserve! adapter reservation-b))))
    (comments/publish!
     adapter
     {:reservation reservation-b
      :comment (comment/comment-value {:id reply-id :post-id post-id :parent-id root-id
                                       :now "2026-07-16T00:00:01Z" :character snapshot-b
                                       :transformed-text "Reply transformed"})
      :ai-run ai-run})
    (let [thread (comments/thread adapter {:post-id post-id :limit 20})
          notifications (:amount (jdbc/execute-one!
                                  datasource
                                  ["SELECT count(*)::int AS amount FROM notifications
                                     WHERE resource_id IN (?::uuid, ?::uuid)" root-id reply-id]
                                  query-options))
          outbox-content (:amount (jdbc/execute-one!
                                   datasource
                                   ["SELECT count(*)::int AS amount FROM outbox_events
                                      WHERE aggregate_id IN (?::uuid, ?::uuid)
                                        AND (event_type LIKE '%Root transformed%'
                                             OR event_type LIKE '%Reply transformed%')"
                                    root-id reply-id]
                                   query-options))]
      (is (= 1 (count (:items thread))))
      (is (= root-id (get-in thread [:items 0 :root :id])))
      (is (= reply-id (get-in thread [:items 0 :replies 0 :id])))
      (is (= (:id snapshot-a) (get-in thread [:items 0 :replies 0 :reply_to :id])))
      (is (= 2 notifications))
      (is (zero? outbox-content)))
    (is (= {:tombstone true}
           (comments/delete! adapter {:comment-id root-id :character-id character-a})))
    (let [thread (comments/thread adapter {:post-id post-id :limit 20})]
      (is (= "deleted" (get-in thread [:items 0 :root :status])))
      (is (nil? (get-in thread [:items 0 :root :text]))))))
