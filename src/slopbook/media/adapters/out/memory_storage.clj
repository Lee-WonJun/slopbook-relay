(ns slopbook.media.adapters.out.memory-storage
  (:require [slopbook.media.ports.storage :as storage-port])
  (:import [java.util Base64]))

(defn adapter [state]
  (storage-port/assert-adapter!
   {:put! (fn [{:keys [key bytes]}]
            (swap! state assoc-in [:media-objects key] bytes)
            {:key key
             :url (str "data:image/webp;base64,"
                       (.encodeToString (Base64/getEncoder) bytes))})
    :delete! (fn [keys]
               (swap! state update :media-objects #(apply dissoc (or % {}) keys))
               true)}))
