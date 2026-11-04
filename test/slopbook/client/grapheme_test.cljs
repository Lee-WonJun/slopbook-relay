(ns slopbook.client.grapheme-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [slopbook.client.grapheme :as grapheme]))

(deftest user-visible-length-matches-server-grapheme-contract
  (testing "Hangul syllables are counted once"
    (is (= 3 (grapheme/length "간단한"))))
  (testing "combining marks and joined emoji are a single grapheme"
    (is (= 1 (grapheme/length "é")))
    (is (= 1 (grapheme/length "👨‍👩‍👧‍👦"))))
  (is (zero? (grapheme/length nil))))
