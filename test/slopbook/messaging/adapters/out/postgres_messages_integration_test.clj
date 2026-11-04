(ns slopbook.messaging.adapters.out.postgres-messages-integration-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [slopbook.identity.adapters.out.postgres :as postgres-characters]
            [slopbook.messaging.adapters.out.postgres :as postgres-messages]
            [slopbook.messaging.application.messages :as messages]
            [slopbook.messaging.ports.messages :as message-port]
            [slopbook.platform.json :as json]
            [slopbook.platform.migrations :as migrations]
            [slopbook.platform.schema-audit :as schema-audit])
  (:import [java.time Instant]
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
   :what_are_you [{:id "radio"}]
   :archetypes [{:id "messenger"}]
   :traits [{:id "warm"}]})

(def behavior-persona
  {:schema_version 1 :voice_tones [{:id "warm"}]
   :sentence_shape "mixed" :formality "neutral"
   :custom_rule "OWNER_ONLY_DM_CANARY"})

(defn account! [label]
  (:id (jdbc/execute-one!
        datasource
        ["INSERT INTO accounts (auth_subject) VALUES (?) RETURNING id"
         (str label "-" (UUID/randomUUID))]
        query-options)))

(defn character! [account-id prefix display-name]
  (let [handle (str prefix "_" (subs (str (UUID/randomUUID)) 0 8))
        id (str (:id (jdbc/execute-one!
                     datasource
                     ["INSERT INTO characters
                         (account_id, handle, display_name, output_language,
                          persona_public, persona_behavior)
                       VALUES (?::uuid, ?, ?, 'en', ?::jsonb, ?::jsonb) RETURNING id"
                      account-id handle display-name
                      (json/write-value public-persona) (json/write-value behavior-persona)]
                     query-options)))]
    {:id id :handle handle :display_name display-name :nationality nil
     :output_language "en" :persona_public public-persona :persona_version 1
     :human_owned true :created_at (str (Instant/now))}))

(defn error-code [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo exception (:code (ex-data exception)))))

(deftest canonical-pair-message-transaction-pagination-read-delete-and-block
  (let [sender-account (account! "dm-sender")
        receiver-account (account! "dm-receiver")
        outsider-account (account! "dm-outsider")
        sender (character! sender-account "sender" "Sender Signal")
        receiver (character! receiver-account "receiver" "Receiver Signal")
        outsider (character! outsider-account "outsider" "Outsider Signal")
        store (postgres-messages/adapter datasource)
        identity {:store (postgres-characters/adapter datasource)}
        ai-calls (atom 0)
        ai {:rewrite! (fn [_]
                        (swap! ai-calls inc)
                        {:status "ok" :text "Transformed private hello 73."
                         :output_language "en" :safety_reason "none"
                         :model_id "fixture/dm" :provider "fixture"
                         :latency_ms 1 :prompt_tokens 1 :completion_tokens 1})}
        deps {:store store :identity identity :transformation {:ai ai}
              :cursor-secret "synthetic-message-cursor-secret"
              :new-id #(str (UUID/randomUUID)) :now #(str (Instant/now))}
        create-command {:actor-character-id (:id sender)
                        :recipient-character-id (:id receiver)}
        outcomes (mapv deref
                       [(future (message-port/create-conversation! store create-command))
                        (future (message-port/create-conversation! store create-command))])
        conversation-id (:id (first outcomes))]
    (testing "opposite-order/concurrent creation converges on one canonical pair"
      (is (= 1 (count (set (map :id outcomes)))))
      (is (= #{true false} (set (map :created outcomes))))
      (is (= 1 (:amount
                (jdbc/execute-one!
                 datasource
                 ["SELECT count(*)::int AS amount FROM conversations
                    WHERE id = ?::uuid" conversation-id]
                 query-options)))))

    (testing "only transformed text and a public persona snapshot commit with notification/outbox"
      (let [raw "PRIVATE_DM_RAW_CANARY_73"
            sent (messages/send! deps sender-account "019f-dm-integration-0001"
                                 conversation-id
                                 {:actor_character_id (:id sender) :input_text raw})
            stored (jdbc/execute-one!
                    datasource
                    ["SELECT text, persona_snapshot::text AS persona_snapshot
                        FROM messages WHERE id = ?::uuid" (:id sent)]
                    query-options)
            envelopes (jdbc/execute!
                       datasource
                       ["SELECT event_type FROM outbox_events
                          WHERE aggregate_id = ?::uuid OR target_character_id = ?::uuid"
                        (:id sent) (:id receiver)] query-options)]
        (is (= "Transformed private hello 73." (:text stored)))
        (is (not (.contains (pr-str stored) raw)))
        (is (not (.contains (:persona_snapshot stored) "OWNER_ONLY_DM_CANARY")))
        (is (not (.contains (pr-str envelopes) raw)))
        (is (= 1 @ai-calls))
        (is (= 1 (:amount
                  (jdbc/execute-one!
                   datasource
                   ["SELECT count(*)::int AS amount FROM notifications
                      WHERE type = 'dm' AND resource_id = ?::uuid" (:id sent)]
                   query-options))))

        (testing "21-message keyset pages have no duplicate or omission"
          (let [extra-ids
                (mapv (fn [index]
                        (str (:id
                              (jdbc/execute-one!
                               datasource
                               ["INSERT INTO messages
                                   (conversation_id, sender_character_id, text, output_language,
                                    persona_snapshot, created_at)
                                 VALUES (?::uuid, ?::uuid, ?, 'en', ?::jsonb,
                                         now() + (? * interval '1 millisecond')) RETURNING id"
                                conversation-id (:id sender) (str "Page message " index)
                                (json/write-value sender) (inc index)]
                               query-options))))
                      (range 20))
                first-page (messages/detail! deps sender-account conversation-id (:id sender) nil)
                cursor (get-in first-page [:messages :next_cursor])
                second-page (messages/detail! deps sender-account conversation-id (:id sender) cursor)
                returned (concat (map :id (get-in first-page [:messages :items]))
                                 (map :id (get-in second-page [:messages :items])))]
            (is (= 20 (count (get-in first-page [:messages :items]))))
            (is (= 1 (count (get-in second-page [:messages :items]))))
            (is (= (set (conj extra-ids (:id sent))) (set returned)))
            (is (= 21 (count (distinct returned))))
            (is (= "CURSOR_INVALID"
                   (error-code #(messages/detail! deps receiver-account conversation-id
                                                  (:id receiver) cursor))))

            (testing "read watermark never moves backwards"
              (let [newest (last (get-in first-page [:messages :items]))
                    oldest (first (get-in second-page [:messages :items]))
                    advanced (messages/mark-read!
                              deps receiver-account conversation-id
                              {:actor_character_id (:id receiver)
                               :last_read_message_id (:id newest)})
                    attempted-regression (messages/mark-read!
                                          deps receiver-account conversation-id
                                          {:actor_character_id (:id receiver)
                                           :last_read_message_id (:id oldest)})]
                (is (= (:id newest) (:last_read_message_id advanced)))
                (is (= (:id newest) (:last_read_message_id attempted-regression)))))

            (testing "delete-for-me is sender-only and does not recall receiver history"
              (is (true? (messages/delete-for-me!
                          deps sender-account (:id sent)
                          {:actor_character_id (:id sender)})))
              (is (= "MESSAGE_NOT_FOUND"
                     (error-code #(messages/delete-for-me!
                                   deps receiver-account (:id sent)
                                   {:actor_character_id (:id receiver)}))))
              (is (not-any? #(= (:id sent) (:id %))
                            (message-port/messages
                             store {:conversation-id conversation-id
                                    :actor-character-id (:id sender) :limit 100})))
              (is (some #(= (:id sent) (:id %))
                        (message-port/messages
                         store {:conversation-id conversation-id
                                :actor-character-id (:id receiver) :limit 100}))))))))

    (testing "non-member gets private 404 and account block hides/stops the conversation"
      (is (= "CONVERSATION_NOT_FOUND"
             (error-code #(messages/detail! deps outsider-account conversation-id
                                            (:id outsider) nil))))
      (jdbc/execute-one!
       datasource
       ["INSERT INTO blocks (blocker_account_id, blocked_account_id, created_by_character_id)
         VALUES (?::uuid, ?::uuid, ?::uuid)"
        sender-account receiver-account (:id sender)])
      (is (empty? (:items (messages/list! deps sender-account (:id sender) nil))))
      (is (= "CONVERSATION_NOT_FOUND"
             (error-code #(messages/send! deps sender-account "019f-dm-integration-0002"
                                          conversation-id
                                          {:actor_character_id (:id sender)
                                           :input_text "This must not reach AI"}))))
      (is (= 1 @ai-calls)))))
