(ns slopbook.identity.domain.anonymous
  (:require [slopbook.identity.domain.persona :as persona]
            [slopbook.shared.domain.text :as text]))

(def guest-keys
  #{:schema_version :display_name :output_language :persona_public :persona_behavior})

(defn valid-persona? [value]
  (and (map? value)
       (empty? (remove guest-keys (keys value)))
       (= 1 (:schema_version value))
       (text/within-graphemes? (:display_name value) 1 40)
       (contains? persona/output-languages (:output_language value))
       (empty? (persona/validate-public (:persona_public value)))
       (empty? (persona/validate-behavior (:persona_behavior value)))))

(defn snapshot [value]
  (let [display-name (text/normalize-text (:display_name value))
        public (persona/normalize-public (:persona_public value))]
    {:schema_version 1
     :display_name display-name
     :output_language (:output_language value)
     :persona_public public
     :avatar (persona/sigil-descriptor display-name public)}))

(defn transformation-character [value]
  {:output_language (:output_language value)
   :nationality nil
   :persona_public (persona/normalize-public (:persona_public value))
   :persona_behavior (persona/normalize-behavior (:persona_behavior value))})
