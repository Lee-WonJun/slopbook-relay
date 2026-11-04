(ns slopbook.client.content-language-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [slopbook.client.content-language :as content-language]))

(deftest content-language-follows-the-public-speaker
  (testing "signed-in persona content"
    (is (= "ko" (content-language/for-value
                 {:character {:output_language "ko"}}))))
  (testing "guest persona content"
    (is (= "ja" (content-language/for-value
                 {:anonymous_persona {:output_language "ja"}}))))
  (testing "unknown legacy content"
    (is (nil? (content-language/for-value {})))))
