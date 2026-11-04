(ns slopbook.identity.application.characters
  (:require [clojure.string :as str]
            [slopbook.identity.domain.account :as account]
            [slopbook.identity.domain.character :as character]
            [slopbook.identity.domain.persona :as persona]
            [slopbook.identity.ports.characters :as character-port]
            [slopbook.platform.errors :as errors]
            [slopbook.platform.token :as token]
            [slopbook.shared.domain.text :as text])
  (:import [java.util Locale UUID]))

(def create-keys
  #{:handle :display_name :nationality :output_language :persona_public :persona_behavior})

(def update-fields
  #{:actor_character_id :display_name :nationality :output_language :avatar_media_id
    :persona_public :persona_behavior})

(def delete-keys #{:actor_character_id})
(def country-codes (set (Locale/getISOCountries)))

(defn- valid-nationality? [value]
  (or (nil? value) (contains? country-codes value)))

(defn- valid-media-id? [value]
  (or (nil? value)
      (try (UUID/fromString value) true (catch Exception _ false))))

(defn- normalize-draft [draft]
  (cond-> draft
    (contains? draft :handle) (update :handle #(some-> % str str/lower-case))
    (contains? draft :display_name) (update :display_name text/normalize-text)
    (contains? draft :nationality) (update :nationality #(some-> % str str/upper-case))))

(defn- assert-valid-draft! [draft]
  (when-not (and (persona/valid-character-draft? draft)
                 (valid-nationality? (:nationality draft))
                 (valid-media-id? (:avatar_media_id draft)))
    (errors/fail! 400 "PERSONA_INVALID" :validation
                  {:public (persona/validate-public (:persona_public draft))
                   :behavior (persona/validate-behavior (:persona_behavior draft))
                   :character (cond-> []
                                (not (valid-nationality? (:nationality draft)))
                                (conj {:field :nationality :rule :iso-3166-1-alpha-2})
                                (not (valid-media-id? (:avatar_media_id draft)))
                                (conj {:field :avatar_media_id :rule :uuid}))})))

(defn prepare-draft!
  "Validates and normalizes a character draft at the identity boundary. Other
  application contexts may use the normalized value without importing private
  identity domain namespaces."
  [draft]
  (errors/assert-allowed-keys! draft create-keys)
  (let [draft (normalize-draft draft)]
    (assert-valid-draft! draft)
    (-> draft
        (update :persona_public persona/normalize-public)
        (update :persona_behavior persona/normalize-behavior))))

(defn greeting-fingerprint
  "Returns a one-way binding for the complete character draft used by the
  first-post preview. It intentionally includes private voice behavior but
  never exposes that behavior in a public projection."
  [draft-or-character]
  (let [value (-> (select-keys draft-or-character
                               [:handle :display_name :nationality :output_language
                                :persona_public :persona_behavior])
                  normalize-draft
                  (update :persona_public persona/normalize-public)
                  (update :persona_behavior persona/normalize-behavior))]
    (token/ephemeral-key "character-greeting:v1" (text/canonical-json value))))

(defn create! [{:keys [store new-id now]} account-id draft]
  (let [draft (prepare-draft! draft)]
    (when (character-port/handle-exists? store (:handle draft))
      (errors/fail! 409 "HANDLE_TAKEN" :conflict))
    (let [value (character/create-value {:id (new-id) :account-id account-id :now (now)} draft)
          stored (character-port/insert-if-below-limit!
                  store account-id account/maximum-active-characters value)]
      (when-not stored
        (errors/fail! 409 "CHARACTER_LIMIT_REACHED" :conflict
                      {:limit account/maximum-active-characters}))
      (character/owned-character stored))))

(defn list-owned [{:keys [store]} account-id]
  (character-port/list-owned store account-id))

(defn owned-summary [identity account-id]
  (let [characters (list-owned identity account-id)
        count-owned (count characters)]
    {:owned_characters characters
     :character_limit account/maximum-active-characters
     :remaining_character_slots (max 0 (- account/maximum-active-characters count-owned))}))

(defn public-by-handle [{:keys [store]} handle]
  (or (character-port/find-public-by-handle store handle)
      (errors/fail! 404 "CHARACTER_NOT_FOUND" :not-found)))

(defn require-owned-internal [{:keys [store]} account-id character-id]
  (let [character (character-port/find-internal store character-id)]
    (when-not (and character (= account-id (:account_id character)))
      (errors/fail! 403 "CHARACTER_NOT_OWNED" :forbidden))
    (when-not (= "active" (:status character))
      (errors/fail! 403 "CHARACTER_SUSPENDED" :forbidden))
    character))

(defn update-result! [{:keys [store now] :as identity} account-id character-id patch]
  (errors/assert-allowed-keys! patch update-fields)
  (when-not (= character-id (:actor_character_id patch))
    (errors/fail! 403 "CHARACTER_NOT_OWNED" :forbidden))
  (let [current (require-owned-internal identity account-id character-id)
        public-present? (contains? patch :persona_public)
        behavior-present? (contains? patch :persona_behavior)]
    (when (not= public-present? behavior-present?)
      (errors/fail! 400 "PERSONA_REPLACEMENT_INCOMPLETE" :validation))
    (let [draft (normalize-draft
                 (merge (select-keys current [:handle :display_name :nationality :output_language
                                              :avatar_media_id :persona_public :persona_behavior])
                        (dissoc patch :actor_character_id)))]
      (assert-valid-draft! draft)
      (let [updated (character/update-value current draft (now) public-present?)]
        (let [stored (character-port/update! store updated)]
          {:owned (character/owned-character stored)
           :purges (:_purges stored)})))))

(defn update! [identity account-id character-id patch]
  (:owned (update-result! identity account-id character-id patch)))

(defn delete-result! [{:keys [store now] :as identity} account-id character-id body]
  (errors/assert-allowed-keys! body delete-keys)
  (when-not (= character-id (:actor_character_id body))
    (errors/fail! 403 "CHARACTER_NOT_OWNED" :forbidden))
  (require-owned-internal identity account-id character-id)
  (character-port/delete! store character-id (now)))

(defn delete! [identity account-id character-id body]
  (boolean (:deleted (delete-result! identity account-id character-id body))))

(defn public-projection [internal-character]
  (character/public-character internal-character))
