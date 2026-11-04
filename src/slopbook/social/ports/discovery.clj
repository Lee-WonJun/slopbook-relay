(ns slopbook.social.ports.discovery)

(def required-operations #{:follow! :unfollow! :list-connections :search})

(defn assert-adapter! [adapter]
  (let [missing (remove #(fn? (get adapter %)) required-operations)]
    (when (seq missing)
      (throw (ex-info "Social discovery adapter is incomplete" {:missing (vec missing)})))
    adapter))

(defn follow! [adapter command] ((:follow! adapter) command))
(defn unfollow! [adapter command] ((:unfollow! adapter) command))
(defn list-connections [adapter query] ((:list-connections adapter) query))
(defn search [adapter query] ((:search adapter) query))
