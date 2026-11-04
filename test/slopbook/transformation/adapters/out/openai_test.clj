(ns slopbook.transformation.adapters.out.openai-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopbook.platform.json :as json]
            [slopbook.platform.routes :as routes]
            [slopbook.transformation.adapters.out.openai :as openai]
            [slopbook.transformation.ports.rewriter :as rewriter])
  (:import [java.net.http HttpTimeoutException]
           [java.util.concurrent CompletableFuture TimeoutException]))

(def model "gpt-6-luna")
(def config {:openai-api-key "SYNTHETIC_OPENAI_KEY"
             :openai-models [model] :openai-data-policy "standard"
             :ai-discovery? true})
(def envelope {:task_kind "rewrite.post" :max_graphemes 280
               :persona {:output_language "en" :public {} :behavior {}}
               :input_text "SYNTHETIC_PRIVATE_INTENT"})
(def result {:status "ok" :text "Synthetic result." :output_language "en"
             :safety_reason "none"})

(defn metadata-get [{:keys [url]}]
  {:status 200 :body (json/write-value {:id (last (clojure.string/split url #"/"))})})

(defn completion [content]
  {:status 200
   :body (json/write-value {:choices [{:finish_reason "stop" :message {:content content}}]
                            :usage {:prompt_tokens 40 :completion_tokens 20}})})

(defn error-code [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo exception (:code (ex-data exception)))))

(deftest mandatory-storage-cost-and-schema-controls
  (let [body (openai/request-body model envelope)]
    (is (openai/request-contract-valid? body))
    (is (false? (:store body)))
    (is (= "none" (:reasoning_effort body)))
    (is (= "default" (:service_tier body)))
    (is (= 640 (:max_completion_tokens body)))
    (doseq [changed [(dissoc body :store)
                     (assoc body :store true)
                     (assoc body :stream true)
                     (assoc body :service_tier "fast")
                     (assoc body :reasoning_effort "high")
                     (assoc body :model "gpt-6-astra")
                     (assoc body :max_completion_tokens 10000)
                     (assoc body :tools [{:type "function"}])
                     (assoc body :metadata {:intent "SYNTHETIC_PRIVATE_INTENT"})
                     (assoc body :provider {:zdr true})
                     (assoc-in body [:response_format :json_schema :strict] false)
                     (assoc-in body [:response_format :json_schema :schema :additionalProperties] true)]]
      (is (thrown? clojure.lang.ExceptionInfo (openai/assert-request-contract! changed))))))

(deftest reaction-keeps-the-bounded-enum-contract
  (let [body (openai/request-body model (assoc envelope :task_kind "choose.reaction"))]
    (is (= 32 (:max_completion_tokens body)))
    (is (openai/request-contract-valid? body))
    (is (= ["like" "love" "insightful" "celebrate" "support" "funny"]
           (get-in body [:response_format :json_schema :schema :properties :reaction :enum])))))

(deftest discovery-and-all-four-tasks-use-the-direct-openai-endpoint
  (let [sent (atom [])
        ai (openai/adapter config
                           {:get-json metadata-get
                            :post-json #(do (swap! sent conj %) (completion (json/write-value result)))})]
    (is (rewriter/available? ai))
    (is (= [model] (rewriter/eligible-models ai)))
    (doseq [task ["rewrite.post" "rewrite.comment" "rewrite.message" "choose.reaction"]]
      (let [output (rewriter/rewrite! ai (assoc envelope :task_kind task))]
        (is (= "openai" (:provider output)))
        (is (= model (:model_id output)))
        (is (= 40 (:prompt_tokens output)))
        (is (= 20 (:completion_tokens output)))))
    (is (= 4 (count @sent)))
    (is (every? #(= "https://api.openai.com/v1/chat/completions" (:url %)) @sent))
    (is (every? #(= 8000 (:timeout-ms %)) @sent))
    (is (every? #(false? (:store (json/read-value (:body %)))) @sent))
    (is (not (re-find #"SYNTHETIC_OPENAI_KEY|SYNTHETIC_PRIVATE_INTENT"
                       (pr-str (rewriter/capability-snapshot ai)))))
    (is (= "AI_UNAVAILABLE"
           (error-code #(rewriter/rewrite-with-model! ai "gpt-6-astra" envelope 1000))))))

(deftest missing-key-and-unverified-zdr-fail-before-any-network
  (doseq [settings [(assoc config :openai-api-key nil)
                    (assoc config :openai-api-key " ")
                    (assoc config :openai-data-policy "zdr" :openai-zdr-verified? false)]]
    (let [calls (atom 0)
          transport (fn [_] (swap! calls inc) (completion "{}"))
          ai (openai/adapter settings {:get-json transport :post-json transport})
          status (routes/status-payload {} #(rewriter/available? ai))]
      (is (not (rewriter/available? ai)))
      (is (= "read_only" (:mode status)))
      (is (= "available" (get-in status [:features :public_read])))
      (is (= "AI_UNAVAILABLE" (error-code #(rewriter/rewrite! ai envelope))))
      (is (zero? @calls)))))

(deftest zdr-requires-a-separate-verified-account-setting
  (is (not (openai/privacy-ready? {:openai-data-policy "zdr"})))
  (is (openai/privacy-ready? {:openai-data-policy "zdr" :openai-zdr-verified? true}))
  (is (not (openai/privacy-ready? {:openai-data-policy "unknown"}))))

(deftest inaccessible-models-remain-read-only
  (doseq [response [{:status 404 :body "{}"}
                    {:status 401 :body "{}"}
                    {:status 200 :body (json/write-value {:id "another-model"})}]]
    (let [ai (openai/adapter config {:get-json (constantly response)})]
      (is (not (rewriter/available? ai)))
      (is (empty? (rewriter/eligible-models ai))))))

(deftest quota-exhaustion-is-distinct-from-transient-rate-limits
  (doseq [[code expected] [["insufficient_quota" "AI_CREDITS_EXHAUSTED"]
                           ["billing_hard_limit_reached" "AI_CREDITS_EXHAUSTED"]
                           ["rate_limit_exceeded" "AI_PROVIDER_ERROR"]]]
    (let [ai (openai/adapter config
                             {:get-json metadata-get
                              :post-json (constantly
                                           {:status 429
                                            :body (json/write-value {:error {:code code}})})})]
      (is (= expected (error-code #(rewriter/rewrite! ai envelope))))
      (is (= (if (= expected "AI_CREDITS_EXHAUSTED") :exhausted :available)
             (rewriter/credit-status ai))))))

(deftest auth-revocation-closes-ai-writes
  (let [ai (openai/adapter config {:get-json metadata-get
                                  :post-json (constantly {:status 401 :body "{}"})})]
    (is (= "AI_UNAVAILABLE" (error-code #(rewriter/rewrite! ai envelope))))
    (is (not (rewriter/available? ai)))))

(deftest incomplete-invalid-and-refused-output-never-becomes-a-result
  (doseq [[payload expected]
          [[{:choices [{:finish_reason "length" :message {:content "{}"}}]} "AI_PROVIDER_ERROR"]
           [{:choices [{:finish_reason "stop" :message {:refusal "SYNTHETIC_PRIVATE_INTENT"}}]}
            "AI_POLICY_BLOCKED"]
           [{:choices [{:finish_reason "content_filter"}]} "AI_POLICY_BLOCKED"]
           [{:choices [{:finish_reason "tool_calls"}]} "AI_OUTPUT_INVALID"]
           [{:choices [{:finish_reason "stop" :message {:content "[]"}}]} "AI_OUTPUT_INVALID"]
           [{:choices [{:finish_reason "stop" :message {:content "not json"}}]} "AI_OUTPUT_INVALID"]
           [{:error {:message "SYNTHETIC_PRIVATE_INTENT"}} "AI_PROVIDER_ERROR"]]]
    (let [ai (openai/adapter config
                             {:get-json metadata-get
                              :post-json (constantly {:status 200 :body (json/write-value payload)})})]
      (is (= expected (error-code #(rewriter/rewrite! ai envelope)))))))

(deftest transport-failures-never-echo-secrets-or-intent
  (let [ai (openai/adapter config
                           {:get-json metadata-get
                            :post-json #(throw (ex-info (str "SYNTHETIC_PRIVATE_INTENT "
                                                            (get-in % [:headers "authorization"])) %))})
        exception (try (rewriter/rewrite! ai envelope)
                       (catch clojure.lang.ExceptionInfo value value))]
    (is (= "AI_PROVIDER_ERROR" (:code (ex-data exception))))
    (is (not (re-find #"SYNTHETIC_PRIVATE_INTENT|SYNTHETIC_OPENAI_KEY" (pr-str exception))))))

(deftest response-body-deadline-cancels-the-http-future
  (let [future (CompletableFuture.)
        started (System/nanoTime)]
    (is (thrown? TimeoutException (#'openai/await-response! future 25)))
    (is (.isCancelled future))
    (is (< (/ (- (System/nanoTime) started) 1000000) 1000))))

(deftest http-and-body-timeouts-have-the-same-public-failure
  (doseq [failure [(TimeoutException.) (HttpTimeoutException. "synthetic timeout")]]
    (let [ai (openai/adapter config {:get-json metadata-get
                                    :post-json (fn [_] (throw failure))})]
      (is (= "AI_TIMEOUT" (error-code #(rewriter/rewrite! ai envelope))))))
  (let [calls (atom 0)
        ai (openai/adapter config {:get-json metadata-get
                                  :post-json #(do (swap! calls inc) (completion "{}"))})]
    (is (= "AI_TIMEOUT"
           (error-code #(rewriter/rewrite-with-model! ai model envelope 0))))
    (is (zero? @calls))))
