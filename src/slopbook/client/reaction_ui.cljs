(ns slopbook.client.reaction-ui)

(def reaction-order ["like" "love" "insightful" "celebrate" "support" "funny"])

(defn received-reactions [counts]
  (->> reaction-order
       (keep (fn [reaction]
               (let [count (or (get counts (keyword reaction))
                               (get counts reaction)
                               0)]
                 (when (pos? count)
                   {:reaction reaction :count count}))))
       vec))

(defn total [counts]
  (reduce + 0 (map :count (received-reactions counts))))

(defn first-decision? [viewer-reaction]
  (nil? (:reaction viewer-reaction)))
