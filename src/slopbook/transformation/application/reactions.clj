(ns slopbook.transformation.application.reactions
  (:require [slopbook.platform.errors :as errors]
            [slopbook.transformation.application.rewrites :as rewrites]
            [slopbook.transformation.domain.reaction :as reaction]
            [slopbook.transformation.ports.rewriter :as rewriter-port]))

(def display
  {"like" {:icon "reaction-like" :label_key "reaction.like"}
   "love" {:icon "reaction-love" :label_key "reaction.love"}
   "insightful" {:icon "reaction-insightful" :label_key "reaction.insightful"}
   "celebrate" {:icon "reaction-celebrate" :label_key "reaction.celebrate"}
   "support" {:icon "reaction-support" :label_key "reaction.support"}
   "funny" {:icon "reaction-funny" :label_key "reaction.funny"}})

(defn choose!
  [{:keys [ai runs]} character target]
  (let [result (rewriter-port/rewrite! ai (reaction/decision-envelope character target))]
    (when-not (reaction/valid-result? result)
      (errors/fail! 502 "AI_OUTPUT_INVALID" :ai-invalid))
    (let [metadata (rewrites/run-metadata "choose.reaction" result)]
      {:reaction (:reaction result)
       :display (get display (:reaction result))
       :ai-run metadata})))
