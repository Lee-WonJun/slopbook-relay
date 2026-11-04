(ns slopbook.transformation.domain.circuit-breaker-test
  (:require [clojure.test :refer [deftest is]]
            [slopbook.transformation.domain.circuit-breaker :as breaker]))

(deftest five-retryable-failures-open-then-half-open-and-close
  (let [opened (reduce (fn [state _] (breaker/record-failure state 100 1000))
                       (breaker/initial-state)
                       (range 5))]
    (is (= :open (:mode opened)))
    (is (= :deny (breaker/attempt-decision opened 30999)))
    (is (= :half-open (breaker/attempt-decision opened 31000)))
    (let [half-open (assoc opened :mode :half-open)
          closed (breaker/record-success half-open 80)]
      (is (= :closed (:mode closed)))
      (is (zero? (:consecutive_failures closed))))
    (let [half-open (assoc opened :mode :half-open)
          reopened (breaker/record-failure half-open 80 32000)]
      (is (= :open (:mode reopened)))
      (is (= 32000 (:opened_at reopened))))))
