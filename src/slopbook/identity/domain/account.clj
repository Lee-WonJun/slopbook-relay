(ns slopbook.identity.domain.account)

(def maximum-active-characters 5)

(defn request-deletion [account now]
  (when (= "active" (:status account))
    (assoc account :status "deletion_pending" :updated_at now)))

(defn authorization-decision [account operation]
  (case (:status account)
    "active" :allowed
    "suspended" (if (= :read operation) :allowed :suspended)
    "deletion_pending" :deletion-pending
    "deleted" :deleted
    :invalid))
