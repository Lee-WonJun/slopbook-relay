(ns slopbook.social.adapters.out.memory-notifications
  (:require [slopbook.social.ports.notifications :as notification-port])
  (:import [java.time Instant]
           [java.util Base64]))

(def encoder (.withoutPadding (Base64/getUrlEncoder)))

(defn- digest-key [digest]
  (.encodeToString encoder digest))

(defn adapter [state]
  (notification-port/assert-adapter!
   {:list-notifications
    (fn [{:keys [character-id cursor limit]}]
      (let [before? (fn [value]
                      (if-not cursor
                        true
                        (neg? (compare [(:created_at value) (:id value)]
                                       [(:created_at cursor) (:id cursor)]))))]
        (->> (:notifications @state)
             vals
             (filter #(and (= character-id (:target_character_id %)) (before? %)))
             (sort-by (juxt :created_at :id) #(compare %2 %1))
             (take limit)
             (mapv (fn [value]
                     (-> value
                         (select-keys [:id :type :resource_type :resource_id :post_id :reaction
                                       :actor_anonymous :unavailable :read_at :created_at :_actor])
                         (assoc :_created_at (:created_at value))))))))

    :mark-read!
    (fn [{:keys [character-id notification-id]}]
      (locking state
        (when-let [value (get-in @state [:notifications notification-id])]
          (when (= character-id (:target_character_id value))
            (let [now (str (Instant/now))]
              (swap! state assoc-in [:notifications notification-id :read_at]
                     (or (:read_at value) now))
              {:id notification-id :read_at (or (:read_at value) now)
               :unread_count (count (filter #(and (= character-id (:target_character_id %))
                                                   (nil? (:read_at %)))
                                             (vals (:notifications @state))))})))))

    :mark-all-read!
    (fn [{:keys [character-id before]}]
      (locking state
        (let [eligible (->> (:notifications @state)
                            vals
                            (filter #(and (= character-id (:target_character_id %))
                                          (nil? (:read_at %))
                                          (not (pos? (compare (:created_at %) before)))))
                            (map :id)
                            vec)
              now (str (Instant/now))]
          (swap! state
                 (fn [snapshot]
                   (reduce #(assoc-in %1 [:notifications %2 :read_at] now) snapshot eligible)))
          {:updated (count eligible) :unread_count 0})))

    :unread-count
    (fn [character-id]
      (count (filter #(and (= character-id (:target_character_id %)) (nil? (:read_at %)))
                     (vals (:notifications @state)))))

    :save-ticket!
    (fn [{:keys [character-id digest expires-at]}]
      (swap! state assoc-in [:websocket-tickets (digest-key digest)]
             {:character-id character-id :expires-at expires-at :consumed? false})
      true)

    :consume-ticket!
    (fn [digest]
      (locking state
        (let [key (digest-key digest)
              ticket (get-in @state [:websocket-tickets key])]
          (when (and ticket (not (:consumed? ticket))
                     (.isAfter (Instant/parse (:expires-at ticket)) (Instant/now)))
            (swap! state assoc-in [:websocket-tickets key :consumed?] true)
            (:character-id ticket)))))}))
