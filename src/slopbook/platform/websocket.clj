(ns slopbook.platform.websocket
  (:require [org.httpkit.server :as http-kit]
            [slopbook.platform.http :as http]
            [slopbook.platform.json :as json]))

(defn hub [] (atom {}))

(defn- remove-channel! [hub character-id channel]
  (swap! hub
         (fn [subscriptions]
           (let [remaining (disj (get subscriptions character-id #{}) channel)]
             (if (seq remaining)
               (assoc subscriptions character-id remaining)
               (dissoc subscriptions character-id))))))

(defn deliver! [hub event]
  (doseq [channel (get @hub (:character_id event) #{})]
    (try
      (http-kit/send! channel (json/write-value event))
      (catch Exception _
        (remove-channel! hub (:character_id event) channel))))
  true)

(defn notification-handler [hub consume-ticket!]
  (fn [request]
    (if (:websocket? request)
      (let [character-id (consume-ticket! (get-in request [:query-params "ticket"]))]
        (http-kit/as-channel
         request
         {:on-open (fn [channel]
                     (swap! hub update character-id (fnil conj #{}) channel))
          :on-close (fn [channel _]
                      (remove-channel! hub character-id channel))
          :on-error (fn [channel _]
                      (remove-channel! hub character-id channel))}))
      (http/json-response 426 {:error {:code "WEBSOCKET_REQUIRED"
                                       :message "WebSocket upgrade required."
                                       :retryable false}}))))

(defn status-handler [status-fn]
  (fn [request]
    (if (:websocket? request)
      (http-kit/as-channel
       request
       {:on-open (fn [channel]
                   (http-kit/send! channel (json/write-value {:type "status.snapshot"
                                                               :payload (status-fn)})))
        :on-receive (fn [channel _]
                      (http-kit/send! channel (json/write-value {:type "status.snapshot"
                                                                  :payload (status-fn)})))})
      (http/json-response 426 {:error {:code "WEBSOCKET_REQUIRED"
                                       :message "WebSocket upgrade required."
                                       :retryable false}}))))
