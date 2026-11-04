(ns slopbook.social.application.notifications
  (:require [slopbook.identity.application.characters :as characters]
            [slopbook.platform.cursor :as cursor]
            [slopbook.platform.errors :as errors]
            [slopbook.platform.token :as token]
            [slopbook.social.ports.notifications :as notification-port])
  (:import [java.time Instant]))

(def page-size 20)
(def read-keys #{:actor_character_id :read})
(def read-all-keys #{:actor_character_id :before})
(def ticket-keys #{:actor_character_id})

(defn- owned-id [{:keys [identity]} account-id actor-id]
  (:id (characters/require-owned-internal identity account-id actor-id)))

(defn list! [{:keys [notifications cursor-secret] :as social} account-id actor-id encoded-cursor]
  (let [character-id (owned-id social account-id actor-id)
        scope (str "notifications:" character-id)
        boundary (cursor/decode-for cursor-secret encoded-cursor scope)
        rows (notification-port/list-notifications
              notifications {:character-id character-id :cursor boundary :limit (inc page-size)})
        page (->> rows
                  (take page-size)
                  (mapv (fn [row]
                          (cond-> row
                            (:_actor row) (assoc :actor (characters/public-projection (:_actor row)))
                            true (dissoc :_actor)))))
        more? (> (count rows) page-size)
        last-row (peek page)]
    {:items (mapv #(dissoc % :_created_at) page)
     :next_cursor (when (and more? last-row)
                    (cursor/encode cursor-secret
                                   {:scope scope :created_at (:_created_at last-row)
                                    :id (:id last-row)}))
     :unread_count (notification-port/unread-count notifications character-id)}))

(defn mark-read! [{:keys [notifications] :as social} account-id notification-id body]
  (errors/assert-allowed-keys! body read-keys)
  (when-not (true? (:read body))
    (errors/fail! 400 "NOTIFICATION_READ_INVALID" :validation))
  (let [character-id (owned-id social account-id (:actor_character_id body))]
    (or (notification-port/mark-read!
         notifications {:character-id character-id :notification-id notification-id})
        (errors/fail! 404 "NOTIFICATION_NOT_FOUND" :not-found))))

(defn mark-all-read! [{:keys [notifications] :as social} account-id body]
  (errors/assert-allowed-keys! body read-all-keys)
  (let [character-id (owned-id social account-id (:actor_character_id body))
        before (try (str (Instant/parse (:before body)))
                    (catch Exception _
                      (errors/fail! 400 "NOTIFICATION_WATERMARK_INVALID" :validation)))]
    (notification-port/mark-all-read!
     notifications {:character-id character-id :before before})))

(defn issue-ticket! [{:keys [notifications now] :as social} account-id body]
  (errors/assert-allowed-keys! body ticket-keys)
  (let [character-id (owned-id social account-id (:actor_character_id body))
        raw (token/random-token)
        expires-at (str (.plusSeconds (Instant/parse (now)) 30))]
    (notification-port/save-ticket!
     notifications {:character-id character-id :digest (token/digest raw) :expires-at expires-at})
    {:ticket raw :expires_at expires-at}))

(defn consume-ticket! [{:keys [notifications]} raw]
  (when-not (and (string? raw) (<= 32 (count raw) 128))
    (errors/fail! 401 "WEBSOCKET_AUTH_REQUIRED" :unauthorized))
  (or (notification-port/consume-ticket! notifications (token/digest raw))
      (errors/fail! 401 "WEBSOCKET_AUTH_REQUIRED" :unauthorized)))
