(ns slopbook.client.message-navigation-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [slopbook.client.routes :as routes]))

;; Regression: ISSUE-003 — successful message entry retained the Search URL.
;; Found by /qa on 2026-07-17
;; Report: .gstack/qa-reports/qa-report-127-0-0-1-2026-07-17.md

(deftest successful-message-entry-replaces-search-with-the-messages-route
  (let [db {:route :search
            :messages {:status :creating}
            :active-character-id "4ad27960-1938-4f45-8a65-340f31c82292"}
        effect (routes/navigation-effect db :messages)]
    (is (= :messages (get-in effect [:db :route])))
    (is (= "/messages" (:history/push effect)))
    (is (= (:messages db) (get-in effect [:db :messages])))
    (is (= (:active-character-id db) (get-in effect [:db :active-character-id])))))

(deftest navigation-effect-only-pushes-declared-application-routes
  (let [effect (routes/navigation-effect {:route :search} :synthetic-unknown)]
    (is (= :synthetic-unknown (get-in effect [:db :route])))
    (is (not (contains? effect :history/push)))))
