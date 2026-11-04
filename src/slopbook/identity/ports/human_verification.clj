(ns slopbook.identity.ports.human-verification)

(def required-operations #{:verify!})

(defn assert-adapter! [adapter]
  (let [missing (remove #(fn? (get adapter %)) required-operations)]
    (when (seq missing)
      (throw (ex-info "Human verification adapter is incomplete" {:missing (vec missing)})))
    adapter))

(defn verify! [adapter challenge] ((:verify! adapter) challenge))
