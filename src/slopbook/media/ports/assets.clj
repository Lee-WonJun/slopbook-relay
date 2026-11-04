(ns slopbook.media.ports.assets)

(def required-operations
  #{:insert-upload! :find-owned :mark-processing! :mark-ready! :mark-failed!
    :ready-owned :delete-unattached! :expire-unattached! :pending-purges
    :mark-purged! :mark-purge-failed!})

(defn assert-adapter! [adapter]
  (let [missing (remove #(fn? (get adapter %)) required-operations)]
    (when (seq missing)
      (throw (ex-info "Media asset adapter is incomplete" {:missing (vec missing)})))
    adapter))

(defn insert-upload! [adapter value] ((:insert-upload! adapter) value))
(defn find-owned [adapter query] ((:find-owned adapter) query))
(defn mark-processing! [adapter id] ((:mark-processing! adapter) id))
(defn mark-ready! [adapter command] ((:mark-ready! adapter) command))
(defn mark-failed! [adapter command] ((:mark-failed! adapter) command))
(defn ready-owned [adapter query] ((:ready-owned adapter) query))
(defn delete-unattached! [adapter command] ((:delete-unattached! adapter) command))
(defn expire-unattached! [adapter] ((:expire-unattached! adapter)))
(defn pending-purges [adapter limit] ((:pending-purges adapter) limit))
(defn mark-purged! [adapter id] ((:mark-purged! adapter) id))
(defn mark-purge-failed! [adapter id error-code]
  ((:mark-purge-failed! adapter) id error-code))
