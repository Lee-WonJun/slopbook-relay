(ns slopbook.client.active-character)

(def storage-key "slopbook.active-character")

(defn read-preference
  ([] (read-preference js/localStorage))
  ([storage]
   (try
     (some-> (.getItem storage storage-key) not-empty)
     (catch :default _ nil))))

(defn store-preference!
  ([character-id] (store-preference! js/localStorage character-id))
  ([storage character-id]
   (try
     (if character-id
       (.setItem storage storage-key character-id)
       (.removeItem storage storage-key))
     (catch :default _ nil))))

(defn select-owned-id [owned-characters current-id preferred-id]
  (let [ids (set (map #(get-in % [:public_character :id]) owned-characters))]
    (or (when (contains? ids current-id) current-id)
        (when (contains? ids preferred-id) preferred-id)
        (get-in owned-characters [0 :public_character :id]))))
