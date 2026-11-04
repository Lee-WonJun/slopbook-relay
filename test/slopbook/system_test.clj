(ns slopbook.system-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [slopbook.messaging.application.messages :as messages]
            [slopbook.platform.cursor :as cursor]
            [slopbook.platform.json :as json]
            [slopbook.platform.migrations :as migrations]
            [slopbook.social.application.notifications :as notifications]
            [slopbook.system :as system])
  (:import [java.io ByteArrayInputStream]
           [java.nio.charset StandardCharsets]))

(defn deterministic-ai-result [envelope]
  (case (:task_kind envelope)
    "choose.reaction"
    {:reaction "like"
     :model_id "test/deterministic"
     :provider "test"}

    {:status "ok"
     :text (:input_text envelope)
     :output_language (get-in envelope [:persona :output_language])
     :safety_reason "none"
     :model_id "test/deterministic"
     :provider "test"}))

(def deterministic-ai-adapter
  {:rewrite! deterministic-ai-result
   :rewrite-with-model! (fn [_ envelope _] (deterministic-ai-result envelope))
   :eligible-models (constantly ["test/deterministic"])
   :eligible-models-for (fn [_] ["test/deterministic"])
   :credit-status (constantly :available)
   :available? (constantly true)
   :capability-snapshot (constantly {:probe_status "complete"
                                     :candidates [{:model_id "test/deterministic"}]})
   :refresh-capabilities! (constantly true)})

(def test-config
  {:environment "test"
   :development? true
   :port 0
   :public-base-url "http://localhost"
   :dev-auth? true
   :store :memory
   :database-url nil
   :db-pool-max 2
   :cursor-secret "synthetic-test-cursor-secret"
   :supabase-url "https://auth.example.test"
   :supabase-publishable-key "synthetic-publishable-key"
   :supabase-jwks-url "https://auth.example.test/.well-known/jwks.json"
   :supabase-issuer "https://auth.example.test/auth/v1"
   :supabase-audience "authenticated"
   :jwks-cache-seconds 300
   :auth-redirect-url "http://localhost/auth/callback"
   :openai-api-key nil
   :openai-data-policy "standard"
   :openai-models ["gpt-6-luna"]
   :ai-discovery? false
   :ai-adapter deterministic-ai-adapter})

(defn request
  ([method uri] (request method uri nil nil))
  ([method uri body headers]
   {:request-method method
    :uri uri
    :headers (or headers {})
    :body (when body
            (ByteArrayInputStream.
             (.getBytes (json/write-value body) StandardCharsets/UTF_8)))}))

(def write-headers
  {"content-type" "application/json"
   "idempotency-key" "019f6d4c-9a98-75c1-bcf8-810a7caf3185"
   "x-slopbook-dev-account" "demo-account"})

(defn write-headers-for [key]
  (assoc write-headers "idempotency-key" key))

(defn response-body [response]
  (when (seq (:body response)) (json/read-value (:body response))))

(defn query-request [uri query headers]
  (assoc (request :get uri nil headers) :query-params query))

(defn exception-code [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo exception (:code (ex-data exception)))))

(def guest-persona
  {:schema_version 1
   :display_name "Midnight Rotor"
   :output_language "ko"
   :persona_public {:schema_version 1
                    :catalog_version 1
                    :what_are_you [{:id "helicopter"}]
                    :archetypes [{:id "philosopher"}]
                    :traits [{:id "restless"} {:id "reflective"} {:id "blunt"}]}
   :persona_behavior {:schema_version 1
                      :voice_tones [{:id "clipped"}]
                      :sentence_shape "short"
                      :formality "neutral"
                      :custom_rule "GUEST_BEHAVIOR_CANARY"}})

(deftest production-store-is-prepared-before-startup
  (testing "PostgreSQL startup applies idempotent migrations"
    (let [called-with (atom nil)
          runtime-config {:store :postgres
                          :database-url "jdbc:postgresql://db/slopbook"}]
      (with-redefs [migrations/migrate! #(reset! called-with %)]
        (system/prepare-store! runtime-config))
      (is (= runtime-config @called-with))))

  (testing "Memory startup never invokes the migration adapter"
    (with-redefs [migrations/migrate! (fn [_]
                                        (throw (ex-info "unexpected" {})))]
      (is (nil? (system/prepare-store! {:store :memory}))))))

(deftest public-search-has-a-query-free-trusted-ip-rate-limit
  (let [{:keys [app state]} (system/create-system (assoc test-config :trust-forwarded-ip? false))
        query "RATE_LIMIT_QUERY_CANARY"
        search-request (fn [forwarded]
                         (assoc (query-request "/api/v1/search"
                                               {"q" query "type" "post"}
                                               {"cf-connecting-ip" forwarded})
                                :remote-addr "203.0.113.44"))]
    (doseq [index (range 60)]
      (is (= 200 (:status (app (search-request (str "198.51.100." index)))))))
    (let [limited (app (search-request "192.0.2.250"))]
      (is (= 429 (:status limited)))
      (is (= "SEARCH_RATE_LIMITED" (get-in (response-body limited) [:error :code])))
      (is (= "60" (get-in limited [:headers "retry-after"]))))
    (is (not (.contains (pr-str @state) query)))))

(deftest authenticated-ai-writes-have-an-account-and-route-burst-boundary
  (let [{:keys [app rate-limits]} (system/create-system test-config)
        actor-id "4ad27960-1938-4f45-8a65-340f31c82292"
        create-request (fn [index]
                         (request :post "/api/v1/posts"
                                  {:actor_character_id actor-id
                                   :input_text (str "Synthetic limited post " index ".")
                                   :media_ids []}
                                  (write-headers-for
                                   (str "019f-rate-limit-post-" (format "%03d" index)))))]
    (doseq [index (range 20)]
      (is (= 201 (:status (app (create-request index))))))
    (let [limited (app (create-request 20))]
      (is (= 429 (:status limited)))
      (is (= "AUTH_WRITE_RATE_LIMITED"
             (get-in (response-body limited) [:error :code])))
      (is (= "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."
             (get-in (response-body limited) [:error :message])))
      (is (= "60" (get-in limited [:headers "retry-after"]))))
    (is (not (.contains
              (pr-str @(get-in rate-limits [:authenticated-write :state]))
              "demo-account")))))

(deftest reaction-writes-have-burst-and-day-cost-boundaries
  (let [policies (system/authenticated-write-policies
                  {:request-method :post
                   :uri "/api/v1/targets/post/019f-reaction-target/reaction"})]
    (is (= [{:bucket :choose-reaction-burst :maximum 12 :window-ms 60000}
            {:bucket :choose-reaction-day :maximum 120 :window-ms 86400000}]
           policies))))

(deftest account-deletion-request-disables-every-owned-character-and-future-login
  (let [{:keys [app state]} (system/create-system test-config)
        missing-confirmation (app (request :delete "/api/v1/me" {}
                                           (write-headers-for "019f-account-delete-0001")))
        deleted (app (request :delete "/api/v1/me" {:confirm true}
                              (write-headers-for "019f-account-delete-0002")))
        after (app (request :get "/api/v1/me" nil
                            {"x-slopbook-dev-account" "demo-account"}))]
    (is (= 400 (:status missing-confirmation)))
    (is (= "ACCOUNT_DELETION_CONFIRMATION_REQUIRED"
           (get-in (response-body missing-confirmation) [:error :code])))
    (is (= 202 (:status deleted)))
    (is (= "deletion_pending" (:status (response-body deleted))))
    (is (= "deletion_pending" (get-in @state [:accounts "demo-account" :status])))
    (is (= #{"deleted"}
           (->> (:characters @state) vals (map :status) set)))
    (is (= 403 (:status after)))
    (is (= "ACCOUNT_DELETION_PENDING" (get-in (response-body after) [:error :code])))))

(deftest public-api-reads-have-a-trusted-ip-burst-boundary
  (let [{:keys [app rate-limits]} (system/create-system test-config)
        status-request (fn [forwarded]
                         (assoc (request :get "/api/v1/status" nil
                                         {"cf-connecting-ip" forwarded})
                                :remote-addr "203.0.113.90"))]
    (doseq [index (range 300)]
      (is (= 200 (:status (app (status-request (str "198.51.100." (mod index 255))))))))
    (let [limited (app (status-request "192.0.2.8"))]
      (is (= 429 (:status limited)))
      (is (= "PUBLIC_READ_RATE_LIMITED"
             (get-in (response-body limited) [:error :code])))
      (is (= "60" (get-in limited [:headers "retry-after"]))))
    (is (not (.contains
              (pr-str @(get-in rate-limits [:public-read :state]))
              "203.0.113.90")))))

(deftest health-and-public-feed
  (let [{:keys [app]} (system/create-system test-config)
        live (app (request :get "/health/live"))
        client-config (app (request :get "/api/v1/config"))
        auth-callback (app (request :get "/auth/callback"))
        policy-pages (mapv #(app (request :get %))
                           ["/characters" "/privacy" "/terms" "/ai-policy"
                            "/community-guidelines" "/security"])
        feed (app (request :get "/api/v1/feed"))
        websocket-required (app (request :get "/ws/status"))
        missing-api (app (request :get "/api/v1/not-real"))]
    (is (= 200 (:status live)))
    (is (string? (get-in live [:headers "x-request-id"])))
    (is (.contains (get-in live [:headers "content-security-policy"]) "https://auth.example.test"))
    (is (.contains (get-in live [:headers "content-security-policy"])
                   "img-src 'self' data: blob:"))
    (is (= true (get-in (json/read-value (:body client-config)) [:auth :enabled])))
    (is (= 200 (:status auth-callback)))
    (is (= "no-store" (get-in auth-callback [:headers "cache-control"])))
    (is (.contains (:body auth-callback) "Character. Beyond Anonymity."))
    (is (every? #(= 200 (:status %)) policy-pages))
    (is (every? #(= "no-store" (get-in % [:headers "cache-control"])) policy-pages))
    (is (not (.contains (:body client-config) "database")))
    (is (= "openai" (get-in (json/read-value (:body client-config)) [:ai :provider])))
    (is (not (re-find #"(?i)api[_-]key" (:body client-config))))
    (is (= 200 (:status feed)))
    (is (= "no-store" (get-in feed [:headers "cache-control"])))
    (is (= "no-store" (get-in client-config [:headers "cache-control"])))
    (is (not (.contains (:body feed) "persona_behavior")))
    (is (= 2 (count (:items (json/read-value (:body feed))))))
    (is (= 426 (:status websocket-required)))
    (is (= 404 (:status missing-api)))
    (is (= "ROUTE_NOT_FOUND" (get-in (json/read-value (:body missing-api)) [:error :code])))))

(deftest persona-sigil-raster-has-a-bounded-immutable-http-contract
  (let [{:keys [app]} (system/create-system test-config)
        seed (apply str (repeat 64 "a"))
        uri (str "/avatars/sigil/v1/" seed "@80.webp")
        first-response (app (request :get uri))
        etag (get-in first-response [:headers "etag"])
        cached-response (app (request :get uri nil {"if-none-match" etag}))
        weak-cached-response (app (request :get uri nil {"if-none-match" (str "W/" etag)}))]
    (is (= 200 (:status first-response)))
    (is (contains? #{"image/webp" "image/png"}
                   (get-in first-response [:headers "content-type"])))
    (is (= "public, max-age=31536000, immutable"
           (get-in first-response [:headers "cache-control"])))
    (is (bytes? (:body first-response)))
    (is (= 304 (:status cached-response)))
    (is (nil? (:body cached-response)))
    (is (= 304 (:status weak-cached-response)))
    (is (nil? (:body weak-cached-response)))))

(deftest transformed-post-is-idempotent-and-does-not-store-request-shape
  (let [{:keys [app state]} (system/create-system test-config)
        payload {:actor_character_id "4ad27960-1938-4f45-8a65-340f31c82292"
                 :input_text "Synthetic input for privacy boundary."
                 :media_ids []}
        first-response (app (request :post "/api/v1/posts" payload write-headers))
        second-response (app (request :post "/api/v1/posts" payload write-headers))
        first-body (json/read-value (:body first-response))
        second-body (json/read-value (:body second-response))
        all-keys (atom [])]
    (walk/postwalk (fn [value]
                     (when (keyword? value) (swap! all-keys conj value))
                     value)
                   @state)
    (is (= 201 (:status first-response)))
    (is (= (:id first-body) (:id second-body)))
    (is (= 3 (count (:posts @state))))
    (is (not (some #{:input_text} @all-keys)))
    (is (not (.contains (:body first-response) "persona_behavior")))))

(deftest feed-uses-signed-stable-pages-and-binds-cursors-to-mode
  (let [{:keys [app state]} (system/create-system test-config)
        template (get-in @state [:posts "a81bf6c2-49ad-4ff2-aee3-4f144d0df2a3"])
        ids (mapv (fn [_] (str (java.util.UUID/randomUUID))) (range 21))]
    (swap! state
           (fn [snapshot]
             (-> (reduce (fn [value [index id]]
                           (assoc-in value [:posts id]
                                     (assoc template :id id
                                            :text (str "Feed page fixture " index)
                                            :created_at (format "2026-07-16T01:00:%02dZ" index))))
                         snapshot (map-indexed vector ids))
                 (update :post-order #(vec (concat ids %))))))
    (let [first-page (response-body
                      (app (query-request "/api/v1/feed" {"mode" "latest"} {})))
          cursor (:next_cursor first-page)
          second-page (response-body
                       (app (query-request "/api/v1/feed"
                                           {"mode" "latest" "cursor" cursor} {})))
          returned (concat (map :id (:items first-page)) (map :id (:items second-page)))
          tampered (app (query-request "/api/v1/feed"
                                       {"mode" "latest" "cursor" (str cursor "x")} {}))
          wrong-mode (app (query-request "/api/v1/feed"
                                         {"mode" "hot" "cursor" cursor} {}))]
      (is (= 20 (count (:items first-page))))
      (is (string? cursor))
      (is (= 3 (count (:items second-page))))
      (is (= 23 (count returned)))
      (is (= 23 (count (distinct returned))))
      (is (= 400 (:status tampered)))
      (is (= "CURSOR_INVALID" (get-in (response-body tampered) [:error :code])))
      (is (= 400 (:status wrong-mode)))
      (is (= "CURSOR_INVALID" (get-in (response-body wrong-mode) [:error :code]))))))

(deftest authenticated-comments-flatten-replies-and-preserve-tombstones
  (let [{:keys [app state]} (system/create-system test-config)
        post-id "a81bf6c2-49ad-4ff2-aee3-4f144d0df2a3"
        root-response
        (app (request :post (str "/api/v1/posts/" post-id "/comments")
                      {:actor_character_id "4ad27960-1938-4f45-8a65-340f31c82292"
                       :input_text "A synthetic root comment."
                       :media_ids []}
                      (write-headers-for "019f706a-09b7-7d86-a922-a4c49f610001")))
        root (json/read-value (:body root-response))
        reply-response
        (app (request :post (str "/api/v1/posts/" post-id "/comments")
                      {:actor_character_id "64dc3c7a-2a5c-497b-83d5-5ac7b5d89919"
                       :input_text "A synthetic reply."
                       :parent_comment_id (:id root)
                       :media_ids []}
                      (write-headers-for "019f706a-09b7-7d86-a922-a4c49f610002")))
        reply (json/read-value (:body reply-response))
        nested-response
        (app (request :post (str "/api/v1/posts/" post-id "/comments")
                      {:actor_character_id "4ad27960-1938-4f45-8a65-340f31c82292"
                       :input_text "A synthetic reply to the reply."
                       :parent_comment_id (:id reply)
                       :media_ids []}
                      (write-headers-for "019f706a-09b7-7d86-a922-a4c49f610006")))
        nested (json/read-value (:body nested-response))
        too-long
        (app (request :post (str "/api/v1/posts/" post-id "/comments")
                      {:actor_character_id "4ad27960-1938-4f45-8a65-340f31c82292"
                       :input_text (apply str (repeat 181 "가")) :media_ids []}
                      (write-headers-for "019f706a-09b7-7d86-a922-a4c49f610007")))
        blocked-media
        (app (request :post (str "/api/v1/posts/" post-id "/comments")
                      {:actor_character_id "4ad27960-1938-4f45-8a65-340f31c82292"
                       :input_text "This image should not be accepted."
                       :media_ids ["019f706a-09b7-7d86-a922-a4c49f610099"]}
                      (write-headers-for "019f706a-09b7-7d86-a922-a4c49f610098")))
        wrong-character-delete
        (app (request :delete (str "/api/v1/comments/" (:id root))
                      {:actor_character_id "64dc3c7a-2a5c-497b-83d5-5ac7b5d89919"}
                      (write-headers-for "019f706a-09b7-7d86-a922-a4c49f610008")))
        thread-before (app (request :get (str "/api/v1/posts/" post-id "/comments")))
        delete-response
        (app (request :delete (str "/api/v1/comments/" (:id root))
                      {:actor_character_id "4ad27960-1938-4f45-8a65-340f31c82292"}
                      (write-headers-for "019f706a-09b7-7d86-a922-a4c49f610003")))
        thread-after (app (request :get (str "/api/v1/posts/" post-id "/comments")))
        before-body (json/read-value (:body thread-before))
        after-body (json/read-value (:body thread-after))
        all-keys (atom [])]
    (walk/postwalk (fn [value]
                     (when (keyword? value) (swap! all-keys conj value))
                     value)
                   @state)
    (is (= 201 (:status root-response)))
    (is (= 201 (:status reply-response)))
    (is (= 201 (:status nested-response)))
    (is (= (:id root) (:thread_root_id (json/read-value (:body reply-response)))))
    (is (= (:id root) (:thread_root_id nested)))
    (is (= (:id reply) (:parent_comment_id nested)))
    (is (= 400 (:status too-long)))
    (is (= "CONTENT_GRAPHEME_LIMIT" (get-in (response-body too-long) [:error :code])))
    (is (= 400 (:status blocked-media)))
    (is (= "COMMENT_MEDIA_DISABLED" (get-in (response-body blocked-media) [:error :code])))
    (is (= 403 (:status wrong-character-delete)))
    (is (= 1 (count (:items before-body))))
    (is (= 2 (count (get-in before-body [:items 0 :replies]))))
    (is (= "moss_radio" (get-in before-body [:items 0 :replies 1 :reply_to :handle])))
    (is (= 200 (:status delete-response)))
    (is (true? (:tombstone (json/read-value (:body delete-response)))))
    (is (= "deleted" (get-in after-body [:items 0 :root :status])))
    (is (nil? (get-in after-body [:items 0 :root :text])))
    (is (= 2 (count (get-in after-body [:items 0 :replies]))))
    (is (not (some #{:input_text} @all-keys)))))

(deftest comment-pages-keep-roots-and-replies-together-with-signed-cursors
  (let [{:keys [app]} (system/create-system test-config)
        post-id "a81bf6c2-49ad-4ff2-aee3-4f144d0df2a3"
        actor "4ad27960-1938-4f45-8a65-340f31c82292"
        created (mapv (fn [index]
                        (response-body
                         (app (request :post (str "/api/v1/posts/" post-id "/comments")
                                       {:actor_character_id actor
                                        :input_text (str "Root page fixture " index)
                                        :media_ids []}
                                       (write-headers-for
                                        (format "019f-comment-page-%04d" index))))))
                      (range 21))
        first-page (response-body (app (request :get (str "/api/v1/posts/" post-id "/comments"))))
        cursor (:next_cursor first-page)
        second-page (response-body
                     (app (query-request (str "/api/v1/posts/" post-id "/comments")
                                         {"cursor" cursor} {})))
        tampered (app (query-request (str "/api/v1/posts/" post-id "/comments")
                                     {"cursor" (str cursor "x")} {}))
        returned (concat (map #(get-in % [:root :id]) (:items first-page))
                         (map #(get-in % [:root :id]) (:items second-page)))]
    (is (= 21 (count created)))
    (is (= 20 (count (:items first-page))))
    (is (string? cursor))
    (is (= 1 (count (:items second-page))))
    (is (= (set (map :id created)) (set returned)))
    (is (= 21 (count (distinct returned))))
    (is (= 400 (:status tampered)))
    (is (= "CURSOR_INVALID" (get-in (response-body tampered) [:error :code])))))

(deftest anonymous-comment-uses-one-browser-secret-and-never-projects-private-state
  (let [{:keys [app state]} (system/create-system test-config)
        post-id "a81bf6c2-49ad-4ff2-aee3-4f144d0df2a3"
        session-response (app (assoc (request :post "/api/v1/anonymous-sessions" {}
                                              (write-headers-for "019f-anon-session-0001"))
                                     :remote-addr "203.0.113.10"))
        session (response-body session-response)
        session-id (:anonymous_session_id session)
        deletion-token (:deletion_token session)
        stored-session (get-in @state [:anonymous-sessions session-id])
        missing-verification
        (app (assoc (request :post (str "/api/v1/posts/" post-id "/comments")
                             {:anonymous_session_id session-id
                              :input_text "Anonymous missing challenge"
                              :anonymous_persona guest-persona}
                             (write-headers-for "019f-anon-comment-0002"))
                    :remote-addr "203.0.113.10"))
        created-response
        (app (assoc (request :post (str "/api/v1/posts/" post-id "/comments")
                             {:anonymous_session_id session-id
                              :input_text "익명 원문 저장 금지 표식"
                              :anonymous_persona guest-persona
                              :turnstile_token "development-pass"}
                             (write-headers-for "019f-anon-comment-0003"))
                    :remote-addr "203.0.113.10"))
        created (response-body created-response)
        notification (first (filter #(= (:id created) (:resource_id %))
                                    (vals (:notifications @state))))
        wrong-delete
        (app (request :delete (str "/api/v1/comments/" (:id created) "/anonymous")
                      {:anonymous_session_id session-id}
                      (assoc (write-headers-for "019f-anon-delete-0004")
                             "x-anonymous-deletion-token" (apply str (repeat 43 "x")))))
        deleted
        (app (request :delete (str "/api/v1/comments/" (:id created) "/anonymous")
                      {:anonymous_session_id session-id}
                      (assoc (write-headers-for "019f-anon-delete-0005")
                             "x-anonymous-deletion-token" deletion-token)))]
    (is (= 201 (:status session-response)))
    (is (= 43 (count deletion-token)))
    (is (= 32 (alength ^bytes (:deletion_token_digest stored-session))))
    (is (not (.contains (pr-str @state) deletion-token)))
    (is (= 400 (:status missing-verification)))
    (is (= "TURNSTILE_REQUIRED" (get-in (response-body missing-verification) [:error :code])))
    (is (= 201 (:status created-response)))
    (is (true? (:anonymous created)))
    (is (= "Midnight Rotor" (get-in created [:anonymous_persona :display_name])))
    (is (= "helicopter" (get-in created [:anonymous_persona :persona_public
                                          :what_are_you 0 :id])))
    (is (= "persona_sigil" (get-in created [:anonymous_persona :avatar :kind])))
    (is (not (.contains (pr-str created) "GUEST_BEHAVIOR_CANARY")))
    (is (not (.contains (pr-str (get-in @state [:comments (:id created)]))
                        "GUEST_BEHAVIOR_CANARY")))
    (is (not-any? #(contains? created %)
                  [:anonymous_session_id :deletion_token :persona_behavior :account_id]))
    (is (= post-id (:post_id notification)))
    (is (= "comment" (:type notification)))
    (is (= 403 (:status wrong-delete)))
    (is (= 200 (:status deleted)))
    (is (false? (:tombstone (response-body deleted))))))

(deftest account-character-roster-is-capped-at-five
  (let [{:keys [app]} (system/create-system test-config)
        draft (fn [index]
                {:handle (str "limit_character_" index)
                 :display_name (str "Limit Character " index)
                 :nationality nil
                 :output_language "en"
                 :persona_public {:schema_version 1 :catalog_version 1
                                  :what_are_you [{:id "human"}]
                                  :archetypes [{:id "inventor"}]
                                  :traits [{:id "curious"} {:id "patient"} {:id "bold"}]}
                 :persona_behavior {:schema_version 1
                                    :voice_tones [{:id "direct"}]
                                    :sentence_shape "mixed"
                                    :formality "neutral"}})
        before (response-body (app (request :get "/api/v1/me" nil write-headers)))
        responses (mapv (fn [index]
                          (app (request :post "/api/v1/characters" (draft index)
                                        (write-headers-for (format "019f-limit-create-%04d" index)))))
                        (range 1 5))
        after (response-body (app (request :get "/api/v1/me" nil write-headers)))]
    (is (= 2 (count (:owned_characters before))))
    (is (= 5 (:character_limit before)))
    (is (= 3 (:remaining_character_slots before)))
    (is (= [201 201 201 409] (mapv :status responses)))
    (is (= "CHARACTER_LIMIT_REACHED"
           (get-in (response-body (last responses)) [:error :code])))
    (is (= 5 (count (:owned_characters after))))
    (is (zero? (:remaining_character_slots after)))))

(deftest character-first-post-preview-is-exact-and-published-once
  (let [{:keys [app state]} (system/create-system test-config)
        draft {:handle "first_hello_test"
               :display_name "Paper Helicopter"
               :nationality nil
               :output_language "en"
               :persona_public {:schema_version 1 :catalog_version 2
                                :what_are_you [{:id "helicopter"}]
                                :archetypes [{:id "wanderer"}]
                                :traits [{:id "curious"} {:id "playful"} {:id "bold"}]}
               :persona_behavior {:schema_version 1
                                  :voice_tones [{:id "playful"}]
                                  :sentence_shape "short"
                                  :formality "casual"}}
        preview-response (app (request :post "/api/v1/character-greetings/preview" draft
                                       (write-headers-for "019f-greeting-preview-0001")))
        preview (response-body preview-response)
        character-response (app (request :post "/api/v1/characters" draft
                                         (write-headers-for "019f-greeting-character-0002")))
        character (response-body character-response)
        actor-id (get-in character [:public_character :id])
        publish-body {:actor_character_id actor-id :preview_token (:preview_token preview)}
        first-publish (app (request :post "/api/v1/character-greetings" publish-body
                                    (write-headers-for "019f-greeting-publish-0003")))
        retry-publish (app (request :post "/api/v1/character-greetings" publish-body
                                    (write-headers-for "019f-greeting-publish-0004")))
        payload (cursor/decode (:cursor-secret test-config) (:preview_token preview))
        expired-token (cursor/encode (:cursor-secret test-config)
                                     (assoc payload :expires_at "2000-01-01T00:00:00Z"))
        expired-publish (app (request :post "/api/v1/character-greetings"
                                      (assoc publish-body :preview_token expired-token)
                                      (write-headers-for "019f-greeting-expired-0005")))
        token (:preview_token preview)
        tamper-index (quot (count token) 2)
        tampered-token (str (subs token 0 tamper-index)
                            (if (= "A" (subs token tamper-index (inc tamper-index))) "B" "A")
                            (subs token (inc tamper-index)))
        tampered-publish (app (request :post "/api/v1/character-greetings"
                                       (assoc publish-body :preview_token tampered-token)
                                       (write-headers-for "019f-greeting-tampered-0006")))
        first-post (response-body first-publish)
        retried-post (response-body retry-publish)]
    (is (= 200 (:status preview-response)))
    (is (string? (:preview_token preview)))
    (is (= 201 (:status character-response)))
    (is (= 201 (:status first-publish)))
    (is (= 201 (:status retry-publish)))
    (is (= (:text preview) (:text first-post)))
    (is (= (:id first-post) (:id retried-post)))
    (is (= "GREETING_PREVIEW_INVALID"
           (get-in (response-body expired-publish) [:error :code])))
    (is (= "GREETING_PREVIEW_INVALID"
           (get-in (response-body tampered-publish) [:error :code])))
    (is (= 1 (count (filter #(= actor-id (:character_id %)) (vals (:posts @state))))))
    (is (= 1 (count (filter #(= "test/deterministic" (:model_id %))
                            (:ai-runs @state)))))))

(deftest character-first-post-preview-is-bound-to-the-created-draft
  (let [{:keys [app]} (system/create-system test-config)
        base {:display_name "Bound Greeting"
              :nationality nil
              :output_language "en"
              :persona_public {:schema_version 1 :catalog_version 2
                               :what_are_you [{:id "ghost"}]
                               :archetypes [{:id "archivist"}]
                               :traits [{:id "curious"} {:id "gentle"} {:id "meticulous"}]}
              :persona_behavior {:schema_version 1
                                 :voice_tones [{:id "measured"}]
                                 :sentence_shape "mixed"
                                 :formality "neutral"}}
        preview (response-body
                 (app (request :post "/api/v1/character-greetings/preview"
                               (assoc base :handle "bound_preview")
                               (write-headers-for "019f-greeting-bound-preview-0005"))))
        character (response-body
                   (app (request :post "/api/v1/characters"
                                 (assoc base :handle "bound_created" :display_name "Changed Name")
                                 (write-headers-for "019f-greeting-bound-create-0006"))))
        response (app (request :post "/api/v1/character-greetings"
                               {:actor_character_id (get-in character [:public_character :id])
                                :preview_token (:preview_token preview)}
                               (write-headers-for "019f-greeting-bound-publish-0007")))]
    (is (= 400 (:status response)))
    (is (= "GREETING_PREVIEW_INVALID" (get-in (response-body response) [:error :code])))))

(deftest persona-selected-reaction-toggles-without-a-second-ai-decision
  (let [{:keys [app state]} (system/create-system test-config)
        post-id "a81bf6c2-49ad-4ff2-aee3-4f144d0df2a3"
        path (str "/api/v1/targets/post/" post-id "/reaction")
        body {:actor_character_id "64dc3c7a-2a5c-497b-83d5-5ac7b5d89919"}
        first (json/read-value
               (:body (app (request :post path body
                                    (write-headers-for "019f706a-09b7-7d86-a922-a4c49f620001")))))
        second (json/read-value
                (:body (app (request :post path body
                                     (write-headers-for "019f706a-09b7-7d86-a922-a4c49f620002")))))
        third (json/read-value
               (:body (app (request :post path body
                                    (write-headers-for "019f706a-09b7-7d86-a922-a4c49f620003")))))]
    (is (true? (:active first)))
    (is (false? (:restored first)))
    (is (false? (:active second)))
    (is (true? (:active third)))
    (is (true? (:restored third)))
    (is (= (:reaction first) (:reaction second) (:reaction third)))
    (is (= 1 (count (:ai-runs @state))))
    (is (= 1 (reduce + (vals (:counts third)))))))

(deftest notifications-are-character-scoped-readable-and-ticketed
  (let [{:keys [app]} (system/create-system test-config)
        rotor "4ad27960-1938-4f45-8a65-340f31c82292"
        moss "64dc3c7a-2a5c-497b-83d5-5ac7b5d89919"
        follow (app (request :put (str "/api/v1/characters/" rotor "/follow")
                             {:actor_character_id moss}
                             (write-headers-for "019f706a-09b7-7d86-a922-a4c49f630001")))
        list-request (assoc (request :get "/api/v1/notifications" nil
                                     {"x-slopbook-dev-account" "demo-account"})
                            :query-params {"actor_character_id" rotor})
        listed (app list-request)
        body (json/read-value (:body listed))
        notification (first (:items body))
        marked (app (request :patch (str "/api/v1/notifications/" (:id notification))
                             {:actor_character_id rotor :read true}
                             (write-headers-for "019f706a-09b7-7d86-a922-a4c49f630002")))
        ticket (app (request :post "/api/v1/ws-tickets"
                             {:actor_character_id rotor}
                             (write-headers-for "019f706a-09b7-7d86-a922-a4c49f630003")))]
    (is (= 200 (:status follow)))
    (is (= 200 (:status listed)))
    (is (= 1 (:unread_count body)))
    (is (= "follow" (:type notification)))
    (is (= moss (get-in notification [:actor :id])))
    (is (not (.contains (:body listed) "persona_behavior")))
    (is (= 200 (:status marked)))
    (is (= 0 (:unread_count (json/read-value (:body marked)))))
    (is (= 201 (:status ticket)))
    (is (string? (:ticket (json/read-value (:body ticket)))))))

(deftest websocket-tickets-are-short-lived-single-use-and-cursors-are-tamper-evident
  (let [{:keys [app social state]} (system/create-system test-config)
        rotor "4ad27960-1938-4f45-8a65-340f31c82292"
        ticket-response (app (request :post "/api/v1/ws-tickets"
                                      {:actor_character_id rotor}
                                      (write-headers-for "019f-ws-ticket-0001")))
        raw (:ticket (response-body ticket-response))
        first-consume (notifications/consume-ticket! social raw)
        second-code (exception-code #(notifications/consume-ticket! social raw))
        expiring-response (app (request :post "/api/v1/ws-tickets"
                                        {:actor_character_id rotor}
                                        (write-headers-for "019f-ws-ticket-0002")))
        expiring (:ticket (response-body expiring-response))
        _ (swap! state update :websocket-tickets
                 (fn [tickets]
                   (into {} (map (fn [[key value]]
                                   [key (if (:consumed? value) value
                                          (assoc value :expires-at "2000-01-01T00:00:00Z"))])
                                 tickets))))
        expired-code (exception-code #(notifications/consume-ticket! social expiring))
        invalid-cursor (app (query-request "/api/v1/notifications"
                                           {"actor_character_id" rotor "cursor" "not-a-ticket"}
                                           {"x-slopbook-dev-account" "demo-account"}))]
    (is (= 201 (:status ticket-response)))
    (is (= rotor first-consume))
    (is (= "WEBSOCKET_AUTH_REQUIRED" second-code))
    (is (= "WEBSOCKET_AUTH_REQUIRED" expired-code))
    (is (= 400 (:status invalid-cursor)))
    (is (= "CURSOR_INVALID" (get-in (response-body invalid-cursor) [:error :code])))))

(deftest search-follow-and-blocking-have-ranked-paginated-private-results
  (let [{:keys [app state]} (system/create-system test-config)
        rotor "4ad27960-1938-4f45-8a65-340f31c82292"
        moss "64dc3c7a-2a5c-497b-83d5-5ac7b5d89919"
        base (get-in @state [:characters rotor])
        generated (mapv (fn [index]
                          (let [id (str (java.util.UUID/randomUUID))
                                handle (format "paging_%02d" index)]
                            (swap! state (fn [snapshot]
                                           (-> snapshot
                                               (assoc-in [:characters id]
                                                         (assoc base :id id :handle handle
                                                                     :display_name (str "Paging " index)))
                                               (assoc-in [:character-handles handle] id))))
                            id))
                        (range 23))
        exact (response-body (app (query-request "/api/v1/search"
                                                 {"q" "rotor_thinks" "type" "character"} {})))
        cjk (response-body (app (query-request "/api/v1/search"
                                               {"q" "오늘" "type" "post"} {})))
        invalid (app (query-request "/api/v1/search" {"q" "가" "type" "post"} {}))
        first-page (response-body (app (query-request "/api/v1/search"
                                                      {"q" "Paging" "type" "character"} {})))
        second-page (response-body (app (query-request "/api/v1/search"
                                                       {"q" "Paging" "type" "character"
                                                        "cursor" (:next_cursor first-page)} {})))
        wrong-scope (app (query-request "/api/v1/search"
                                        {"q" "Rotor" "type" "character"
                                         "cursor" (:next_cursor first-page)} {}))
        first-follow (response-body
                      (app (request :put (str "/api/v1/characters/" rotor "/follow")
                                    {:actor_character_id moss}
                                    (write-headers-for "019f-follow-idempotent-0001"))))
        repeated-follow (response-body
                         (app (request :put (str "/api/v1/characters/" rotor "/follow")
                                       {:actor_character_id moss}
                                       (write-headers-for "019f-follow-idempotent-0002"))))
        following (response-body
                   (app (query-request (str "/api/v1/characters/" moss "/following")
                                       {"actor_character_id" moss}
                                       {"x-slopbook-dev-account" "demo-account"})))
        other-account "other-account"
        blocked-id (str (java.util.UUID/randomUUID))
        blocked-handle "blocked_character"
        blocked-post-id (str (java.util.UUID/randomUUID))
        _ (swap! state
                 (fn [snapshot]
                   (let [blocked-character (assoc base :id blocked-id :account_id other-account
                                                       :handle blocked-handle
                                                       :display_name "Blocked Needle")
                         blocked-post (assoc (get-in snapshot [:posts "a81bf6c2-49ad-4ff2-aee3-4f144d0df2a3"])
                                             :id blocked-post-id :character_id blocked-id
                                             :character (select-keys blocked-character
                                                                     [:id :handle :display_name :output_language
                                                                      :persona_public :persona_version])
                                             :text "blockedneedle transformed")]
                     (-> snapshot
                         (assoc-in [:accounts other-account] {:id other-account :status "active"})
                         (assoc-in [:characters blocked-id] blocked-character)
                         (assoc-in [:character-handles blocked-handle] blocked-id)
                         (assoc-in [:posts blocked-post-id] blocked-post)
                         (update :post-order conj blocked-post-id)
                         (update :follows (fnil conj #{}) [rotor blocked-id])
                         (update :blocks (fnil conj #{}) ["demo-account" other-account])))))
        blocked-search (response-body
                        (app (query-request "/api/v1/search"
                                            {"q" "blockedneedle" "type" "post"
                                             "actor_character_id" rotor}
                                            {"x-slopbook-dev-account" "demo-account"})))
        blocked-follow (app (request :put (str "/api/v1/characters/" blocked-id "/follow")
                                     {:actor_character_id rotor}
                                     (write-headers-for "019f-follow-blocked-0003")))
        blocked-list (response-body
                      (app (query-request (str "/api/v1/characters/" rotor "/following")
                                          {"actor_character_id" rotor}
                                          {"x-slopbook-dev-account" "demo-account"})))]
    (is (= "rotor_thinks" (get-in exact [:items 0 :handle])))
    (is (= 1 (count (:items cjk))))
    (is (= 400 (:status invalid)))
    (is (= 20 (count (:items first-page))))
    (is (= 3 (count (:items second-page))))
    (is (= (set generated)
           (set (map :id (concat (:items first-page) (:items second-page))))))
    (is (= 400 (:status wrong-scope)))
    (is (true? (:created first-follow)))
    (is (false? (:created repeated-follow)))
    (is (= [rotor] (mapv :id (:items following))))
    (is (empty? (:items blocked-search)))
    (is (= 404 (:status blocked-follow)))
    (is (not-any? #(= blocked-id (:id %)) (:items blocked-list)))
    (is (not (.contains (pr-str exact) "account_id")))))

(deftest direct-messages-use-canonical-membership-and-transformed-only-storage
  (let [{:keys [app state]} (system/create-system test-config)
        rotor "4ad27960-1938-4f45-8a65-340f31c82292"
        moss "64dc3c7a-2a5c-497b-83d5-5ac7b5d89919"
        create-body {:actor_character_id rotor :recipient_character_id moss}
        created (app (request :post "/api/v1/conversations" create-body
                              (write-headers-for "019f706a-09b7-7d86-a922-a4c49f640001")))
        conversation (json/read-value (:body created))
        repeated (app (request :post "/api/v1/conversations"
                               {:actor_character_id moss :recipient_character_id rotor}
                               (write-headers-for "019f706a-09b7-7d86-a922-a4c49f640002")))
        raw-canary "DM-RAW-CANARY-DO-NOT-STORE"
        sent (app (request :post (str "/api/v1/conversations/" (:id conversation) "/messages")
                           {:actor_character_id rotor :input_text raw-canary}
                           (write-headers-for "019f706a-09b7-7d86-a922-a4c49f640003")))
        message (json/read-value (:body sent))
        detail-request (assoc (request :get (str "/api/v1/conversations/" (:id conversation))
                                       nil {"x-slopbook-dev-account" "demo-account"})
                              :query-params {"actor_character_id" moss})
        detail (app detail-request)
        read-response (app (request :post (str "/api/v1/conversations/" (:id conversation) "/read")
                                    {:actor_character_id moss :last_read_message_id (:id message)}
                                    (write-headers-for "019f706a-09b7-7d86-a922-a4c49f640004")))
        hidden (app (request :delete (str "/api/v1/messages/" (:id message))
                             {:actor_character_id rotor}
                             (write-headers-for "019f706a-09b7-7d86-a922-a4c49f640005")))]
    (is (= 201 (:status created)))
    (is (= 200 (:status repeated)))
    (is (= (:id conversation) (:id (json/read-value (:body repeated)))))
    (is (= 1 (count (:conversations @state))))
    (is (= 201 (:status sent)))
    (is (= raw-canary (:text message)))
    (is (= 200 (:status detail)))
    (is (= (:id message) (get-in (json/read-value (:body detail)) [:messages :items 0 :id])))
    (is (= 200 (:status read-response)))
    (is (= 204 (:status hidden)))
    (is (not (.contains (pr-str @state) ":input_text")))
    (is (not (.contains (:body sent) "persona_behavior")))))

(deftest direct-message-pagination-membership-delete-and-watermark-are-consistent
  (let [{:keys [app state messaging]} (system/create-system test-config)
        rotor "4ad27960-1938-4f45-8a65-340f31c82292"
        moss "64dc3c7a-2a5c-497b-83d5-5ac7b5d89919"
        conversation (response-body
                      (app (request :post "/api/v1/conversations"
                                    {:actor_character_id rotor :recipient_character_id moss}
                                    (write-headers-for "019f-dm-page-create-0001"))))
        sent (mapv (fn [index]
                     (response-body
                      (app (request :post (str "/api/v1/conversations/" (:id conversation) "/messages")
                                    {:actor_character_id rotor :input_text (str "DM page " index)}
                                    (write-headers-for (format "019f-dm-page-send-%04d" index))))))
                   (range 21))
        detail-query (fn [cursor]
                       (cond-> {"actor_character_id" moss} cursor (assoc "cursor" cursor)))
        first-page (response-body
                    (app (query-request (str "/api/v1/conversations/" (:id conversation))
                                        (detail-query nil)
                                        {"x-slopbook-dev-account" "demo-account"})))
        cursor (get-in first-page [:messages :next_cursor])
        second-page (response-body
                     (app (query-request (str "/api/v1/conversations/" (:id conversation))
                                         (detail-query cursor)
                                         {"x-slopbook-dev-account" "demo-account"})))
        newest (last (get-in first-page [:messages :items]))
        oldest (first (get-in second-page [:messages :items]))
        read-newest (response-body
                     (app (request :post (str "/api/v1/conversations/" (:id conversation) "/read")
                                   {:actor_character_id moss :last_read_message_id (:id newest)}
                                   (write-headers-for "019f-dm-page-read-0002"))))
        read-older (response-body
                    (app (request :post (str "/api/v1/conversations/" (:id conversation) "/read")
                                  {:actor_character_id moss :last_read_message_id (:id oldest)}
                                  (write-headers-for "019f-dm-page-read-0003"))))
        receiver-delete
        (app (request :delete (str "/api/v1/messages/" (:id newest))
                      {:actor_character_id moss}
                      (write-headers-for "019f-dm-page-delete-0004")))
        other-account "dm-other-account"
        outsider-id (str (java.util.UUID/randomUUID))
        base (get-in @state [:characters rotor])
        _ (swap! state (fn [snapshot]
                         (-> snapshot
                             (assoc-in [:accounts other-account] {:id other-account :status "active"})
                             (assoc-in [:characters outsider-id]
                                       (assoc base :id outsider-id :account_id other-account
                                                   :handle "dm_outsider" :display_name "DM Outsider")))))
        outsider-code (exception-code
                       #(messages/detail! messaging other-account (:id conversation) outsider-id nil))]
    (is (= 21 (count sent)))
    (is (= 20 (count (get-in first-page [:messages :items]))))
    (is (= 1 (count (get-in second-page [:messages :items]))))
    (is (= (set (map :id sent))
           (set (map :id (concat (get-in first-page [:messages :items])
                                 (get-in second-page [:messages :items]))))))
    (is (= (:id newest) (:last_read_message_id read-newest)))
    (is (= (:id newest) (:last_read_message_id read-older)))
    (is (= 404 (:status receiver-delete)))
    (is (= "MESSAGE_NOT_FOUND" (get-in (response-body receiver-delete) [:error :code])))
    (is (= "CONVERSATION_NOT_FOUND" outsider-code))))
