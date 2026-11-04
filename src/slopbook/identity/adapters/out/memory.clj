(ns slopbook.identity.adapters.out.memory
  (:require [slopbook.identity.domain.character :as character]
            [slopbook.identity.ports.characters :as character-port]
            [slopbook.platform.errors :as errors]
            [slopbook.shared.domain.media :as shared-media])
  (:import [java.time Instant]))

(defn- purge-item [media-id media]
  (let [keys (mapv :key (:variants media))]
    (when (and media-id (seq keys))
      {:id media-id :keys keys})))

(defn- mark-media-deleted [snapshot media-id deleted-at]
  (if-not media-id
    snapshot
    (let [variants (get-in snapshot [:media media-id :variants])]
      (update-in snapshot [:media media-id]
                 #(cond-> (assoc % :status "deleted" :deleted_at deleted-at)
                    (seq variants) (assoc :purge_available_at deleted-at))))))

(defn adapter [state]
  (character-port/assert-adapter!
   {:list-owned
   (fn [account-id]
     (->> (:characters @state)
          vals
          (filter #(and (= account-id (:account_id %)) (= "active" (:status %))))
          (sort-by :created_at)
          (mapv character/owned-character)))

   :find-internal
   (fn [id] (get-in @state [:characters id]))

   :find-public-by-id
   (fn [id]
     (some-> (get-in @state [:characters id]) character/public-character))

   :find-public-by-handle
   (fn [handle]
     (when-let [id (get-in @state [:character-handles handle])]
       (let [value (get-in @state [:characters id])]
         (when (= "active" (:status value))
           (character/public-character value)))))

   :handle-exists?
   (fn [handle] (contains? (:character-handles @state) handle))

   :insert-if-below-limit!
   (fn [account-id limit value]
     (locking state
       (let [snapshot @state
             active-count (->> (:characters snapshot)
                               vals
                               (filter #(and (= account-id (:account_id %))
                                             (= "active" (:status %))))
                               count)]
         (when (contains? (:character-handles snapshot) (:handle value))
           (errors/fail! 409 "HANDLE_TAKEN" :conflict))
         (when (< active-count limit)
           (swap! state (fn [snapshot]
                          (-> snapshot
                              (assoc-in [:characters (:id value)] value)
                              (assoc-in [:character-handles (:handle value)] (:id value)))))
           value))))

   :update!
   (fn [value]
     (locking state
       (let [current (get-in @state [:characters (:id value)])
             old-id (:avatar_media_id current)
             new-id (:avatar_media_id value)
             changed? (not= old-id new-id)
             old-media (get-in @state [:media old-id])
             new-media (get-in @state [:media new-id])
             next-value (assoc value :avatar_upload
                               (when new-id (shared-media/avatar-upload new-media)))]
         (when (and changed? new-id
                    (not (and (= "ready" (:status new-media))
                              (= (:id value) (:owner_character_id new-media))
                              (= "avatar" (:purpose new-media)))))
           (throw (ex-info "Avatar media was no longer attachable" {:media-id new-id})))
         (swap! state
                (fn [snapshot]
                  (cond-> (assoc-in snapshot [:characters (:id value)] next-value)
                    (and changed? new-id) (assoc-in [:media new-id :status] "attached")
                    (and changed? old-id)
                    (mark-media-deleted old-id (str (Instant/now))))))
         (cond-> next-value
           (and changed? (purge-item old-id old-media))
           (assoc :_purges [(purge-item old-id old-media)])))))

   :delete!
   (fn [character-id now]
     (locking state
       (let [current (get-in @state [:characters character-id])
             media-id (:avatar_media_id current)
             media (get-in @state [:media media-id])]
         (when (= "active" (:status current))
           (swap! state
                  (fn [snapshot]
                    (-> snapshot
                        (update-in [:characters character-id]
                                   #(assoc % :status "deleted" :deleted_at now :updated_at now))
                        (mark-media-deleted media-id now))))
           (cond-> {:deleted true}
             (purge-item media-id media) (assoc :purges [(purge-item media-id media)]))))))}))
