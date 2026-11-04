(ns slopbook.transformation.domain.reaction)

(def reaction-types ["like" "love" "insightful" "celebrate" "support" "funny"])
(def reaction-set (set reaction-types))
(def allowed-result-fields
  #{:reaction :model_id :provider :latency_ms :prompt_tokens :completion_tokens})

(def public-signal-fields
  [:what_are_you :archetypes :traits :interests :values :dislikes])

(def behavior-signal-fields
  [:humor_style :social_mode :reply_posture])

(defn- choice-signal [value]
  (if (map? value)
    (select-keys value [:id :custom_label])
    value))

(defn- compact-field [value]
  (if (sequential? value)
    (mapv choice-signal value)
    (choice-signal value)))

(defn- compact-persona [character]
  (let [public (:persona_public character)
        behavior (:persona_behavior character)]
    {:output_language (:output_language character)
     :public (into {}
                   (keep (fn [field]
                           (when-let [value (get public field)]
                             [field (compact-field value)])))
                   public-signal-fields)
     :behavior (into {}
                     (keep (fn [field]
                             (when-let [value (get behavior field)]
                               [field (compact-field value)])))
                     behavior-signal-fields)}))

(defn decision-envelope [character target]
  {:task_kind "choose.reaction"
   :persona (compact-persona character)
   :target {:type (:type target)
            :text (:text target)}})

(defn valid-result? [value]
  (and (map? value)
       (empty? (remove allowed-result-fields (keys value)))
       (contains? reaction-set (:reaction value))))
