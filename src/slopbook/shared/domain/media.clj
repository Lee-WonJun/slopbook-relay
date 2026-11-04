(ns slopbook.shared.domain.media)

(defn public-asset [value]
  (when value
    (let [sources (->> (:variants value)
                       (remove :placeholder)
                       (mapv #(dissoc % :key :placeholder)))
          placeholder (some #(when (:placeholder %) (dissoc % :key :placeholder))
                            (:variants value))]
      (cond-> {:id (:id value)
               :purpose (:purpose value)
               :status (:status value)
               :width (:width value)
               :height (:height value)
               :average_color (:average_color value)
               :created_at (:created_at value)}
        (= "ready" (:status value))
        (assoc :variants sources
               :placeholder placeholder
               :alt_text (:alt_text value)
               :alt_language (:alt_language value))

        (contains? #{"rejected" "blocked" "failed"} (:status value))
        (assoc :error_code (:error_code value))))))

(defn avatar-upload [value]
  (when value
    (let [public (public-asset (assoc value :status "ready"))
          sources (:variants public)
          largest (last (sort-by :width sources))]
      (when (:url largest)
        {:status "ready"
         :url (:url largest)
         :sources sources}))))
