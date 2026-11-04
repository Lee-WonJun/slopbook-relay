(ns slopbook.transformation.adapters.out.postgres-runs-integration-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as result-set]
            [slopbook.platform.migrations :as migrations]
            [slopbook.platform.schema-audit :as schema-audit]
            [slopbook.transformation.adapters.out.postgres-runs :as postgres-runs]
            [slopbook.transformation.application.rewrites :as rewrites]))

(def database-url
  (or (System/getenv "DATABASE_URL")
      "jdbc:postgresql://127.0.0.1:15432/slopbook?user=slopbook&password=local-slopbook-only"))

(def datasource (schema-audit/datasource database-url))
(def query-options {:builder-fn result-set/as-unqualified-lower-maps})

(use-fixtures :once
  (fn [test-fn]
    (migrations/migrate! {:database-url database-url})
    (test-fn)))

(deftest ai-run-persists-only-allowlisted-metadata
  (let [raw-canary "PRIVATE_RAW_CANARY says 42"
        behavior-canary "OWNER_BEHAVIOR_CANARY"
        task "rewrite.post"
        runs (postgres-runs/adapter datasource)
        ai {:rewrite! (constantly {:status "ok" :text "The signal still says 42."
                                   :output_language "en" :safety_reason "none"
                                   :model_id "synthetic/model" :provider "synthetic"
                                   :latency_ms 9 :prompt_tokens 12 :completion_tokens 7})}]
    (jdbc/execute! datasource ["DELETE FROM ai_runs WHERE model_id = 'synthetic/model'"])
    (is (= "The signal still says 42."
           (:text (rewrites/rewrite! {:ai ai :runs runs}
                                     {:operation task :grapheme-limit 280
                                      :character {:output_language "en"
                                                  :persona_public {}
                                                  :persona_behavior {:custom_rule behavior-canary}}
                                      :input-text raw-canary}))))
    (let [row (jdbc/execute-one!
               datasource
               ["SELECT task_kind, model_id, provider_class, prompt_version, schema_version,
                        result_code, latency_ms, prompt_tokens, completion_tokens
                   FROM ai_runs WHERE model_id = 'synthetic/model'"]
               query-options)
          rendered (pr-str row)]
      (is (= #{:task_kind :model_id :provider_class :prompt_version :schema_version
               :result_code :latency_ms :prompt_tokens :completion_tokens}
             (set (keys row))))
      (is (not (.contains rendered raw-canary)))
      (is (not (.contains rendered behavior-canary))))))
