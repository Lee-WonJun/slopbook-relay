(ns slopbook.client.post-state-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [slopbook.client.post-state :as post-state]))

;; Regression: ISSUE-002 — deleted comments left visible post counts stale.
;; Found by /qa on 2026-07-17
;; Report: .gstack/qa-reports/qa-report-127-0-0-1-2026-07-17.md

(def target-id "a81bf6c2-49ad-4ff2-aee3-4f144d0df2a3")
(def other-id "5d7b7bc0-ab55-4d39-8426-76fb1cdcab53")

(def surface-db
  {:thread {:post {:id target-id :comment_count 1}}
   :feed {:items [{:id target-id :comment_count 1}
                  {:id other-id :comment_count 7}]}
   :profile {:value {:posts {:items [{:id target-id :comment_count 1}]}}}})

(deftest comment-count-updates-every-visible-copy-of-a-post
  (let [incremented (post-state/adjust-comment-count surface-db target-id 1)
        deleted (post-state/adjust-comment-count incremented target-id -1)]
    (is (= 2 (get-in incremented [:thread :post :comment_count])))
    (is (= 2 (get-in incremented [:feed :items 0 :comment_count])))
    (is (= 2 (get-in incremented [:profile :value :posts :items 0 :comment_count])))
    (is (= 7 (get-in incremented [:feed :items 1 :comment_count])))
    (is (= surface-db deleted))))

(deftest deleted-comment-count-never-goes-negative-or-materializes-missing-surfaces
  (let [db {:thread {:post {:id target-id :comment_count 0}}
            :feed {:items nil}
            :profile {:value {:posts {:items nil}}}}
        updated (post-state/adjust-comment-count db target-id -1)]
    (is (= 0 (get-in updated [:thread :post :comment_count])))
    (is (nil? (get-in updated [:feed :items])))
    (is (nil? (get-in updated [:profile :value :posts :items])))))
