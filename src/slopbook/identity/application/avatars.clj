(ns slopbook.identity.application.avatars
  (:require [slopbook.identity.application.characters :as characters]
            [slopbook.media.application.assets :as media-assets]))

(defn update!
  [identity media account-id character-id patch]
  (when (contains? patch :avatar_media_id)
    (let [current (characters/require-owned-internal identity account-id character-id)
          media-id (:avatar_media_id patch)]
      (when (and media-id (not= media-id (:avatar_media_id current)))
        (media-assets/ready-assets! media character-id "avatar" [media-id]))))
  (let [{:keys [owned purges]}
        (characters/update-result! identity account-id character-id patch)]
    (media-assets/purge-keys! media purges)
    owned))

(defn delete!
  [identity media account-id character-id body]
  (let [{:keys [deleted purges]}
        (characters/delete-result! identity account-id character-id body)]
    (media-assets/purge-keys! media purges)
    (boolean deleted)))
