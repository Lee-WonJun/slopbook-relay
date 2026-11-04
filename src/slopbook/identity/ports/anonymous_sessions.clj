(ns slopbook.identity.ports.anonymous-sessions)

(def required-operations #{:insert! :find! :touch!})

(defn assert-adapter! [adapter]
  (let [missing (remove #(fn? (get adapter %)) required-operations)]
    (when (seq missing)
      (throw (ex-info "Anonymous session adapter is incomplete" {:missing (vec missing)})))
    adapter))

(defn insert! [adapter value] ((:insert! adapter) value))
(defn find! [adapter id] ((:find! adapter) id))
(defn touch! [adapter id] ((:touch! adapter) id))
