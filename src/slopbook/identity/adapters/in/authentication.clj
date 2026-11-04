(ns slopbook.identity.adapters.in.authentication
  (:require [clojure.string :as str]
            [slopbook.identity.application.accounts :as accounts]
            [slopbook.platform.errors :as errors]))

(def bearer-pattern #"(?i)^Bearer ([^\s]+)$")

(defn- bearer-token [request]
  (when-let [value (get-in request [:headers "authorization"])]
    (second (re-matches bearer-pattern (str/trim value)))))

(defn require-account-id [identity config request operation]
  (let [authorization (get-in request [:headers "authorization"])
        token (bearer-token request)]
    (cond
      token (:id (accounts/authenticate! identity token operation))
      authorization (errors/fail! 401 "AUTH_INVALID" :unauthorized)
      (:dev-auth? config)
      (:id (accounts/authorize-development!
            identity
            (or (get-in request [:headers "x-slopbook-dev-account"]) "demo-account")
            operation))
      :else (errors/fail! 401 "AUTH_REQUIRED" :unauthorized))))

(defn optional-account-id [identity config request operation]
  (let [authorization (get-in request [:headers "authorization"])
        development-subject (get-in request [:headers "x-slopbook-dev-account"])]
    (when (or authorization (and (:dev-auth? config) development-subject))
      (require-account-id identity config request operation))))
