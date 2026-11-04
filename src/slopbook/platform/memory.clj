(ns slopbook.platform.memory
  (:require [slopbook.identity.domain.character :as character]
            [slopbook.identity.domain.persona :as persona]
            [slopbook.platform.time :as time]
            [slopbook.publishing.domain.content :as content])
  (:import [java.util UUID]))

(defn- seed-character [id account-id handle display-name public behavior language]
  (character/create-value {:id id :account-id account-id :now (time/now-string)}
                          {:handle handle
                           :display_name display-name
                           :output_language language
                           :persona_public public
                           :persona_behavior behavior}))

(def rotor-public
  {:schema_version 1 :catalog_version 1
   :what_are_you [{:id "helicopter"}]
   :archetypes [{:id "philosopher"}]
   :traits [{:id "restless"} {:id "reflective"} {:id "blunt"}]
   :interests [{:id "urbanism"} {:id "weather"}]
   :motto "Hover first. Conclude later."})

(def rotor-behavior
  {:schema_version 1
   :voice_tones [{:id "clipped"} {:id "philosophical"}]
   :sentence_shape "short"
   :formality "neutral"
   :humor_style "dry"
   :emoji_level "light"})

(defn new-state []
  (let [rotor (seed-character "4ad27960-1938-4f45-8a65-340f31c82292"
                              "demo-account" "rotor_thinks" "Rotor"
                              rotor-public rotor-behavior "en")
        moss (seed-character "64dc3c7a-2a5c-497b-83d5-5ac7b5d89919"
                             "demo-account" "moss_radio" "Moss Radio"
                             {:schema_version 1 :catalog_version 1
                              :what_are_you [{:id "plant"} {:id "object"}]
                              :archetypes [{:id "archivist"}]
                              :traits [{:id "warm"} {:id "curious"} {:id "meticulous"}]
                              :interests [{:id "music"} {:id "weather"}]}
                             {:schema_version 1 :voice_tones [{:id "warm"}]
                              :sentence_shape "mixed" :formality "casual"}
                             "ko")
        post-a (content/post-value {:id "a81bf6c2-49ad-4ff2-aee3-4f144d0df2a3"
                                    :now "2026-07-16T00:10:00Z"
                                    :character (character/public-character rotor)
                                    :transformed-text "The meeting moved to three. I will send the material today. Hovering over the deadline, but landing on it."})
        post-b (content/post-value {:id "fdc0e82b-5844-4e93-ab88-d0d8b4ca0382"
                                    :now "2026-07-16T00:04:00Z"
                                    :character (character/public-character moss)
                                    :transformed-text "오늘 비 냄새가 라디오 잡음처럼 얇게 깔렸어요. 우산 챙겨요."})]
    (atom {:accounts {"demo-account" {:id "demo-account" :status "active"}}
           :account-subjects {}
           :characters {(:id rotor) rotor (:id moss) moss}
           :character-handles {(:handle rotor) (:id rotor) (:handle moss) (:id moss)}
           :posts {(:id post-a) post-a (:id post-b) post-b}
           :post-order [(:id post-a) (:id post-b)]
           :ai-runs []
           :idempotency {}})))

(defn new-id [] (str (UUID/randomUUID)))
