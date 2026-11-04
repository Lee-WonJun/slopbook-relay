(ns slopbook.identity.domain.persona-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopbook.identity.domain.persona :as persona]
            [slopbook.identity.domain.sigil :as sigil]))

(def rotor-public
  {:schema_version 1
   :catalog_version 1
   :what_are_you [{:id "helicopter"}]
   :archetypes [{:id "philosopher"}]
   :traits [{:id "restless"} {:id "reflective"} {:id "blunt"}]
   :interests [{:id "urbanism"} {:id "weather"}]
   :motto "Hover first. Conclude later."})

(deftest canonical-sigil-vector
  (testing "the approved Rotor vector is byte stable"
    (is (= "{\"display_name\":\"Rotor\",\"persona_public\":{\"archetypes\":[{\"id\":\"philosopher\"}],\"catalog_version\":1,\"interests\":[{\"id\":\"urbanism\"},{\"id\":\"weather\"}],\"motto\":\"Hover first. Conclude later.\",\"schema_version\":1,\"traits\":[{\"id\":\"blunt\"},{\"id\":\"reflective\"},{\"id\":\"restless\"}],\"what_are_you\":[{\"id\":\"helicopter\"}]},\"sigil_schema_version\":1}"
           (persona/sigil-canonical-input " Rotor  " rotor-public)))
    (is (= "02c4b36ed11a0a71df78af8d3c07c37acf968909f481c3683426a1171ef9a31d"
           (persona/sigil-seed "Rotor" rotor-public)))))

(deftest sigil-svg-is-deterministic-and-dom-compact
  (let [seed (persona/sigil-seed "Rotor" rotor-public)
        first-render (sigil/svg seed)
        second-render (sigil/svg seed)]
    (is (= first-render second-render))
    (is (.startsWith first-render "<svg"))
    (is (= 5 (count (re-seq #"<path " first-render))))
    (is (= 1 (count (re-seq #"<circle " first-render))))
    (is (< (count first-render) 70000))))

(deftest persona-validation
  (is (empty? (persona/validate-public rotor-public)))
  (is (empty? (persona/validate-behavior
               {:schema_version 1
                :voice_tones [{:id "clipped"} {:id "philosophical"}]
                :sentence_shape "short"
                :formality "neutral"})))
  (is (seq (persona/validate-public (assoc rotor-public :traits [{:id "blunt"}])))))

(deftest catalog-v2-validates-the-expanded-social-persona-options
  (let [public {:schema_version 1
                :catalog_version persona/current-catalog-version
                :what_are_you [{:id "ai"} {:id "helicopter"}]
                :archetypes [{:id "developer"}]
                :traits [{:id "cynical"} {:id "obsessive"} {:id "delusional"}]
                :interests [{:id "gaming"} {:id "k_pop"} {:id "design"}]}
        behavior {:schema_version 1
                  :voice_tones [{:id "short_dry"} {:id "community_slang"}]
                  :sentence_shape "short"
                  :formality "casual"}]
    (is (empty? (persona/validate-public public)))
    (is (empty? (persona/validate-behavior behavior)))
    (is (seq (persona/validate-public (assoc public :catalog_version 999))))))

(def complete-behavior
  {:schema_version 1
   :voice_tones [{:id "clipped"}]
   :sentence_shape "short"
   :formality "neutral"
   :humor_style "dry"
   :emoji_level "heavy"
   :social_mode "replier"
   :reply_posture "curious"
   :catchphrases [(apply str (repeat 60 "a"))]
   :verbal_tics [(apply str (repeat 30 "b"))]
   :avoid_style (apply str (repeat 60 "c"))
   :custom_rule (apply str (repeat 30 "d"))})

(deftest optional-fields-and-grapheme-boundaries
  (let [maximum-public (assoc rotor-public
                              :bio (apply str (repeat 160 "🛰️"))
                              :pronouns (apply str (repeat 24 "가"))
                              :home_base (apply str (repeat 40 "x"))
                              :motto (apply str (repeat 60 "y"))
                              :values [{:id "curiosity"}]
                              :dislikes [{:id "small_talk"}]
                              :setting {:id "present_day"})]
    (is (empty? (persona/validate-public maximum-public)))
    (is (empty? (persona/validate-behavior complete-behavior)))
    (is (seq (persona/validate-public (assoc maximum-public :motto (apply str (repeat 61 "y"))))))
    (is (seq (persona/validate-behavior
              (assoc complete-behavior :custom_rule (apply str (repeat 31 "d"))))))
    (is (seq (persona/validate-public (assoc rotor-public :unknown "rejected"))))
    (is (seq (persona/validate-public
              (assoc rotor-public :what_are_you [{:id "not_in_catalog"}]))))))

(deftest sigil-input-is-public-only
  (let [base (persona/sigil-seed "Rotor" rotor-public)]
    (is (not= base (persona/sigil-seed "Rotor Two" rotor-public)))
    (is (not= base (persona/sigil-seed "Rotor" (assoc rotor-public :motto "Changed"))))
    (is (= base (persona/sigil-seed "Rotor" rotor-public)))))
