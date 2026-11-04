(ns slopbook.client.grapheme)

(defonce ^:private segmenter
  (when (and (exists? js/Intl) (some? (.-Segmenter js/Intl)))
    (js/Intl.Segmenter. "und" #js {:granularity "grapheme"})))

(defn length
  "Counts user-perceived characters. Intl.Segmenter handles Hangul composition,
  combining marks, and joined emoji; Array.from is the code-point fallback."
  [value]
  (let [value (or value "")]
    (if segmenter
      (.-length (js/Array.from (.segment segmenter value)))
      (.-length (js/Array.from value)))))
