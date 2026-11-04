(ns slopbook.social.domain.relationship)

(defn toggle-follow
  [{:keys [id actor-character-id target-character-id active?]} {:keys [relationship-id now]}]
  (when (= actor-character-id target-character-id)
    (throw (ex-info "A character cannot follow itself" {:code :self-follow})))
  (let [restoring? (and id (not active?))
        removing? (and id active?)
        relationship {:id (or id relationship-id)
                      :actor_character_id actor-character-id
                      :target_character_id target-character-id
                      :active (not removing?)
                      :updated_at now}]
    {:relationship relationship
     :event {:event_type (cond removing? "social.follow.removed"
                                restoring? "social.follow.restored"
                                :else "social.follow.created")
             :resource_id (:id relationship)}}))
