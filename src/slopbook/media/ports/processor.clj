(ns slopbook.media.ports.processor)

(def required-operations #{:validate! :normalize!})

(defn assert-adapter! [adapter]
  (let [missing (remove #(fn? (get adapter %)) required-operations)]
    (when (seq missing)
      (throw (ex-info "Media processor adapter is incomplete" {:missing (vec missing)})))
    adapter))

(defn validate! [adapter upload] ((:validate! adapter) upload))
(defn normalize! [adapter upload descriptor] ((:normalize! adapter) upload descriptor))
