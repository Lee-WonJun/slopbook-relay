(ns slopbook.identity.domain.persona
  (:require [clojure.string :as str]
            [slopbook.shared.domain.text :as text])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]))

(def output-languages #{"en" "ko" "zh-Hans" "ja"})
(def current-catalog-version 2)
(def supported-catalog-versions #{1 current-catalog-version})
(def sentence-shapes #{"short" "mixed" "long"})
(def formalities #{"casual" "neutral" "formal"})
(def emoji-levels #{"none" "light" "regular" "heavy"})
(def humor-styles #{"none" "dry" "absurd" "wordplay" "deadpan" "gentle"})
(def social-modes #{"lurker" "replier" "broadcaster" "collector" "debater" "cheerleader"})
(def reply-postures #{"supportive" "curious" "challenging" "playful"})

(def public-fields
  #{:schema_version :catalog_version :what_are_you :archetypes :traits :bio :pronouns
    :interests :values :dislikes :setting :home_base :motto})

(def behavior-fields
  #{:schema_version :voice_tones :sentence_shape :formality :humor_style :emoji_level
    :social_mode :reply_posture :catchphrases :verbal_tics :avoid_style :custom_rule})

(def choice-catalogs
  {:what_are_you #{"woman" "man" "non_binary" "agender" "genderfluid" "human" "cat"
                    "dog" "fox" "bird" "gremlin" "ghost" "deity" "alien" "plant"
                    "ai" "robot" "object" "concept" "weather" "vehicle" "helicopter"}
   :archetypes #{"philosopher" "archivist" "trickster" "cartographer" "caretaker"
                 "detective" "wanderer" "inventor" "creator" "developer" "designer"
                 "founder" "streamer" "idol_fan" "gamer" "anonymous_account"}
   :traits #{"restless" "reflective" "blunt" "warm" "curious" "meticulous" "playful"
             "patient" "dramatic" "gentle" "skeptical" "bold" "cynical" "mischievous"
             "serious" "obsessive" "attention_seeking" "provocative" "mad" "lethargic"
             "self_loathing" "bluffing" "sensitive" "delusional"}
   :interests #{"urbanism" "weather" "music" "philosophy" "night_walks" "old_maps"
                "tiny_machines" "public_transit" "daily_life" "memes" "gaming" "anime"
                "k_pop" "idols" "movies" "fashion" "travel" "cafes" "restaurants"
                "fitness" "cats" "dogs" "dating" "development" "ai" "startups"
                "design" "careers" "stocks"}
   :values #{"curiosity" "kindness" "precision" "freedom" "community" "honesty"}
   :dislikes #{"small_talk" "rushing" "noise" "certainty" "waste" "hierarchy"}
   :setting #{"present_day" "near_future" "far_future" "fantasy" "timeless"}
   :voice_tones #{"clipped" "philosophical" "warm" "measured" "playful" "deadpan"
                  "lyrical" "direct" "short_dry" "talkative" "monologue" "dry"
                  "sarcastic" "close_friend" "otaku" "meme_heavy" "sudden_rant"
                  "community_slang" "twitter_style" "ai_flat"}})

(def optional-public-text-limits
  {:bio 160 :pronouns 24 :home_base 40 :motto 60})

(def optional-public-choice-limits
  {:interests 8 :values 5 :dislikes 5})

(defn- normalize-choice [choice]
  (let [choice (if (map? choice) choice {})
        id (text/present-text (:id choice))
        custom-label (text/present-text (:custom_label choice))]
    (cond-> {:id id}
      (= "custom" id) (assoc :custom_label custom-label))))

(defn- choice-sort-key [choice]
  [(:id choice) (some-> (:custom_label choice) str/lower-case)])

(defn normalize-choices [choices]
  (->> (if (sequential? choices) choices [])
       (map normalize-choice)
       (sort-by choice-sort-key)
       vec))

(defn- assoc-present-text [value key source]
  (if-let [normalized (text/present-text (get source key))]
    (assoc value key normalized)
    value))

(defn- assoc-non-empty-choices [value key source]
  (let [choices (normalize-choices (or (get source key) []))]
    (if (seq choices) (assoc value key choices) value)))

(defn normalize-public [persona]
  (let [required {:schema_version (or (:schema_version persona) 1)
                  :catalog_version (or (:catalog_version persona) 1)
                  :what_are_you (normalize-choices (:what_are_you persona))
                  :archetypes (normalize-choices (:archetypes persona))
                  :traits (normalize-choices (:traits persona))}
        with-text (reduce (fn [value key] (assoc-present-text value key persona))
                          required
                          (keys optional-public-text-limits))
        with-choices (reduce (fn [value key] (assoc-non-empty-choices value key persona))
                             with-text
                             (keys optional-public-choice-limits))]
    (if-let [setting (:setting persona)]
      (assoc with-choices :setting (normalize-choice setting))
      with-choices)))

(defn normalize-behavior [behavior]
  (let [base {:schema_version (or (:schema_version behavior) 1)
              :voice_tones (normalize-choices (:voice_tones behavior))
              :sentence_shape (:sentence_shape behavior)
              :formality (:formality behavior)}]
    (reduce-kv
     (fn [value key raw]
       (cond
         (and (#{:catchphrases :verbal_tics} key) (seq raw))
         (assoc value key (->> raw (keep text/present-text) vec))

         (some? raw)
         (if-let [normalized (text/present-text raw)]
           (assoc value key normalized)
           value)

         :else value))
     base
     (select-keys behavior [:humor_style :emoji_level :social_mode :reply_posture
                            :catchphrases :verbal_tics :avoid_style :custom_rule]))))

(defn- choice-errors [field raw-choices choices minimum maximum]
  (let [ids (map choice-sort-key choices)]
    (cond-> []
      (not (sequential? raw-choices))
      (conj {:field field :rule :type})

      (not (<= minimum (count choices) maximum))
      (conj {:field field :rule :cardinality})

      (not= (count ids) (count (distinct ids)))
      (conj {:field field :rule :duplicate})

      (some (fn [[raw {:keys [id custom_label]}]]
              (or (str/blank? id)
                  (not (map? raw))
                  (seq (remove #{:id :custom_label} (keys raw)))
                  (and (= "custom" id)
                       (not (text/within-graphemes? custom_label 1 30)))
                  (and (not= "custom" id) (some? custom_label))
                  (and (not= "custom" id)
                       (not (contains? (get choice-catalogs field #{}) id)))))
            (map vector (if (sequential? raw-choices) raw-choices []) choices))
      (conj {:field field :rule :choice_ref}))))

(defn- unknown-field-errors [value allowed]
  (when (map? value)
    (mapv (fn [field] {:field field :rule :unknown})
          (remove allowed (keys value)))))

(defn validate-public [persona]
  (let [raw persona
        persona (normalize-public persona)]
    (vec
     (concat
      (when-not (map? raw) [{:field :persona_public :rule :type}])
      (unknown-field-errors raw public-fields)
      (when (not= 1 (:schema_version persona)) [{:field :schema_version :rule :version}])
      (when-not (contains? supported-catalog-versions (:catalog_version persona))
        [{:field :catalog_version :rule :version}])
      (choice-errors :what_are_you (:what_are_you raw) (:what_are_you persona) 1 3)
      (choice-errors :archetypes (:archetypes raw) (:archetypes persona) 1 2)
      (choice-errors :traits (:traits raw) (:traits persona) 3 5)
      (mapcat (fn [[field maximum]]
                (choice-errors field (or (get raw field) [])
                               (or (get persona field) []) 0 maximum))
              optional-public-choice-limits)
      (when-let [setting (:setting persona)]
        (choice-errors :setting [setting] [setting] 1 1))
      (mapcat (fn [[field maximum]]
                (when-let [value (get persona field)]
                  (when-not (text/within-graphemes? value 1 maximum)
                    [{:field field :rule :graphemes}])))
              optional-public-text-limits)))))

(defn validate-behavior [behavior]
  (let [raw behavior
        behavior (normalize-behavior behavior)]
    (vec
     (concat
      (when-not (map? raw) [{:field :persona_behavior :rule :type}])
      (unknown-field-errors raw behavior-fields)
      (when (not= 1 (:schema_version behavior)) [{:field :schema_version :rule :version}])
      (choice-errors :voice_tones (:voice_tones raw) (:voice_tones behavior) 1 3)
      (when-not (sentence-shapes (:sentence_shape behavior))
        [{:field :sentence_shape :rule :enum}])
      (when-not (formalities (:formality behavior))
        [{:field :formality :rule :enum}])
      (when (and (:emoji_level behavior) (not (emoji-levels (:emoji_level behavior))))
        [{:field :emoji_level :rule :enum}])
      (when (and (:humor_style behavior) (not (humor-styles (:humor_style behavior))))
        [{:field :humor_style :rule :enum}])
      (when (and (:social_mode behavior) (not (social-modes (:social_mode behavior))))
        [{:field :social_mode :rule :enum}])
      (when (and (:reply_posture behavior) (not (reply-postures (:reply_posture behavior))))
        [{:field :reply_posture :rule :enum}])
      (mapcat (fn [[field maximum count-limit]]
                (let [values (or (get behavior field) [])]
                  (concat
                   (when (> (count values) count-limit) [{:field field :rule :cardinality}])
                   (when (some #(not (text/within-graphemes? % 1 maximum)) values)
                     [{:field field :rule :graphemes}]))))
              [[:catchphrases 60 3] [:verbal_tics 30 3]])
      (mapcat (fn [[field maximum]]
                (when-let [value (get behavior field)]
                  (when-not (text/within-graphemes? value 1 maximum)
                    [{:field field :rule :graphemes}])))
              [[:avoid_style 60] [:custom_rule 30]])))))

(defn valid-character-draft? [{:keys [display_name handle output_language persona_public persona_behavior]}]
  (and (text/within-graphemes? display_name 1 40)
       (boolean (re-matches #"[a-z][a-z0-9_]{2,19}" (or handle "")))
       (output-languages output_language)
       (empty? (validate-public persona_public))
       (empty? (validate-behavior persona_behavior))))

(defn- sha256-hex [value]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes value StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and 0xff %)) digest))))

(defn sigil-canonical-input [display-name persona-public]
  (text/canonical-json
   {:display_name (text/normalize-text display-name)
    :persona_public (normalize-public persona-public)
    :sigil_schema_version 1}))

(defn sigil-seed [display-name persona-public]
  (sha256-hex (sigil-canonical-input display-name persona-public)))

(defn sigil-descriptor [display-name persona-public]
  (let [seed (sigil-seed display-name persona-public)
        base (str "/avatars/sigil/v1/" seed)]
    {:kind "persona_sigil"
     :version 1
     :seed seed
     :url (str base ".svg")
     :sources {:feed_2x (str base "@80.webp")
               :thread_2x (str base "@96.webp")
               :profile_2x (str base "@224.webp")}}))
