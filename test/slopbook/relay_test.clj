(ns slopbook.relay-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [slopbook.platform.json :as json]
            [slopbook.relay.adapters.out.moltbook :as moltbook]
            [slopbook.relay.domain.source :as source]
            [slopbook.system :as system]
            [slopbook.system-test :as support]
            [slopbook.transformation.adapters.out.meta :as meta-ai]))

(def source-id "00000000-0000-4000-8000-000000000001")
(def public-source
  {:id source-id :title "A discussion about agent memory" :content "An agent argues for explicit constraints."
   :author "example-agent" :community "general" :comment_count 2
   :url (str "https://www.moltbook.com/post/" source-id)})
(def brief-result
  {:summary "The author argues for explicit constraints."
   :different_view "No contrasting view is present in the sampled comments."
   :open_question "What would a safe handoff preserve?" :output_language "en"})

(deftest source-requests-cannot-become-arbitrary-fetches-or-writes
  (let [calls (atom [])
        adapter (moltbook/adapter
                 {:get-json (fn [path]
                              (swap! calls conj path)
                              {:status 200 :body (json/write-value
                                                  {:posts [{:id source-id :title "Public source"
                                                            :content "Read only" :private_key "CANARY"}]})})})]
    (is (= "RELAY_SOURCE_INVALID" (support/exception-code #((:thread adapter) "https://private.example/secret"))))
    (is (= "RELAY_SORT_INVALID" (support/exception-code #((:feed adapter) "hot&redirect=evil"))))
    (is (empty? @calls))
    (is (= 1 (count (:items ((:feed adapter) "hot")))))
    ((:feed adapter) "hot")
    (is (= ["/posts?sort=hot&limit=12"] @calls))
    (is (not (str/includes? (pr-str ((:feed adapter) "hot")) "CANARY")))
    (is (nil? (source/public-post {:id source-id :title "deleted" :is_deleted true})))
    (is (nil? (source/public-post {:id source-id :title "spam" :is_spam true})))))

(deftest meta-requests-have-an-explicit-structured-contract-and-no-openai-fallback
  (let [request (meta-ai/request-body {:task_kind "relay.brief" :output_language "en" :post public-source})
        rewrite (meta-ai/request-body {:task_kind "rewrite.post" :input_text "Private intent"})]
    (is (= "muse-spark-1.3" (:model request)))
    (is (= "minimal" (:reasoning_effort request)))
    (is (false? (:store request)))
    (is (true? (get-in request [:response_format :json_schema :strict])))
    (is (= meta-ai/brief-schema (get-in request [:response_format :json_schema :schema])))
    (is (= 2 (count (:messages request))))
    (is (not (str/includes? (get-in rewrite [:messages 0 :content]) "Private intent")))
    (is (not ((:available? (meta-ai/adapter {:openai-api-key "must-not-be-used"})))))))

(deftest meta-edition-keeps-every-existing-persona-ai-operation
  (let [calls (atom [])
        adapter (meta-ai/adapter
                 {:meta-api-key "synthetic-meta-key"}
                 {:post-json
                  (fn [{:keys [body]}]
                    (let [request (json/read-value body)
                          envelope (json/read-value (get-in request [:messages 1 :content]))
                          result (dissoc (support/deterministic-ai-result envelope) :model_id :provider)]
                      (swap! calls conj {:model (:model request) :envelope envelope})
                      {:status 200
                       :body (json/write-value {:choices [{:finish_reason "stop"
                                                          :message {:content (json/write-value result)}}]})}))})
        {:keys [app]} (system/create-system (-> support/test-config
                                               (dissoc :ai-adapter)
                                               (assoc :ai-provider "meta" :meta-ai-adapter adapter)))
        rotor "4ad27960-1938-4f45-8a65-340f31c82292"
        moss "64dc3c7a-2a5c-497b-83d5-5ac7b5d89919"
        post-id "a81bf6c2-49ad-4ff2-aee3-4f144d0df2a3"
        send! (fn [path body key]
                (app (support/request :post path body (support/write-headers-for (str "019f-meta-" key)))))
        draft {:handle "muse_persona_test" :display_name "Muse Persona" :output_language "en"
               :persona_public {:schema_version 1 :catalog_version 2
                                :what_are_you [{:id "helicopter"}] :archetypes [{:id "wanderer"}]
                                :traits [{:id "curious"} {:id "playful"} {:id "bold"}]}
               :persona_behavior {:schema_version 1 :voice_tones [{:id "playful"}]
                                  :sentence_shape "short" :formality "casual"}}
        preview (send! "/api/v1/character-greetings/preview" draft "meta-full-greeting")
        post (send! "/api/v1/posts" {:actor_character_id rotor :input_text "A human post." :media_ids []}
                    "meta-full-post")
        comment (send! (str "/api/v1/posts/" post-id "/comments")
                       {:actor_character_id rotor :input_text "A human comment." :media_ids []}
                       "meta-full-comment")
        reaction (send! (str "/api/v1/targets/post/" post-id "/reaction")
                        {:actor_character_id moss} "meta-full-reaction")
        conversation (send! "/api/v1/conversations" {:actor_character_id rotor :recipient_character_id moss}
                            "meta-full-conversation")
        message (send! (str "/api/v1/conversations/" (:id (support/response-body conversation)) "/messages")
                       {:actor_character_id rotor :input_text "A private human message."} "meta-full-message")]
    (doseq [[kind response] [[:greeting preview] [:post post] [:comment comment]
                            [:reaction reaction] [:conversation conversation] [:message message]]]
      (is (<= 200 (:status response) 201) (str kind " " (:body response))))
    ;; Greetings use the ordinary post rewrite contract, then an exact preview token.
    (is (= #{"rewrite.post" "rewrite.comment" "choose.reaction" "rewrite.message"}
           (set (map #(get-in % [:envelope :task_kind]) @calls))))
    (is (= 5 (count @calls)))
    (is (every? #(= "muse-spark-1.3" (:model %)) @calls))
    (is (= "playful" (get-in (first @calls) [:envelope :persona :behavior :voice_tones 0 :id])))))

(deftest relay-briefs-are-public-and-bounded-with-no-durable-source-or-user-input
  (let [calls (atom [])
        fake (assoc support/deterministic-ai-adapter
                    :rewrite-with-model! (fn [_ envelope _]
                                           (swap! calls conj envelope)
                                           (assoc brief-result :model_id "muse-spark-1.3" :provider "meta")))
        {:keys [app state]} (system/create-system
                            (assoc support/test-config :meta-ai-adapter fake
                                   :relay-source-adapter {:feed (fn [_] {:items [public-source]})
                                                          :post (fn [_] public-source)
                                                          :thread (fn [_] {:post public-source :comments []})}))
        request #(assoc (support/request :post "/api/v1/relay/brief"
                                        {:post_id source-id :output_language "en"}
                                        {"content-type" "application/json"
                                         "idempotency-key" (str "relay-public-brief-" %)})
                        :remote-addr "192.0.2.47")]
    (is (= 200 (:status (app (support/request :get "/api/v1/relay/posts")))))
    (doseq [index (range 12)]
      (is (= 200 (:status (app (request index))))))
    (is (= 429 (:status (app (request 12)))))
    (is (= 12 (count @calls)))
    (is (not (str/includes? (pr-str @state) (:title public-source))))
    (is (not (str/includes? (pr-str @state) (:summary brief-result))))))

(deftest relay-publication-reuses-ownership-idempotency-and-private-intent-boundaries
  (let [calls (atom [])
        source-adapter {:feed (fn [_] {:items [public-source]})
                        :post (fn [_] public-source)
                        :thread (fn [_] {:post public-source :comments []})}
        fake (assoc support/deterministic-ai-adapter
                    :rewrite-with-model!
                    (fn [_ envelope _]
                      (swap! calls conj envelope)
                      (assoc (support/deterministic-ai-result envelope)
                             :text (str "A human asks for careful handoffs.\n" (:url public-source))
                             :model_id "muse-spark-1.3" :provider "meta")))
        {:keys [app state]} (system/create-system (assoc support/test-config
                                                       :meta-ai-adapter fake :relay-source-adapter source-adapter))
        intent "PRIVATE_RELAY_INTENT_CANARY"
        body {:post_id source-id :actor_character_id "4ad27960-1938-4f45-8a65-340f31c82292" :input_text intent}
        request #(support/request :post "/api/v1/relay/posts" body %)
        headers (support/write-headers-for "relay-publish-idempotent-key")
        first-response (app (request headers))
        retry-response (app (request headers))]
    (is (= 201 (:status first-response)))
    (is (= (:id (support/response-body first-response)) (:id (support/response-body retry-response))))
    (is (= 1 (count @calls)))
    (is (str/ends-with? (:text (support/response-body first-response)) (:url public-source)))
    (is (not (str/includes? (pr-str @state) intent)))
    (testing "the shared composer cannot silently drop an attachment"
      (let [response (app (support/request :post "/api/v1/relay/posts"
                                           (assoc body :media_ids ["00000000-0000-4000-8000-000000000087"])
                                           (support/write-headers-for "relay-missing-media-key")))]
        (is (= 409 (:status response)))
        (is (= "MEDIA_NOT_READY" (get-in (support/response-body response) [:error :code])))
        (is (= 1 (count @calls)))))
    (let [protected-app (:app (system/create-system
                              (assoc support/test-config :dev-auth? false :meta-ai-adapter fake
                                     :relay-source-adapter source-adapter)))]
      (is (= 401 (:status (protected-app (request (dissoc headers "x-slopbook-dev-account")))))))
    (is (= 403 (:status (app (support/request :post "/api/v1/relay/posts"
                                             (assoc body :actor_character_id "00000000-0000-4000-8000-000000000099")
                                             (support/write-headers-for "relay-unowned-persona-key"))))))
    (is (= 400 (:status (app (support/request :post "/api/v1/relay/posts" (assoc body :input_text "")
                                             (support/write-headers-for "relay-empty-intent-key"))))))))
