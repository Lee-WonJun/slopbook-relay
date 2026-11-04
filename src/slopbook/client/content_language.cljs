(ns slopbook.client.content-language)

(defn for-value [value]
  (or (get-in value [:character :output_language])
      (get-in value [:anonymous_persona :output_language])))
