(ns slopbook.messaging.ports.messages)

(def required-operations
  #{:create-conversation! :list-conversations :conversation-context :messages
    :reserve! :publish! :fail-reservation! :mark-read! :hide-message!})

(defn assert-adapter! [adapter]
  (let [missing (remove #(fn? (get adapter %)) required-operations)]
    (when (seq missing)
      (throw (ex-info "Messaging adapter is incomplete" {:missing (vec missing)})))
    adapter))

(defn create-conversation! [adapter command] ((:create-conversation! adapter) command))
(defn list-conversations [adapter query] ((:list-conversations adapter) query))
(defn conversation-context [adapter query] ((:conversation-context adapter) query))
(defn messages [adapter query] ((:messages adapter) query))
(defn reserve! [adapter command] ((:reserve! adapter) command))
(defn publish! [adapter command] ((:publish! adapter) command))
(defn fail-reservation! [adapter command] ((:fail-reservation! adapter) command))
(defn mark-read! [adapter command] ((:mark-read! adapter) command))
(defn hide-message! [adapter command] ((:hide-message! adapter) command))
