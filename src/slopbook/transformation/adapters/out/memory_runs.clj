(ns slopbook.transformation.adapters.out.memory-runs
  (:require [slopbook.transformation.ports.runs :as run-port]))

(defn adapter [state]
  (run-port/assert-adapter!
   {:insert! (fn [metadata]
               (swap! state update :ai-runs (fnil conj []) metadata)
               metadata)}))
