(ns slopbook.transformation.adapters.out.postgres-runs
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as result-set]
            [slopbook.transformation.ports.runs :as run-port]))

(def query-options {:builder-fn result-set/as-unqualified-lower-maps})

(defn adapter [datasource]
  (run-port/assert-adapter!
   {:insert!
    (fn [metadata]
      (jdbc/execute-one!
       datasource
       ["INSERT INTO ai_runs
           (task_kind, model_id, provider_class, prompt_version, schema_version,
            result_code, latency_ms, prompt_tokens, completion_tokens)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
         RETURNING id"
        (:task_kind metadata) (:model_id metadata) (:provider_class metadata)
        (:prompt_version metadata) (:schema_version metadata) (:result_code metadata)
        (:latency_ms metadata) (:prompt_tokens metadata) (:completion_tokens metadata)]
       query-options))}))
