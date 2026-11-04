(ns slopbook.identity.application.anonymous-sessions
  (:require [slopbook.identity.domain.anonymous :as anonymous]
            [slopbook.identity.ports.anonymous-sessions :as session-port]
            [slopbook.identity.ports.human-verification :as verification-port]
            [slopbook.platform.errors :as errors]
            [slopbook.platform.rate-limit :as rate-limit]
            [slopbook.platform.token :as token]))

(defn create!
  [{:keys [anonymous-sessions anonymous-rate new-id now]} remote-ip]
  (rate-limit/enforce! anonymous-rate :session-create remote-ip
                       10 (* 60 60 1000) "ANON_RATE_LIMITED")
  (let [secret (token/random-token)
        value {:id (new-id) :deletion_token_digest (token/digest secret)
               :status "active" :created_at (now) :last_used_at (now)}]
    (session-port/insert! anonymous-sessions value)
    {:anonymous_session_id (:id value) :deletion_token secret}))

(defn require-active!
  [{:keys [anonymous-sessions]} id]
  (let [value (session-port/find! anonymous-sessions id)]
    (when-not (= "active" (:status value))
      (errors/fail! 403 "ANON_SESSION_INVALID" :forbidden))
    value))

(defn authorize-delete!
  [{:keys [anonymous-sessions] :as identity} id deletion-token]
  (let [value (require-active! identity id)]
    (when-not (token/digest-token? deletion-token (:deletion_token_digest value))
      (errors/fail! 403 "ANON_DELETE_FORBIDDEN" :forbidden))
    value))

(defn touch! [{:keys [anonymous-sessions]} id]
  (session-port/touch! anonymous-sessions id))

(def valid-persona? anonymous/valid-persona?)
(def snapshot anonymous/snapshot)
(def transformation-character anonymous/transformation-character)

(defn verify-human! [{:keys [human-verification]} challenge]
  (verification-port/verify! human-verification challenge))
