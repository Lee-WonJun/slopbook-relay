(ns slopbook.identity.adapters.out.memory-accounts
  (:require [slopbook.identity.domain.account :as account]
            [slopbook.identity.ports.accounts :as account-port]))

(defn adapter [state new-id now]
  (account-port/assert-adapter!
   {:find-or-create!
    (fn [auth-subject]
      (locking state
        (if-let [account-id (get-in @state [:account-subjects auth-subject])]
          (get-in @state [:accounts account-id])
          (let [account {:id (new-id)
                         :auth_subject auth-subject
                         :status "active"
                         :created_at (now)
                         :updated_at (now)}]
            (swap! state (fn [snapshot]
                           (-> snapshot
                               (assoc-in [:accounts (:id account)] account)
                               (assoc-in [:account-subjects auth-subject] (:id account)))))
            account))))

    :find-by-id
    (fn [account-id]
      (get-in @state [:accounts account-id]))

    :request-deletion!
    (fn [account-id]
      (locking state
        (let [deleted-at (now)
              next-account (account/request-deletion
                            (get-in @state [:accounts account-id]) deleted-at)]
          (when next-account
            (swap! state
                   (fn [snapshot]
                     (-> (assoc-in snapshot [:accounts account-id] next-account)
                         (update :characters
                                 (fn [characters]
                                   (into {}
                                         (map (fn [[character-id character]]
                                                [character-id
                                                 (if (and (= account-id (:account_id character))
                                                          (not= "deleted" (:status character)))
                                                   (assoc character :status "deleted"
                                                          :deleted_at deleted-at
                                                          :updated_at deleted-at)
                                                   character)]))
                                         characters))))))
            next-account))))}))
