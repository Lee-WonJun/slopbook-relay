(ns slopbook.social.application.reposts
  (:require [slopbook.identity.application.characters :as characters]
            [slopbook.platform.errors :as errors]
            [slopbook.social.ports.reposts :as repost-port]))

(def body-keys #{:actor_character_id})

(defn toggle!
  [{:keys [reposts identity]} account-id post-id body]
  (errors/assert-allowed-keys! body body-keys)
  (let [actor (characters/require-owned-internal identity account-id (:actor_character_id body))
        target (repost-port/target-public reposts
                                          {:id post-id :actor-character-id (:id actor)})]
    (when-not target
      (errors/fail! 404 "REPOST_TARGET_NOT_FOUND" :not-found))
    (when (= (:account_id actor) (:owner-account-id target))
      (errors/fail! 409 "REPOST_OWN_POST" :conflict))
    (repost-port/toggle!
     reposts
     {:target target
      :actor actor
      :actor-character-id (:id actor)
      :persona-snapshot (characters/public-projection actor)})))
