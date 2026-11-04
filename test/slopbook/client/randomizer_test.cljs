(ns slopbook.client.randomizer-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [slopbook.client.randomizer :as randomizer]))

(def rotor-draft
  {:display_name "Rotor"
   :persona_public
   {:schema_version 1
    :catalog_version 1
    :what_are_you [{:id "helicopter"}]
    :archetypes [{:id "philosopher"}]
    :traits [{:id "restless"} {:id "reflective"} {:id "blunt"}]
    :interests [{:id "urbanism"} {:id "weather"}]
    :motto "Hover first. Conclude later."}})

(deftest canonical-input-matches-server-contract
  (is (= "{\"display_name\":\"Rotor\",\"persona_public\":{\"archetypes\":[{\"id\":\"philosopher\"}],\"catalog_version\":1,\"interests\":[{\"id\":\"urbanism\"},{\"id\":\"weather\"}],\"motto\":\"Hover first. Conclude later.\",\"schema_version\":1,\"traits\":[{\"id\":\"blunt\"},{\"id\":\"reflective\"},{\"id\":\"restless\"}],\"what_are_you\":[{\"id\":\"helicopter\"}]},\"sigil_schema_version\":1}"
         (randomizer/sigil-input rotor-draft))))

(deftest random-draft-always-populates-required-fields
  (let [draft (randomizer/random-draft nil)]
    (is (seq (:display_name draft)))
    (is (= randomizer/current-catalog-version
           (get-in draft [:persona_public :catalog_version])))
    (is (<= 1 (count (get-in draft [:persona_public :what_are_you])) 3))
    (is (<= 3 (count (get-in draft [:persona_public :traits])) 5))
    (is (<= 1 (count (get-in draft [:persona_behavior :voice_tones])) 3))))

(deftest catalog-v2-keeps-the-useful-legacy-slopbook-range
  (is (every? (set randomizer/what-catalog) ["helicopter" "alien" "ai" "robot"]))
  (is (every? (set randomizer/trait-catalog)
              ["cynical" "obsessive" "attention_seeking" "delusional"]))
  (is (every? (set randomizer/tone-catalog)
              ["short_dry" "sudden_rant" "community_slang" "monologue"]))
  (is (every? (set randomizer/interest-catalog)
              ["gaming" "anime" "k_pop" "development" "design" "cats"])))

(deftest randomize-refreshes-the-whole-editable-profile
  (let [current (assoc (randomizer/random-draft nil)
                       :display_name "Pinned Rotor" :handle "pinned_rotor")
        rolled (randomizer/random-draft current)]
    (is (not= "pinned_rotor" (:handle rolled)))
    (is (= (:output_language current) (:output_language rolled)))
    (is (seq (get-in rolled [:persona_public :values])))
    (is (seq (get-in rolled [:persona_public :dislikes])))))

(deftest guest-randomizer-uses-the-same-contract-without-an-account-identity
  (let [guest (randomizer/random-guest "ko")]
    (is (= 1 (:schema_version guest)))
    (is (= "ko" (:output_language guest)))
    (is (seq (:display_name guest)))
    (is (nil? (:handle guest)))
    (is (map? (:persona_public guest)))
    (is (map? (:persona_behavior guest)))))
