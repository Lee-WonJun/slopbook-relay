(ns slopbook.platform.json
  (:require [jsonista.core :as json])
  (:import [com.fasterxml.jackson.databind DeserializationFeature]))

(def mapper
  (doto (json/object-mapper {:decode-key-fn true})
    (.enable DeserializationFeature/FAIL_ON_TRAILING_TOKENS)))

(defn read-value [value]
  (json/read-value value mapper))

(defn write-value [value]
  (json/write-value-as-string value mapper))
