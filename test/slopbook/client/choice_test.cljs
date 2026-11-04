(ns slopbook.client.choice-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [slopbook.client.choice :as choice]))

(deftest selected-choices-can-always-be-cleared
  (testing "the last required choice can be cleared before choosing its replacement"
    (is (= []
           (choice/toggle-ref [{:id "man"}] "man" 1 3))))
  (testing "a group can temporarily fall below a larger minimum"
    (is (= [{:id "curious"} {:id "warm"}]
           (choice/toggle-ref [{:id "curious"} {:id "warm"} {:id "blunt"}]
                              "blunt" 3 5)))))

(deftest unselected-choices-still-respect-the-maximum
  (let [full [{:id "woman"} {:id "human"} {:id "helicopter"}]]
    (is (= full (choice/toggle-ref full "alien" 1 3)))))
