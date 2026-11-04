(ns slopbook.client.reaction-ui-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [slopbook.client.reaction-ui :as reaction-ui]))

(deftest reaction-summary-only-exposes-received-types
  (testing "zero-count reaction choices do not masquerade as received reactions"
    (is (= [{:reaction "love" :count 2}
            {:reaction "support" :count 1}]
           (reaction-ui/received-reactions
            {:like 0 :love 2 :insightful 0 :celebrate 0 :support 1 :funny 0})))
    (is (= 3 (reaction-ui/total {:love 2 :support 1})))))

(deftest only-a-new-actor-target-pair-needs-an-ai-decision
  (is (reaction-ui/first-decision? nil))
  (is (not (reaction-ui/first-decision? {:reaction "like" :active false})))
  (is (not (reaction-ui/first-decision? {:reaction "love" :active true}))))
