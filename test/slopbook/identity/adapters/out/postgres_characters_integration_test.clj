(ns slopbook.identity.adapters.out.postgres-characters-integration-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [slopbook.identity.adapters.out.postgres :as postgres-characters]
            [slopbook.identity.adapters.out.postgres-accounts :as postgres-accounts]
            [slopbook.identity.application.characters :as characters]
            [slopbook.identity.domain.character :as character]
            [slopbook.platform.migrations :as migrations]
            [slopbook.platform.schema-audit :as schema-audit])
  (:import [java.util UUID]))

(def database-url
  (or (System/getenv "DATABASE_URL")
      "jdbc:postgresql://127.0.0.1:15432/slopbook?user=slopbook&password=local-slopbook-only"))

(def datasource (schema-audit/datasource database-url))
(def account-store (postgres-accounts/adapter datasource))
(def character-store (postgres-characters/adapter datasource))

(use-fixtures :once
  (fn [test-fn]
    (migrations/migrate! {:database-url database-url})
    (test-fn)))

(def draft
  {:handle "race_handle"
   :display_name "Rotor"
   :nationality "KR"
   :output_language "en"
   :persona_public {:schema_version 1 :catalog_version 1
                    :what_are_you [{:id "helicopter"}]
                    :archetypes [{:id "philosopher"}]
                    :traits [{:id "restless"} {:id "reflective"} {:id "blunt"}]}
   :persona_behavior {:schema_version 1
                      :voice_tones [{:id "clipped"}]
                      :sentence_shape "short"
                      :formality "neutral"
                      :custom_rule "OWNER_ONLY_BEHAVIOR_CANARY"}})

(defn test-identity []
  {:store character-store
   :new-id #(str (UUID/randomUUID))
   :now #(str (java.time.Instant/now))})

(defn result [f]
  (try {:value (f)}
       (catch clojure.lang.ExceptionInfo exception
         {:code (:code (ex-data exception))})))

(deftest handle-race-crud-authorization-and-projection
  (jdbc/execute! datasource ["DELETE FROM characters WHERE handle IN ('race_handle', 'snapshot_handle')"])
  (let [account-a (postgres-accounts/adapter datasource)
        account-a ((:find-or-create! account-a) "character-test-subject-a")
        account-b ((:find-or-create! account-store) "character-test-subject-b")
        outcomes (->> [(future (result #(characters/create! (test-identity) (:id account-a) draft)))
                       (future (result #(characters/create! (test-identity) (:id account-b) draft)))]
                      (mapv deref))
        success (first (keep :value outcomes))
        character-id (get-in success [:public_character :id])
        owner-id (:account_id ((:find-internal character-store) character-id))
        other-id (if (= owner-id (:id account-a)) (:id account-b) (:id account-a))]
    (is (= 1 (count (keep :value outcomes))))
    (is (= ["HANDLE_TAKEN"] (vec (keep :code outcomes))))
    (is (= "CHARACTER_NOT_OWNED"
           (:code (result #(characters/require-owned-internal
                            (test-identity) other-id character-id)))))
    (let [public (characters/public-by-handle (test-identity) "race_handle")
          public-text (pr-str public)
          owned-text (pr-str success)]
      (is (not (.contains public-text "persona_behavior")))
      (is (not (.contains public-text "OWNER_ONLY_BEHAVIOR_CANARY")))
      (is (not-any? #(contains? public %) [:account_id :auth_subject :email :name]))
      (is (.contains owned-text "OWNER_ONLY_BEHAVIOR_CANARY")))
    (let [snapshot (character/public-character
                    (characters/require-owned-internal (test-identity) owner-id character-id))
          changed-public (assoc (:persona_public draft) :motto "Now changed")
          updated (characters/update! (test-identity) owner-id character-id
                                      {:actor_character_id character-id
                                       :persona_public changed-public
                                       :persona_behavior (:persona_behavior draft)})]
      (is (= 2 (get-in updated [:public_character :persona_version])))
      (is (nil? (get-in snapshot [:persona_public :motto])))
      (is (= "Now changed" (get-in updated [:public_character :persona_public :motto])))
      (is (not= (get-in snapshot [:avatar :seed])
                (get-in updated [:public_character :avatar :seed]))))
    (is (true? (characters/delete! (test-identity) owner-id character-id
                                   {:actor_character_id character-id})))
    (is (= "CHARACTER_NOT_FOUND"
           (:code (result #(characters/public-by-handle (test-identity) "race_handle")))))
    (is (= "HANDLE_TAKEN"
           (:code (result #(characters/create! (test-identity) owner-id draft)))))))

(deftest concurrent-character-creation-enforces-the-account-limit
  (let [run-id (subs (str (UUID/randomUUID)) 0 8)
        owner ((:find-or-create! account-store) (str "character-limit-subject-" run-id))
        make-draft (fn [index]
                     (assoc draft
                            :handle (str "limit_" run-id "_" index)
                            :display_name (str "Limit " index)))
        outcomes (->> (range 6)
                      (mapv (fn [index]
                              (future
                                (result #(characters/create! (test-identity) (:id owner)
                                                             (make-draft index))))))
                      (mapv deref))
        summary (characters/owned-summary (test-identity) (:id owner))]
    (is (= 5 (count (keep :value outcomes))))
    (is (= ["CHARACTER_LIMIT_REACHED"] (vec (keep :code outcomes))))
    (is (= 5 (:character_limit summary)))
    (is (zero? (:remaining_character_slots summary)))
    (is (= 5 (count (:owned_characters summary))))))
