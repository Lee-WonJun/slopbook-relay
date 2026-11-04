(ns slopbook.trust.domain.moderation)

(def report-reasons #{"harassment" "hate" "spam" "privacy" "other"})

(defn report-value [{:keys [id reporter-account-id resource-type resource-id reason now]}]
  (when-not (contains? report-reasons reason)
    (throw (ex-info "Report reason is invalid" {:code :invalid-report-reason})))
  {:id id
   :reporter_account_id reporter-account-id
   :resource_type resource-type
   :resource_id resource-id
   :reason reason
   :status "open"
   :created_at now})
