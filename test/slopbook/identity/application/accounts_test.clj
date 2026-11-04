(ns slopbook.identity.application.accounts-test
  (:require [clojure.test :refer [deftest is]]
            [slopbook.identity.application.accounts :as accounts]))

(defn error-code [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo exception
      (:code (ex-data exception)))))

(deftest suspended-account-can-read-but-cannot-write
  (let [account-store {:find-or-create! (constantly {:id "account-1" :status "suspended"})
                       :find-by-id (constantly {:id "account-1" :status "suspended"})}
        auth {:verify-token (constantly {:subject "subject-1"})
              :bust-cache! (constantly true)}
        identity {:accounts account-store :auth auth}]
    (is (= "account-1" (:id (accounts/authenticate! identity "synthetic-token" :read))))
    (is (= "ACCOUNT_SUSPENDED"
           (error-code #(accounts/authenticate! identity "synthetic-token" :write))))))

(deftest account-deletion-requires-confirmation-and-uses-the-account-port
  (let [requested (atom [])
        identity {:accounts {:request-deletion!
                             (fn [account-id]
                               (swap! requested conj account-id)
                               {:id account-id :status "deletion_pending"})}}]
    (is (= "ACCOUNT_DELETION_CONFIRMATION_REQUIRED"
           (error-code #(accounts/request-deletion! identity "account-1" {}))))
    (is (= "deletion_pending"
           (:status (accounts/request-deletion! identity "account-1" {:confirm true}))))
    (is (= ["account-1"] @requested))))
