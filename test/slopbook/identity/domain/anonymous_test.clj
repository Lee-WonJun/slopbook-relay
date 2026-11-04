(ns slopbook.identity.domain.anonymous-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopbook.identity.domain.anonymous :as anonymous]))

(def guest
  {:schema_version 1
   :display_name "Midnight Rotor"
   :output_language "ko"
   :persona_public {:schema_version 1
                    :catalog_version 1
                    :what_are_you [{:id "helicopter"}]
                    :archetypes [{:id "philosopher"}]
                    :traits [{:id "restless"} {:id "reflective"} {:id "blunt"}]}
   :persona_behavior {:schema_version 1
                      :voice_tones [{:id "clipped"}]
                      :sentence_shape "short"
                      :formality "neutral"
                      :custom_rule "GUEST_BEHAVIOR_CANARY"}})

(deftest guest-persona-separates-request-behavior-from-public-snapshot
  (is (anonymous/valid-persona? guest))
  (is (not (anonymous/valid-persona? (assoc guest :handle "guest_handle"))))
  (is (not (anonymous/valid-persona? (dissoc guest :persona_behavior))))
  (let [snapshot (anonymous/snapshot guest)
        actor (anonymous/transformation-character guest)]
    (testing "the rewrite actor receives private behavior"
      (is (= "GUEST_BEHAVIOR_CANARY" (get-in actor [:persona_behavior :custom_rule]))))
    (testing "the public snapshot has identity and Sigil but no private/profile state"
      (is (= "Midnight Rotor" (:display_name snapshot)))
      (is (= "persona_sigil" (get-in snapshot [:avatar :kind])))
      (is (not (.contains (pr-str snapshot) "GUEST_BEHAVIOR_CANARY")))
      (is (not-any? #(contains? snapshot %)
                    [:persona_behavior :handle :profile_url :human_owned :account_id])))))
