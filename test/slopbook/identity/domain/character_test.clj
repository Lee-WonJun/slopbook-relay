(ns slopbook.identity.domain.character-test
  (:require [clojure.test :refer [deftest is]]
            [slopbook.identity.domain.character :as character]
            [slopbook.identity.domain.persona-test :as persona-fixture]))

(def internal-character
  {:id "character-1"
   :handle "rotor"
   :display_name "Rotor"
   :output_language "en"
   :persona_public persona-fixture/rotor-public
   :persona_behavior persona-fixture/complete-behavior
   :persona_version 1
   :human_owned true
   :status "active"
   :created_at "2026-07-16T00:00:00Z"})

(deftest non-ready-upload-falls-back-to-current-sigil
  (let [fallback (:avatar (character/public-character internal-character))]
    (doseq [status ["pending" "failed" "deleted"]]
      (is (= fallback
             (:avatar (character/public-character
                       (assoc internal-character :avatar_upload {:status status}))))))
    (let [ready (:avatar (character/public-character
                          (assoc internal-character :avatar_upload
                                 {:status "ready" :url "/media/ready.webp"
                                  :sources {:profile "/media/profile.webp"}})))]
      (is (= "upload" (:kind ready)))
      (is (= fallback (:fallback ready))))))
