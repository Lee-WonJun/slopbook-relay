(ns slopbook.media.ports.storage)

(def required-operations #{:put! :delete!})

(defn assert-adapter! [adapter]
  (let [missing (remove #(fn? (get adapter %)) required-operations)]
    (when (seq missing)
      (throw (ex-info "Media storage adapter is incomplete" {:missing (vec missing)})))
    adapter))

(defn put! [adapter object] ((:put! adapter) object))
(defn delete! [adapter keys] ((:delete! adapter) keys))
