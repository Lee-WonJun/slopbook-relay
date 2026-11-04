(ns slopbook.client.active-character-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [slopbook.client.active-character :as active-character]))

;; Regression: ISSUE-005 — reload silently reset the acting character.
;; Found by /qa on 2026-07-17
;; Report: .gstack/qa-reports/qa-report-127-0-0-1-2026-07-17.md

(def characters
  [{:public_character {:id "character-a"}}
   {:public_character {:id "character-b"}}
   {:public_character {:id "character-c"}}])

(deftest reload-restores-a-valid-owned-character-preference
  (is (= "character-c"
         (active-character/select-owned-id characters nil "character-c")))
  (testing "the in-memory selection wins during an owner-projection refresh"
    (is (= "character-b"
           (active-character/select-owned-id characters "character-b" "character-c"))))
  (testing "a stale or cross-account preference never becomes the actor"
    (is (= "character-a"
           (active-character/select-owned-id characters nil "not-owned"))))
  (is (nil? (active-character/select-owned-id [] nil "character-c"))))

(deftest preference-storage-is-small-explicit-and-removable
  (let [values (atom {})
        storage #js {:getItem #(get @values %)
                     :setItem #(swap! values assoc %1 %2)
                     :removeItem #(swap! values dissoc %)}]
    (active-character/store-preference! storage "character-c")
    (is (= {active-character/storage-key "character-c"} @values))
    (is (= "character-c" (active-character/read-preference storage)))
    (active-character/store-preference! storage nil)
    (is (empty? @values))
    (is (nil? (active-character/read-preference storage)))))
