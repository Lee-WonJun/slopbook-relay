(ns slopbook.shared.domain.text
  (:require [clojure.string :as str])
  (:import [java.text Normalizer Normalizer$Form]))

(defn normalize-text [value]
  (when (some? value)
    (-> (Normalizer/normalize (str value) Normalizer$Form/NFC)
        str/trim
        (str/replace #"\s+" " "))))

(defn present-text [value]
  (let [value (normalize-text value)]
    (when-not (str/blank? value) value)))

(defn grapheme-count [value]
  (count (re-seq #"\X" (or value ""))))

(defn within-graphemes? [value minimum maximum]
  (let [count (grapheme-count (normalize-text value))]
    (<= minimum count maximum)))

(defn- escape-json-character [character]
  (case character
    \" "\\\""
    \\ "\\\\"
    \backspace "\\b"
    \formfeed "\\f"
    \newline "\\n"
    \return "\\r"
    \tab "\\t"
    (let [code (int character)]
      (if (< code 0x20)
        (format "\\u%04x" code)
        (str character)))))

(defn json-string [value]
  (str "\"" (apply str (map escape-json-character (str value))) "\""))

(declare canonical-json)

(defn- canonical-map [value]
  (let [entries (sort-by (comp name key) value)]
    (str "{"
         (str/join "," (map (fn [[key item]]
                              (str (json-string (name key)) ":" (canonical-json item)))
                            entries))
         "}")))

(defn canonical-json [value]
  (cond
    (map? value) (canonical-map value)
    (sequential? value) (str "[" (str/join "," (map canonical-json value)) "]")
    (string? value) (json-string value)
    (keyword? value) (json-string (name value))
    (number? value) (str value)
    (true? value) "true"
    (false? value) "false"
    (nil? value) "null"
    :else (throw (ex-info "Unsupported canonical JSON value" {:type (type value)}))))

