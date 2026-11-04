(ns slopbook.client.feed-visibility-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [slopbook.client.feed-visibility :as feed-visibility]))

(deftest guest-onboarding-disappears-after-the-guest-is-ready
  (testing "anonymous first visit"
    (is (true? (feed-visibility/composer-visible? :anonymous false))))
  (testing "anonymous visitor with a saved guest persona"
    (is (false? (feed-visibility/composer-visible? :anonymous true))))
  (testing "signed-in visitors always keep the post composer path"
    (is (true? (feed-visibility/composer-visible? :authenticated true)))
    (is (true? (feed-visibility/composer-visible? :development true)))))
