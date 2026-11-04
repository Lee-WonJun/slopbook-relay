(ns slopbook.social.domain.reaction)

(def allowed-types #{"like" "love" "insightful" "celebrate" "support" "funny"})

(defn transition [existing decision]
  (cond
    (nil? existing)
    (do
      (when-not (contains? allowed-types decision)
        (throw (ex-info "Invalid reaction decision" {:code :invalid-reaction})))
      {:reaction decision :active true :restored false :new-decision true})

    (:active existing)
    {:reaction (:reaction existing) :active false :restored false :new-decision false}

    :else
    {:reaction (:reaction existing) :active true :restored true :new-decision false}))
