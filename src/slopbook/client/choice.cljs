(ns slopbook.client.choice)

(defn toggle-ref [values id _minimum maximum]
  (let [values (vec (or values []))
        selected? (some #(= id (:id %)) values)]
    (cond
      selected?
      (vec (remove #(= id (:id %)) values))

      (< (count values) maximum)
      (conj values {:id id})

      :else values)))
