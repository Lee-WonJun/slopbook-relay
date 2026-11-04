(ns slopbook.client.media-test
  (:require [cljs.test :refer-macros [deftest is]]
            [slopbook.client.media :as media]))

(deftest selected-files-survive-clearing-the-live-input-list
  (let [first-file #js {:name "first.png"}
        second-file #js {:name "second.webp"}
        live-files #js [first-file second-file]
        selected (media/snapshot-files live-files)]
    (set! (.-length live-files) 0)
    (is (empty? (array-seq live-files)))
    (is (= 2 (count selected)))
    (is (= "first.png" (.-name (first selected))))
    (is (= "second.webp" (.-name (second selected))))))
