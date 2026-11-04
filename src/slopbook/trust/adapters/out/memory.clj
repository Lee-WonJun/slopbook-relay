(ns slopbook.trust.adapters.out.memory)

(defn adapter [state]
  {:insert! (fn [report]
              (swap! state assoc-in [:reports (:id report)] report)
              report)})
