(ns slopbook.media.domain.asset
  (:require [slopbook.shared.domain.media :as shared-media]
            [slopbook.shared.domain.text :as text]))

(def purposes #{"post" "comment" "avatar"})
(def output-languages #{"en" "ko" "zh-Hans" "ja"})
(def max-file-bytes (* 10 1024 1024))
(def max-request-bytes (* 41 1024 1024))
(def max-edge 12000)
(def max-pixels 40000000)

(defn valid-request? [{:keys [purpose output-language size]}]
  (and (contains? purposes purpose)
       (contains? output-languages output-language)
       (integer? size) (<= 1 size max-file-bytes)))

(def public-asset shared-media/public-asset)

(defn valid-alt? [value]
  (text/within-graphemes? value 1 160))
