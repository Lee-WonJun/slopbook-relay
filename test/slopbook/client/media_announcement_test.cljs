(ns slopbook.client.media-announcement-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [slopbook.client.media :as media]))

;; Regression: ISSUE-006 — a valid upload retained an obsolete selection error.
;; Found by /qa on 2026-07-17
;; Report: .gstack/qa-reports/qa-report-127-0-0-1-2026-07-17.md

(deftest valid-selection-clears-a-prior-warning
  (testing "a new valid selection does not retain an earlier invalid-file warning"
    (is (= ""
           (media/selection-announcement
            [{:valid? true}] :valid? "invalid selection")))))

(deftest mixed-selection-keeps-the-current-warning
  (testing "a warning remains when any newly selected candidate is invalid"
    (is (= "invalid selection"
           (media/selection-announcement
            [{:valid? true} {:valid? false}] :valid? "invalid selection")))))
