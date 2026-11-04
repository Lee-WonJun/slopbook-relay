(ns slopbook.social.application.reactions
  (:require [slopbook.identity.application.characters :as characters]
            [slopbook.platform.errors :as errors]
            [slopbook.social.ports.reactions :as reaction-port]
            [slopbook.transformation.application.reactions :as decisions]))

(def body-keys #{:actor_character_id})
(def target-types #{"post" "comment"})

(defn toggle!
  [{:keys [reactions identity transformation]} account-id target-type target-id body]
  (errors/assert-allowed-keys! body body-keys)
  (when-not (contains? target-types target-type)
    (errors/fail! 400 "REACTION_TARGET_INVALID" :validation))
  (let [actor (characters/require-owned-internal identity account-id (:actor_character_id body))
        target (reaction-port/target-public reactions
                                            {:type target-type
                                             :id target-id
                                             :actor-character-id (:id actor)})]
    (when-not target
      (errors/fail! 404 "REACTION_TARGET_NOT_FOUND" :not-found))
    (let [result (reaction-port/toggle!
                  reactions
                  {:type target-type
                   :target target
                   :actor-character-id (:id actor)
                   :decide #(decisions/choose! transformation actor target)})]
      (assoc result :display (get decisions/display (:reaction result))))))
