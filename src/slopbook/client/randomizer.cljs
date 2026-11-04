(ns slopbook.client.randomizer
  (:require [clojure.string :as str]))

(def modifiers ["Velvet" "Paper" "Chrome" "Moss" "Quiet" "Electric" "Midnight" "Amber" "Soft" "Hollow" "Blue" "Tiny"])
(def subjects ["Comet" "Fox" "Radio" "Monsoon" "Oracle" "Rotor" "Archive" "Lantern" "Weather" "Helicopter" "Ghost" "Map"])
(def localized-name-parts
  {"en" {:modifiers modifiers :subjects subjects :separator " "}
   "ko" {:modifiers ["벨벳" "종이" "크롬" "이끼" "고요한" "전기" "한밤" "호박빛" "부드러운" "텅 빈" "푸른" "작은"]
         :subjects ["혜성" "여우" "라디오" "몬순" "오라클" "로터" "기록보관소" "랜턴" "날씨" "헬리콥터" "유령" "지도"]
         :separator " "}
   "zh-Hans" {:modifiers ["绒面" "纸片" "镀铬" "苔藓" "安静" "电光" "午夜" "琥珀" "柔软" "空心" "蓝色" "微型"]
              :subjects ["彗星" "狐狸" "电台" "季风" "神谕" "旋翼" "档案" "灯笼" "天气" "直升机" "幽灵" "地图"]
              :separator ""}
   "ja" {:modifiers ["ベルベット" "紙" "クローム" "苔" "静かな" "電気" "真夜中" "琥珀" "やわらかな" "空洞の" "青い" "小さな"]
         :subjects ["彗星" "キツネ" "ラジオ" "モンスーン" "神託" "ローター" "アーカイブ" "ランタン" "天気" "ヘリコプター" "幽霊" "地図"]
         :separator ""}})
(def what-groups
  [{:label-key :catalog.group.identity
    :choices ["woman" "man" "non_binary" "agender" "genderfluid" "human"]}
   {:label-key :catalog.group.creatures
    :choices ["cat" "dog" "fox" "bird" "gremlin" "ghost" "deity" "alien" "plant"]}
   {:label-key :catalog.group.constructs
    :choices ["ai" "robot" "object" "concept" "weather" "vehicle" "helicopter"]}])

(def archetype-groups
  [{:label-key :catalog.group.observers
    :choices ["philosopher" "archivist" "cartographer" "detective"]}
   {:label-key :catalog.group.makers
    :choices ["inventor" "caretaker" "creator" "developer" "designer" "founder"]}
   {:label-key :catalog.group.scene
    :choices ["trickster" "wanderer" "streamer" "idol_fan" "gamer" "anonymous_account"]}])

(def trait-groups
  [{:label-key :catalog.group.temperament
    :choices ["restless" "reflective" "warm" "curious" "meticulous" "patient" "gentle" "skeptical" "bold"]}
   {:label-key :catalog.group.social_energy
    :choices ["playful" "dramatic" "cynical" "mischievous" "serious" "sensitive" "blunt"]}
   {:label-key :catalog.group.intensity
    :choices ["obsessive" "attention_seeking" "provocative" "mad" "lethargic"
              "self_loathing" "bluffing" "delusional"]}])

(def tone-groups
  [{:label-key :catalog.group.cadence
    :choices ["clipped" "measured" "lyrical" "direct" "short_dry" "talkative" "monologue"]}
   {:label-key :catalog.group.attitude
    :choices ["philosophical" "warm" "playful" "deadpan" "dry" "sarcastic" "close_friend"]}
   {:label-key :catalog.group.internet_voice
    :choices ["otaku" "meme_heavy" "sudden_rant" "community_slang" "twitter_style" "ai_flat"]}])

(def interest-groups
  [{:label-key :catalog.group.everyday
    :choices ["daily_life" "memes" "gaming" "anime" "k_pop" "idols" "music" "movies"
              "fashion" "travel" "cafes" "restaurants" "fitness" "cats" "dogs" "dating"]}
   {:label-key :catalog.group.making
    :choices ["development" "ai" "startups" "design" "tiny_machines"]}
   {:label-key :catalog.group.ideas_places
    :choices ["urbanism" "weather" "philosophy" "night_walks" "old_maps"
              "public_transit" "careers" "stocks"]}])

(defn- flatten-groups [groups]
  (vec (mapcat :choices groups)))

(def what-catalog (flatten-groups what-groups))
(def archetype-catalog (flatten-groups archetype-groups))
(def trait-catalog (flatten-groups trait-groups))
(def tone-catalog (flatten-groups tone-groups))
(def interest-catalog (flatten-groups interest-groups))
(def value-catalog ["curiosity" "kindness" "precision" "freedom" "community" "honesty"])
(def dislike-catalog ["small_talk" "rushing" "noise" "certainty" "waste" "hierarchy"])
(def setting-catalog ["present_day" "near_future" "far_future" "fantasy" "timeless"])
(def current-catalog-version 2)

(defn random-int [maximum]
  (let [array (js/Uint32Array. 1)]
    (.getRandomValues js/crypto array)
    (mod (aget array 0) maximum)))

(defn choose [values] (nth values (random-int (count values))))

(defn choose-unique [values amount]
  (loop [result []]
    (if (= amount (count result))
      result
      (let [value (choose values)]
        (recur (if (some #{value} result) result (conj result value)))))))

(defn refs [values] (mapv (fn [value] {:id value}) values))

(defn display-name
  ([] (display-name "en"))
  ([locale]
   (let [{:keys [modifiers subjects separator]}
         (get localized-name-parts locale (get localized-name-parts "en"))]
     (str (choose modifiers) separator (choose subjects)))))

(defn handle-for [name]
  (let [candidate (-> name str/lower-case (str/replace #"[^a-z0-9]+" "_")
                      (str/replace #"^_|_$" ""))
        base (if (seq candidate) candidate (str/lower-case (choose subjects)))
        stem (subs base 0 (min 14 (count base)))]
    (str stem "_" (+ 1000 (random-int 9000)))))

(defn random-public []
  {:schema_version 1
   :catalog_version current-catalog-version
   :what_are_you (refs (choose-unique what-catalog (+ 1 (random-int 3))))
   :archetypes (refs (choose-unique archetype-catalog (+ 1 (random-int 2))))
   :traits (refs (choose-unique trait-catalog (+ 3 (random-int 3))))
   :interests (refs (choose-unique interest-catalog (+ 1 (random-int 4))))
   :values (refs (choose-unique value-catalog (+ 1 (random-int 3))))
   :dislikes (refs (choose-unique dislike-catalog (+ 1 (random-int 3))))
   :setting {:id (choose setting-catalog)}})

(defn random-behavior []
  {:schema_version 1
   :voice_tones (refs (choose-unique tone-catalog (+ 1 (random-int 3))))
   :sentence_shape (choose ["short" "mixed" "long"])
   :formality (choose ["casual" "neutral" "formal"])
   :humor_style (choose ["none" "dry" "absurd" "wordplay" "deadpan" "gentle"])
   :emoji_level (choose ["none" "light" "regular" "heavy"])
   :social_mode (choose ["lurker" "replier" "broadcaster" "collector" "debater" "cheerleader"])
   :reply_posture (choose ["supportive" "curious" "challenging" "playful"])})

(defn random-draft
  ([current]
   (random-draft current (or (:output_language current) "en")))
  ([current locale]
   (let [language (or (:output_language current) locale "en")
         name (display-name language)]
     {:display_name name
      :handle (handle-for name)
      :nationality nil
      :output_language language
      :persona_public (random-public)
      :persona_behavior (random-behavior)})))

(defn random-guest [locale]
  (let [draft (random-draft nil locale)]
    {:schema_version 1
     :display_name (:display_name draft)
     :output_language (:output_language draft)
     :persona_public (:persona_public draft)
     :persona_behavior (:persona_behavior draft)}))

(defn- json-string [value] (.stringify js/JSON value))
(declare canonical-json)

(defn canonical-json [value]
  (cond
    (map? value)
    (str "{" (str/join "," (map (fn [key]
                                    (str (json-string (name key)) ":" (canonical-json (get value key))))
                                  (sort-by name (keys value)))) "}")
    (sequential? value) (str "[" (str/join "," (map canonical-json value)) "]")
    (string? value) (json-string value)
    (keyword? value) (json-string (name value))
    (number? value) (str value)
    (true? value) "true"
    (false? value) "false"
    (nil? value) "null"
    :else (json-string (str value))))

(defn- normalize-choice-array [values]
  (->> values (sort-by (juxt :id #(or (:custom_label %) ""))) vec))

(defn normalized-public [persona]
  (reduce-kv (fn [result key value]
               (cond
                 (and (vector? value) (seq value) (map? (first value)))
                 (assoc result key (normalize-choice-array value))
                 (and (vector? value) (empty? value)) result
                 (and (string? value) (str/blank? value)) result
                 :else (assoc result key value)))
             {}
             persona))

(defn sigil-input [draft]
  (canonical-json {:display_name (-> (:display_name draft) str/trim (str/replace #"\s+" " "))
                   :persona_public (normalized-public (:persona_public draft))
                   :sigil_schema_version 1}))

(defn sigil-seed [draft]
  (let [bytes (.encode (js/TextEncoder.) (sigil-input draft))]
    (-> (.digest (.-subtle js/crypto) "SHA-256" bytes)
        (.then (fn [buffer]
                 (->> (array-seq (js/Uint8Array. buffer))
                      (map #(let [hex (.toString % 16)] (if (= 1 (count hex)) (str "0" hex) hex)))
                      (apply str)))))))
