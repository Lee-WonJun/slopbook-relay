(ns slopbook.tickets-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopbook.tickets :as tickets]))

(def base-ticket
  {:id "SLOP-900" :title "fixture" :status "backlog" :priority "P0" :milestone "M1"
   :prd "PRD-09" :requirements ["PROD-018"] :owner "test" :depends_on []
   :planned_start "2026-07-16" :planned_end "2026-07-16" :estimate "1h"
   :acceptance "pending" :evidence []})

(defn error-codes [ticket-set]
  (set (map :code (tickets/validate-ticket-set ticket-set))))

(deftest validator-failure-fixtures
  (testing "duplicate ID"
    (is (contains? (error-codes [base-ticket base-ticket]) :duplicate-id)))
  (testing "broken dependency"
    (is (contains? (error-codes [(assoc base-ticket :depends_on ["SLOP-404"])])
                   :missing-dependency)))
  (testing "dependency cycle"
    (is (contains? (error-codes [(assoc base-ticket :depends_on ["SLOP-901"])
                                 (assoc base-ticket :id "SLOP-901" :depends_on ["SLOP-900"])])
                   :dependency-cycle)))
  (testing "done ticket requires evidence"
    (is (contains? (error-codes [(assoc base-ticket :status "done" :acceptance "passed")])
                   :done-evidence))))
