(ns slopbook.client.message-state)

(defn remove-visible-message [db message-id]
  (let [messages (get-in db [:messages :active :messages :items])
        remaining (vec (remove #(= message-id (:id %)) messages))
        conversation-id (get-in db [:messages :active :conversation :id])
        preview (:text (peek remaining))]
    (-> db
        (assoc-in [:messages :active :messages :items] remaining)
        (update-in [:messages :items]
                   (fn [conversations]
                     (when conversations
                       (mapv (fn [conversation]
                               (if (= conversation-id (:id conversation))
                                 (assoc conversation :preview preview)
                                 conversation))
                             conversations)))))))
