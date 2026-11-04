(ns slopbook.social.ports.notifications)

(def required-operations
  #{:list-notifications :mark-read! :mark-all-read! :unread-count
    :save-ticket! :consume-ticket!})

(defn assert-adapter! [adapter]
  (let [missing (remove #(fn? (get adapter %)) required-operations)]
    (when (seq missing)
      (throw (ex-info "Social notification adapter is incomplete" {:missing (vec missing)})))
    adapter))

(defn list-notifications [adapter query] ((:list-notifications adapter) query))
(defn mark-read! [adapter command] ((:mark-read! adapter) command))
(defn mark-all-read! [adapter command] ((:mark-all-read! adapter) command))
(defn unread-count [adapter character-id] ((:unread-count adapter) character-id))
(defn save-ticket! [adapter command] ((:save-ticket! adapter) command))
(defn consume-ticket! [adapter digest] ((:consume-ticket! adapter) digest))
