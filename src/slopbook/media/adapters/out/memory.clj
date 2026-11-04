(ns slopbook.media.adapters.out.memory
  (:require [clojure.set :as set]
            [slopbook.media.domain.asset :as asset]
            [slopbook.media.ports.assets :as asset-port])
  (:import [java.time Instant]))

(defn adapter [state]
  (asset-port/assert-adapter!
   {:insert-upload!
    (fn [value]
      (let [value (assoc value :expires_at
                         (str (.plusSeconds (Instant/parse (:created_at value)) (* 30 60))))]
        (swap! state assoc-in [:media (:id value)] value)
        (asset/public-asset value)))
    :find-owned
    (fn [{:keys [id owner-character-id]}]
      (let [value (get-in @state [:media id])]
        (when (= owner-character-id (:owner_character_id value))
          (asset/public-asset value))))
    :mark-processing!
    (fn [id] (swap! state assoc-in [:media id :status] "processing") true)
    :mark-ready!
    (fn [{:keys [id] :as command}]
      (swap! state update-in [:media id]
             merge (-> command
                       (select-keys [:verified-type :width :height :variants :average-color
                                     :alt-text :alt-language])
                       (set/rename-keys {:verified-type :verified_input_type
                                                 :average-color :average_color
                                                 :alt-text :alt_text
                                                 :alt-language :alt_language})
                       (assoc :status "ready")))
      (asset/public-asset (get-in @state [:media id])))
    :mark-failed!
    (fn [{:keys [id status error-code purge-keys]}]
      (swap! state update-in [:media id] merge
             (cond-> {:status status :error_code error-code}
               (seq purge-keys)
               (assoc :variants (mapv (fn [key] {:key key}) purge-keys)
                      :purge_available_at (str (Instant/now)))))
      true)
    :ready-owned
    (fn [{:keys [ids owner-character-id purpose]}]
      (->> ids
           (keep (fn [id]
                   (let [value (get-in @state [:media id])]
                     (when (and (= "ready" (:status value))
                                (= owner-character-id (:owner_character_id value))
                                (= purpose (:purpose value))
                                (:expires_at value)
                                (.isAfter (Instant/parse (:expires_at value)) (Instant/now)))
                       (asset/public-asset value)))))
           vec))
    :delete-unattached!
    (fn [{:keys [id owner-character-id]}]
      (locking state
        (let [value (get-in @state [:media id])
              deleted-at (str (Instant/now))]
          (when (and (= owner-character-id (:owner_character_id value))
                     (contains? #{"validating" "processing" "inspecting" "ready"
                                  "rejected" "blocked" "failed"} (:status value)))
            (swap! state update-in [:media id]
                   #(assoc % :status "deleted" :deleted_at deleted-at
                           :purge_available_at (when (seq (:variants value)) deleted-at)))
            {:id id :keys (mapv :key (:variants value))}))))

    :expire-unattached!
    (fn []
      (locking state
        (let [now (Instant/now)
              expired (->> (:media @state)
                           vals
                           (filter #(and (:expires_at %)
                                         (not (contains? #{"attached" "deleted"} (:status %)))
                                         (not (.isAfter (Instant/parse (:expires_at %)) now))))
                           (mapv :id))]
          (swap! state
                 (fn [snapshot]
                   (reduce (fn [value id]
                             (let [variants (get-in value [:media id :variants])]
                               (update-in value [:media id]
                                          #(assoc % :status "deleted" :deleted_at (str now)
                                                  :purge_available_at (when (seq variants)
                                                                        (str now))))))
                           snapshot expired)))
          (count expired))))

    :pending-purges
    (fn [limit]
      (let [now (Instant/now)]
        (->> (:media @state)
             vals
             (filter #(and (seq (:variants %)) (:purge_available_at %)
                           (nil? (:purged_at %))
                           (not (.isAfter (Instant/parse (:purge_available_at %)) now))))
             (sort-by (juxt :purge_available_at :id))
             (take limit)
             (mapv (fn [value]
                     {:id (:id value) :keys (mapv :key (:variants value))})))))

    :mark-purged!
    (fn [id]
      (swap! state update-in [:media id]
             #(assoc % :variants nil :purged_at (str (Instant/now))
                     :purge_available_at nil :last_purge_error_code nil))
      true)

    :mark-purge-failed!
    (fn [id error-code]
      (swap! state update-in [:media id]
             #(let [attempt (inc (or (:purge_attempt_count %) 0))
                    delay (min 3600 (* 5 (bit-shift-left 1 (min 9 (dec attempt)))))]
                (assoc % :purge_attempt_count attempt
                       :last_purge_error_code error-code
                       :purge_available_at (str (.plusSeconds (Instant/now) delay)))))
      true)}))
