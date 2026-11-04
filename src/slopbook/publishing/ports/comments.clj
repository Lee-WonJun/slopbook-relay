(ns slopbook.publishing.ports.comments)

(def required-operations
  #{:reserve! :publish! :fail-reservation! :thread :find-internal :delete! :delete-anonymous!})

(defn assert-adapter! [adapter]
  (let [missing (remove #(fn? (get adapter %)) required-operations)]
    (when (seq missing)
      (throw (ex-info "Publishing comment adapter is incomplete" {:missing (vec missing)})))
    adapter))

(defn reserve! [adapter command] ((:reserve! adapter) command))
(defn publish! [adapter command] ((:publish! adapter) command))
(defn fail-reservation! [adapter command] ((:fail-reservation! adapter) command))
(defn thread [adapter query] ((:thread adapter) query))
(defn find-internal [adapter id] ((:find-internal adapter) id))
(defn delete! [adapter command] ((:delete! adapter) command))
(defn delete-anonymous! [adapter command] ((:delete-anonymous! adapter) command))
