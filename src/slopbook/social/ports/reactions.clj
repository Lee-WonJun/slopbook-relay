(ns slopbook.social.ports.reactions)

(def required-operations #{:target-public :toggle!})

(defn assert-adapter! [adapter]
  (let [missing (remove #(fn? (get adapter %)) required-operations)]
    (when (seq missing)
      (throw (ex-info "Social reaction adapter is incomplete" {:missing (vec missing)})))
    adapter))

(defn target-public [adapter query] ((:target-public adapter) query))
(defn toggle! [adapter command] ((:toggle! adapter) command))
