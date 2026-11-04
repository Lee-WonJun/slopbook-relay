(ns slopbook.social.application.relationships
  (:require [slopbook.social.domain.relationship :as relationship]
            [slopbook.social.ports.relationships :as ports]))

(defn toggle-follow!
  [{:keys [store transaction events new-id now]} actor-character-id target-character-id]
  (ports/transact!
   transaction
   (fn []
     (let [current (ports/find-by-pair store actor-character-id target-character-id)
           transition (relationship/toggle-follow
                       (merge {:actor-character-id actor-character-id
                               :target-character-id target-character-id}
                              current)
                       {:relationship-id (new-id) :now (now)})]
       (ports/save! store (:relationship transition))
       (ports/publish! events (:event transition))
       (:relationship transition)))))
