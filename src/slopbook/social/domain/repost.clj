(ns slopbook.social.domain.repost)

(defn transition [existing]
  (cond
    (nil? existing) {:active true :restored false :new-repost true}
    (:active existing) {:active false :restored false :new-repost false}
    :else {:active true :restored true :new-repost false}))
