(ns slopbook.identity.application.accounts
  (:require [slopbook.identity.domain.account :as account]
            [slopbook.identity.ports.accounts :as account-port]
            [slopbook.identity.ports.authentication :as auth-port]
            [slopbook.platform.errors :as errors]))

(def deletion-request-keys #{:confirm})

(defn- authorize! [internal-account operation]
  (case (account/authorization-decision internal-account operation)
    :allowed internal-account
    :suspended (errors/fail! 403 "ACCOUNT_SUSPENDED" :forbidden)
    :deletion-pending (errors/fail! 403 "ACCOUNT_DELETION_PENDING" :forbidden)
    :deleted (errors/fail! 403 "ACCOUNT_DELETED" :forbidden)
    (errors/fail! 401 "AUTH_INVALID" :unauthorized)))

(defn authenticate! [{:keys [auth accounts]} token operation]
  (let [{:keys [subject]} (auth-port/verify-token auth token)
        internal-account (account-port/find-or-create! accounts subject)]
    (authorize! internal-account operation)))

(defn authorize-development! [{:keys [accounts]} account-id operation]
  (authorize! (or (account-port/find-by-id accounts account-id)
                  (account-port/find-or-create! accounts (str "dev:" account-id)))
              operation))

(defn request-deletion! [{:keys [accounts]} account-id body]
  (errors/assert-allowed-keys! body deletion-request-keys)
  (when-not (true? (:confirm body))
    (errors/fail! 400 "ACCOUNT_DELETION_CONFIRMATION_REQUIRED" :validation))
  (or (account-port/request-deletion! accounts account-id)
      (errors/fail! 404 "ACCOUNT_NOT_FOUND" :not-found)))
