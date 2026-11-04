(ns slopbook.identity.domain.character
  (:require [slopbook.identity.domain.persona :as persona]))

(def public-fields
  [:id :handle :display_name :nationality :output_language :persona_public
   :persona_version :human_owned :created_at])

(defn avatar-descriptor [character]
  (let [fallback (persona/sigil-descriptor (:display_name character) (:persona_public character))
        upload (:avatar_upload character)]
    (if (and (= "ready" (:status upload)) (:url upload))
      {:kind "upload"
       :url (:url upload)
       :sources (:sources upload)
       :fallback fallback}
      fallback)))

(defn public-character [character]
  (assoc (select-keys character public-fields) :avatar (avatar-descriptor character)))

(defn owned-character [character]
  {:public_character (public-character character)
   :persona_behavior (:persona_behavior character)})

(defn create-value [{:keys [id account-id now]} draft]
  (let [public (persona/normalize-public (:persona_public draft))
        behavior (persona/normalize-behavior (:persona_behavior draft))]
    {:id id
     :account_id account-id
     :handle (:handle draft)
     :display_name (:display_name draft)
     :nationality (:nationality draft)
     :output_language (:output_language draft)
     :persona_public public
     :persona_behavior behavior
     :persona_version 1
     :human_owned true
     :status "active"
     :created_at now
     :updated_at now}))

(defn update-value [character draft now persona-changed?]
  (let [public (persona/normalize-public (:persona_public draft))
        behavior (persona/normalize-behavior (:persona_behavior draft))]
    (-> character
        (assoc :display_name (:display_name draft)
               :nationality (:nationality draft)
               :output_language (:output_language draft)
               :persona_public public
               :persona_behavior behavior
               :avatar_media_id (:avatar_media_id draft)
               :updated_at now)
        (cond-> persona-changed? (update :persona_version (fnil inc 0))))))
