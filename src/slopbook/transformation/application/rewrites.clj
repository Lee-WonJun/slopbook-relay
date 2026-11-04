(ns slopbook.transformation.application.rewrites
  (:require [slopbook.platform.errors :as errors]
            [slopbook.transformation.domain.rewrite :as rewrite]
            [slopbook.transformation.ports.rewriter :as rewriter-port]
            [slopbook.transformation.ports.runs :as run-port]))

(defn run-metadata [operation result]
  {:task_kind operation
   :model_id (:model_id result)
   :provider_class (:provider result)
   :prompt_version 1
   :schema_version 1
   :result_code (if (= "blocked" (:status result)) (:safety_reason result) "ok")
   :latency_ms (long (or (:latency_ms result) 0))
   :prompt_tokens (:prompt_tokens result)
   :completion_tokens (:completion_tokens result)})

(defn rewrite!
  [{:keys [ai runs]} {:keys [operation grapheme-limit character input-text]}]
  (let [envelope (rewrite/rewrite-envelope operation grapheme-limit character input-text)
        result (rewriter-port/rewrite! ai envelope)]
    (when-not (rewrite/valid-result? result grapheme-limit (:output_language character) input-text)
      (errors/fail! 502 "AI_OUTPUT_INVALID" :ai-invalid))
    (when runs
      (run-port/insert! runs (run-metadata operation result)))
    (when (= "blocked" (:status result))
      (errors/fail! 422 "AI_POLICY_BLOCKED" :validation))
    result))
