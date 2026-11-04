(ns slopbook.social.adapters.out.memory)

(defn relationship-store [state]
  {:find-by-pair (fn [actor-id target-id]
                   (get-in @state [:relationships [actor-id target-id]]))
   :save! (fn [relationship]
            (swap! state assoc-in
                   [:relationships [(:actor_character_id relationship)
                                    (:target_character_id relationship)]]
                   relationship)
            relationship)})
