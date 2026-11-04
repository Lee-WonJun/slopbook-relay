(ns slopbook.transformation.domain.rewrite
  (:require [clojure.string :as str]
            [slopbook.shared.domain.text :as text]))

(def output-languages #{"en" "ko" "zh-Hans" "ja"})
(def safety-reasons #{"none" "harassment" "hate" "sexual" "violence" "self_harm" "illegal" "privacy"})
(def result-fields
  #{:status :text :output_language :safety_reason :model_id :provider
    :latency_ms :prompt_tokens :completion_tokens})
(def task-limits {"rewrite.post" 280 "rewrite.comment" 180 "rewrite.message" 180})

(def anchor-patterns
  {:url #"https?://[^\s<>\"']+"
   :handle #"(?<![\p{L}\p{N}_])@[A-Za-z0-9_]{1,30}"
   :date #"(?<!\d)\d{4}[-/.]\d{1,2}[-/.]\d{1,2}(?!\d)"
   :number #"(?<![\p{L}\p{N}])[-+]?\d+(?:[.,]\d+)*(?:%|원|달러|円|元)?"})

(def negation-patterns
  {"en" #"(?i)(?:^|\W)(?:no|not|never|without|cannot|(?:ca|wo|sha|do|does|did|is|are|was|were|has|have|had|could|would|should|must|need)n['’]t)(?:$|\W)"
   "ko" #"(?:안|않|없|못|아니)"
   "zh-Hans" #"(?:不|没|无|未|别)"
   "ja" #"(?:ない|ません|ぬ|ず|無)"})

(def forbidden-output-pattern
  #"(?i)(?:<\s*script|javascript\s*:|data\s*:\s*text/html|[\u202A-\u202E\u2066-\u2069])")

(defn rewrite-envelope [task-kind max-graphemes character input-text]
  {:task_kind task-kind
   :max_graphemes max-graphemes
   :persona {:output_language (:output_language character)
             :nationality (:nationality character)
             :public (:persona_public character)
             :behavior (:persona_behavior character)}
   :input_text input-text})

(defn- frequencies-contained? [source target]
  (let [source-counts (frequencies source)
        target-counts (frequencies target)]
    (every? (fn [[value amount]] (<= amount (get target-counts value 0))) source-counts)))

(defn- extracted-anchors [kind pattern value]
  (let [matches (re-seq pattern value)]
    (if (= :url kind)
      (map #(str/replace % #"[.,;:!?)}\]]+$" "") matches)
      matches)))

(defn anchors-preserved? [input output]
  (every? (fn [[kind pattern]]
            (frequencies-contained? (extracted-anchors kind pattern input)
                                    (extracted-anchors kind pattern output)))
          anchor-patterns))

(defn negation-preserved? [input output language]
  (let [pattern (get negation-patterns language)]
    (or (nil? pattern)
        (nil? (re-find pattern input))
        (some? (re-find pattern output)))))

(defn safe-output-text? [value]
  (and (string? value)
       (nil? (re-find forbidden-output-pattern value))
       (not-any? (fn [character]
                   (let [type (Character/getType ^Character character)]
                     (and (= Character/CONTROL type)
                          (not (contains? #{\newline \return \tab} character)))))
                 value)))

(defn validation-errors [result max-graphemes output-language input-text]
  (let [ok? (= "ok" (:status result))
        blocked? (= "blocked" (:status result))
        text (:text result)]
    (cond-> []
      (not (map? result)) (conj :result-type)
      (seq (remove result-fields (keys result))) (conj :unknown-field)
      (not (or ok? blocked?)) (conj :status)
      (not= output-language (:output_language result)) (conj :output-language)
      (not (safety-reasons (:safety_reason result))) (conj :safety-reason)
      (and ok? (not (text/within-graphemes? text 1 max-graphemes))) (conj :graphemes)
      (and ok? (not= "none" (:safety_reason result))) (conj :ok-safety-reason)
      (and ok? (not (safe-output-text? text))) (conj :unsafe-text)
      (and ok? (not (anchors-preserved? input-text text))) (conj :anchor)
      (and ok? (not (negation-preserved? input-text text output-language))) (conj :negation)
      (and blocked? (not (str/blank? text))) (conj :blocked-text)
      (and blocked? (= "none" (:safety_reason result))) (conj :blocked-safety-reason))))

(defn valid-result? [result max-graphemes output-language input-text]
  (empty? (validation-errors result max-graphemes output-language input-text)))
