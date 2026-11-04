(ns slopbook.publishing.adapters.out.postgres-posts-integration-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as result-set]
            [slopbook.identity.adapters.out.postgres :as postgres-characters]
            [slopbook.identity.adapters.out.postgres-accounts :as postgres-accounts]
            [slopbook.identity.application.characters :as characters]
            [slopbook.platform.cursor :as cursor]
            [slopbook.platform.json :as json]
            [slopbook.platform.migrations :as migrations]
            [slopbook.platform.schema-audit :as schema-audit]
            [slopbook.publishing.adapters.out.postgres :as postgres-posts]
            [slopbook.publishing.application.greetings :as greetings]
            [slopbook.publishing.application.posts :as posts])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.time Instant]
           [java.util UUID]
           [java.util.concurrent CountDownLatch TimeUnit]))

(def database-url
  (or (System/getenv "DATABASE_URL")
      "jdbc:postgresql://127.0.0.1:15432/slopbook?user=slopbook&password=local-slopbook-only"))

(def datasource (schema-audit/datasource database-url))
(def query-options {:builder-fn result-set/as-unqualified-lower-maps})

(use-fixtures :once
  (fn [test-fn]
    (migrations/migrate! {:database-url database-url})
    (test-fn)))

(def character-draft
  {:handle "post_writer_fixture"
   :display_name "Post Writer"
   :nationality nil
   :output_language "en"
   :persona_public {:schema_version 1 :catalog_version 1
                    :what_are_you [{:id "fox"}]
                    :archetypes [{:id "archivist"}]
                    :traits [{:id "warm"} {:id "curious"} {:id "meticulous"}]}
   :persona_behavior {:schema_version 1 :voice_tones [{:id "warm"}]
                      :sentence_shape "short" :formality "neutral"
                      :custom_rule "OWNER_ONLY_POST_CANARY"}})

(defn error-code [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo exception (:code (ex-data exception)))))

(defn sha256-hex [value]
  (let [bytes (.digest (MessageDigest/getInstance "SHA-256")
                       (.getBytes value StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and 0xff %)) bytes))))

(defn ensure-character! [identity account-id handle]
  (or (some #(when (= handle (get-in % [:public_character :handle])) %)
            ((:list-owned (:store identity)) account-id))
      (characters/create! identity account-id (assoc character-draft :handle handle))))

(defn insert-feed-account! [label]
  (:id (jdbc/execute-one!
        datasource
        ["INSERT INTO accounts (auth_subject) VALUES (?) RETURNING id"
         (str label "-" (UUID/randomUUID))]
        query-options)))

(defn insert-feed-character! [account-id label]
  (let [prefix (subs label 0 (min 10 (count label)))
        handle (str prefix "_" (subs (str (UUID/randomUUID)) 0 8))
        public (:persona_public character-draft)]
    {:id (str (:id (jdbc/execute-one!
                    datasource
                    ["INSERT INTO characters
                        (account_id, handle, display_name, output_language,
                         persona_public, persona_behavior)
                      VALUES (?::uuid, ?, ?, 'en', ?::jsonb, ?::jsonb) RETURNING id"
                     account-id handle label (json/write-value public)
                     (json/write-value (:persona_behavior character-draft))]
                    query-options)))
     :handle handle
     :display-name label
     :public public}))

(defn insert-feed-post! [character text published-at]
  (str (:id (jdbc/execute-one!
             datasource
             ["INSERT INTO posts
                 (character_id, text, output_language, persona_snapshot, published_at, created_at)
               VALUES (?::uuid, ?, 'en', ?::jsonb, ?::timestamptz, ?::timestamptz)
               RETURNING id"
              (:id character) text
              (json/write-value {:id (:id character) :handle (:handle character)
                                 :display_name (:display-name character)
                                 :nationality nil :output_language "en"
                                 :persona_public (:public character) :persona_version 1
                                 :human_owned true :created_at published-at})
              published-at published-at]
             query-options))))

(deftest transactional-post-idempotency-privacy-and-delete
  (jdbc/execute! datasource ["DELETE FROM idempotency_keys WHERE route_name = 'posts.create'"])
  (let [account-store (postgres-accounts/adapter datasource)
        account ((:find-or-create! account-store) "post-integration-subject")
        character-store (postgres-characters/adapter datasource)
        identity {:store character-store :new-id #(str (UUID/randomUUID))
                  :now #(str (java.time.Instant/now))}
        owned (ensure-character! identity (:id account) (:handle character-draft))
        character-id (get-in owned [:public_character :id])
        post-store (postgres-posts/adapter datasource)
        calls (atom 0)
        model-id (str "synthetic/post-model-" (UUID/randomUUID))
        ai {:rewrite! (fn [envelope]
                        (swap! calls inc)
                        {:status "ok" :text "Transformed signal 42."
                         :output_language "en" :safety_reason "none"
                         :model_id model-id :provider "synthetic"
                         :latency_ms 4 :prompt_tokens 5 :completion_tokens 6})}
        deps {:store post-store :identity identity :transformation {:ai ai}
              :new-id #(str (UUID/randomUUID)) :now #(str (java.time.Instant/now))}
        key "019f-post-idempotency-0001"
        raw "PRIVATE_POST_RAW_CANARY 42"
        first-post (posts/create! deps (:id account) key
                                  {:actor_character_id character-id :input_text raw :media_ids []})
        second-post (posts/create! deps (:id account) key
                                   {:actor_character_id character-id
                                    :input_text "DIFFERENT_PRIVATE_RAW 42" :media_ids []})]
    (is (= (:id first-post) (:id second-post)))
    (is (= 1 @calls))
    (is (= "CONTENT_GRAPHEME_LIMIT"
           (error-code #(posts/create! deps (:id account) "019f-post-boundary-0002"
                                       {:actor_character_id character-id
                                        :input_text (apply str (repeat 281 "가"))
                                        :media_ids []}))))
    (is (= 1 @calls) "281 graphemes fail before AI")
    (let [stored (jdbc/execute-one!
                  datasource
                  ["SELECT text, persona_snapshot::text AS persona_snapshot,
                           encode(k.metadata_fingerprint, 'hex') AS fingerprint
                      FROM posts p
                      JOIN idempotency_keys k ON k.resource_id = p.id
                     WHERE p.id = ?::uuid" (:id first-post)]
                  query-options)
          rendered (pr-str stored)]
      (is (= "Transformed signal 42." (:text stored)))
      (is (not (.contains rendered raw)))
      (is (not (.contains rendered "OWNER_ONLY_POST_CANARY")))
      (is (not= (sha256-hex raw) (:fingerprint stored))))
    (is (= 1 (:run_count
              (jdbc/execute-one! datasource
                                 ["SELECT count(*)::int AS run_count FROM ai_runs
                                    WHERE model_id = ?" model-id]
                                 query-options))))
    (is (= 1 (:event_count
              (jdbc/execute-one! datasource
                                 ["SELECT count(*)::int AS event_count FROM outbox_events
                                    WHERE event_type = 'post.published' AND aggregate_id = ?::uuid"
                                  (:id first-post)]
                                 query-options))))
    (is (true? (posts/delete! deps (:id account) (:id first-post)
                              {:actor_character_id character-id})))
    (is (= "POST_NOT_FOUND" (error-code #(posts/find-post deps (:id first-post)))))
    (is (= {:status "deleted" :text nil}
           (jdbc/execute-one! datasource
                              ["SELECT status, text FROM posts WHERE id = ?::uuid" (:id first-post)]
                              query-options)))))

(deftest character-greeting-preview-publishes-exactly-once-in-postgres
  (let [suffix (subs (str (UUID/randomUUID)) 0 8)
        account-store (postgres-accounts/adapter datasource)
        account ((:find-or-create! account-store) (str "greeting-integration-" suffix))
        character-store (postgres-characters/adapter datasource)
        identity {:store character-store :new-id #(str (UUID/randomUUID))
                  :now #(str (Instant/now))}
        draft (assoc character-draft
                     :handle (str "greeting_" suffix)
                     :display_name "Greeting Comet")
        model-id (str "synthetic/greeting-" suffix)
        ai {:rewrite! (constantly {:status "ok"
                                   :text "Hello from the exact preview."
                                   :output_language "en"
                                   :safety_reason "none"
                                   :model_id model-id
                                   :provider "synthetic"})}
        deps {:store (postgres-posts/adapter datasource)
              :identity identity
              :transformation {:ai ai}
              :cursor-secret (str "integration-greeting-secret-" suffix)
              :new-id #(str (UUID/randomUUID))
              :now #(str (Instant/now))}
        preview (greetings/preview! deps (:id account) draft)
        owned (characters/create! identity (:id account) draft)
        actor-id (get-in owned [:public_character :id])
        body {:actor_character_id actor-id :preview_token (:preview_token preview)}
        first-post (greetings/publish! deps (:id account) body)
        retry-post (greetings/publish! deps (:id account) body)
        counts (jdbc/execute-one!
                datasource
                ["SELECT
                    (SELECT count(*)::int FROM posts WHERE character_id = ?::uuid) AS posts,
                    (SELECT count(*)::int FROM ai_runs WHERE model_id = ?) AS runs,
                    (SELECT count(*)::int FROM idempotency_keys
                      WHERE route_name = 'character-greetings.publish'
                        AND resource_id = ?::uuid) AS reservations"
                 actor-id model-id (:id first-post)]
                query-options)]
    (is (= (:text preview) (:text first-post)))
    (is (= (:id first-post) (:id retry-post)))
    (is (= {:posts 1 :runs 1 :reservations 1} counts))))

(deftest concurrent-same-key-runs-ai-once
  (jdbc/execute! datasource ["DELETE FROM idempotency_keys WHERE idempotency_key = '019f-post-concurrent-0003'"])
  (let [account-store (postgres-accounts/adapter datasource)
        account ((:find-or-create! account-store) "post-integration-subject")
        character-store (postgres-characters/adapter datasource)
        identity {:store character-store :new-id #(str (UUID/randomUUID))
                  :now #(str (java.time.Instant/now))}
        actor (ensure-character! identity (:id account) "post_concurrent")
        character-id (get-in actor [:public_character :id])
        post-store (postgres-posts/adapter datasource)
        entered (CountDownLatch. 1)
        release (CountDownLatch. 1)
        calls (atom 0)
        ai {:rewrite! (fn [_]
                        (swap! calls inc)
                        (.countDown entered)
                        (.await release 3 TimeUnit/SECONDS)
                        {:status "ok" :text "Concurrent 7."
                         :output_language "en" :safety_reason "none"
                         :model_id "synthetic/concurrent" :provider "synthetic"})}
        deps {:store post-store :identity identity :transformation {:ai ai}
              :new-id #(str (UUID/randomUUID)) :now #(str (java.time.Instant/now))}
        input {:actor_character_id character-id :input_text "Concurrent raw 7" :media_ids []}
        first-call (future (posts/create! deps (:id account) "019f-post-concurrent-0003" input))]
    (is (.await entered 2 TimeUnit/SECONDS))
    (is (= "REQUEST_IN_PROGRESS"
           (error-code #(posts/create! deps (:id account) "019f-post-concurrent-0003" input))))
    (.countDown release)
    (is (string? (:id @first-call)))
    (is (= 1 @calls))))

(deftest transaction-failure-retry-does-not-persist-raw-or-duplicate
  (let [key "019f-post-db-retry-0004"
        _ (jdbc/execute! datasource ["DELETE FROM idempotency_keys WHERE idempotency_key = ?" key])
        account-store (postgres-accounts/adapter datasource)
        account ((:find-or-create! account-store) "post-integration-subject")
        character-store (postgres-characters/adapter datasource)
        identity {:store character-store :new-id #(str (UUID/randomUUID))
                  :now #(str (java.time.Instant/now))}
        actor (ensure-character! identity (:id account) "post_retry")
        character-id (get-in actor [:public_character :id])
        base-store (postgres-posts/adapter datasource)
        fail-once (atom true)
        post-store (assoc base-store :publish!
                          (fn [publication]
                            (if (compare-and-set! fail-once true false)
                              (throw (ex-info "synthetic commit boundary failure" {}))
                              ((:publish! base-store) publication))))
        raw "DB_FAILURE_PRIVATE_CANARY 9"
        ai {:rewrite! (constantly {:status "ok" :text "Recovered signal 9."
                                   :output_language "en" :safety_reason "none"
                                   :model_id "synthetic/retry" :provider "synthetic"})}
        deps {:store post-store :identity identity :transformation {:ai ai}
              :new-id #(str (UUID/randomUUID)) :now #(str (java.time.Instant/now))}
        input {:actor_character_id character-id :input_text raw :media_ids []}]
    (is (thrown? clojure.lang.ExceptionInfo
                 (posts/create! deps (:id account) key input)))
    (let [post (posts/create! deps (:id account) key input)
          rows (jdbc/execute!
                datasource
                ["SELECT text FROM posts WHERE id = ?::uuid" (:id post)]
                query-options)]
      (is (= [{:text "Recovered signal 9."}] rows))
      (is (not (.contains (pr-str rows) raw))))))

(deftest signed-feed-cursors-are-snapshot-stable-ranked-and-block-aware-in-postgres
  (let [secret "synthetic-postgres-feed-cursor-secret"
        viewer-account (insert-feed-account! "feed-viewer")
        author-account (insert-feed-account! "feed-author")
        blocked-account (insert-feed-account! "feed-blocked")
        viewer (insert-feed-character! viewer-account "feed_viewer")
        author (insert-feed-character! author-account "feed_author")
        blocked-author (insert-feed-character! blocked-account "feed_blocked")
        published-at (str (.minusSeconds (Instant/now) 3600))
        expected (mapv #(insert-feed-post! author (str "Stable feed item " %) published-at)
                       (range 23))
        engaged-id (first expected)
        blocked-id (insert-feed-post! blocked-author "Blocked feed item" published-at)
        identity {:store (postgres-characters/adapter datasource)}
        deps {:store (postgres-posts/adapter datasource)
              :identity identity :cursor-secret secret :now #(str (Instant/now))}]
    (jdbc/execute-one!
     datasource
     ["INSERT INTO follows (follower_character_id, followee_character_id)
       VALUES (?::uuid, ?::uuid), (?::uuid, ?::uuid)"
      (:id viewer) (:id author) (:id viewer) (:id blocked-author)]
     query-options)
    (jdbc/execute-one!
     datasource
     ["INSERT INTO blocks (blocker_account_id, blocked_account_id, created_by_character_id)
       VALUES (?::uuid, ?::uuid, ?::uuid)"
      viewer-account blocked-account (:id viewer)]
     query-options)
    (jdbc/execute-one!
     datasource
     ["INSERT INTO post_reactions (target_id, character_id, reaction, active)
       VALUES (?::uuid, ?::uuid, 'like', true)"
      engaged-id (:id viewer)]
     query-options)

    (testing "latest/following keyset pagination freezes its as-of boundary"
      (let [first-page (posts/feed deps "following" viewer-account (:id viewer))
            first-cursor (:next_cursor first-page)
            boundary (cursor/decode secret first-cursor)
            late-id (insert-feed-post!
                     author "Inserted after page one"
                     (str (.plusMillis (Instant/parse (:as_of boundary)) 1)))
            returned (loop [page first-page values []]
                       (let [values (into values (map :id) (:items page))]
                         (if-let [encoded (:next_cursor page)]
                           (recur (posts/feed deps "following" viewer-account
                                              (:id viewer) encoded)
                                  values)
                           values)))]
        (is (= 20 (count (:items first-page))))
        (is (= (set expected) (set returned)))
        (is (= (count returned) (count (distinct returned))))
        (is (not (some #{late-id blocked-id} returned)))
        (is (= "CURSOR_INVALID"
               (error-code #(posts/feed deps "hot" viewer-account (:id viewer)
                                        first-cursor))))
        (is (= "CURSOR_INVALID"
               (error-code #(posts/feed deps "following" viewer-account (:id viewer)
                                        (str first-cursor "tampered")))))))

    (testing "hot mode uses engagement score, stable UUID ties, and scoped cursors"
      (let [first-page (posts/feed deps "hot" viewer-account (:id viewer))
            returned (loop [page first-page values []]
                       (let [values (into values (:items page))]
                         (if-let [encoded (:next_cursor page)]
                           (recur (posts/feed deps "hot" viewer-account (:id viewer) encoded)
                                  values)
                            values)))]
        (is (every? (set (map :id returned)) expected))
        (is (< (.indexOf (mapv :id returned) engaged-id)
               (apply min (map #(.indexOf (mapv :id returned) %)
                               (rest expected)))))
        (is (= {:reaction "like" :active true :restored false}
               (:viewer_reaction (some #(when (= engaged-id (:id %)) %) returned))))
        (is (= (count returned) (count (distinct (map :id returned)))))
        (is (= (sort #(compare %2 %1) (rest expected))
               (filter (set (rest expected)) (map :id returned))))
        (is (not (some #{blocked-id} (map :id returned))))))))
