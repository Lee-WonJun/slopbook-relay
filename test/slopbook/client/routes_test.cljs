(ns slopbook.client.routes-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [slopbook.client.routes :as routes]))

;; Regression: ISSUE-001 — application routes were lost on reload and deep links.
;; Found by /qa on 2026-07-17
;; Report: .gstack/qa-reports/qa-report-127-0-0-1-2026-07-17.md

(deftest fixed-application-routes-round-trip
  (testing "every declared fixed path restores the same screen"
    (doseq [[route path] routes/route-paths]
      (is (= {:route route} (routes/target-for-path path)))))
  (testing "primary destinations have durable URLs"
    (is (= #{"/" "/compose" "/characters" "/search" "/notifications" "/messages"}
           (set (map routes/route-paths
                     [:feed :compose :persona :search :notifications :messages])))))
  (testing "a trailing slash does not lose the current screen"
    (is (= {:route :search} (routes/target-for-path "/search/")))))

(deftest public-profile-and-thread-deep-links-round-trip
  (let [post-id "a81bf6c2-49ad-4ff2-aee3-4f144d0df2a3"]
    (is (= "/characters/rotor_thinks" (routes/profile-path "rotor_thinks")))
    (is (= {:route :profile :handle "rotor_thinks"}
           (routes/target-for-path (routes/profile-path "rotor_thinks"))))
    (is (= (str "/posts/" post-id) (routes/post-path post-id)))
    (is (= {:route :thread :post-id post-id}
           (routes/target-for-path (routes/post-path post-id))))))

(deftest unknown-or-invalid-public-paths-fall-back-to-the-feed
  (is (= {:route :feed} (routes/target-for-path "/not-a-route")))
  (is (= {:route :feed} (routes/target-for-path "/characters/Guest Name")))
  (is (= {:route :feed} (routes/target-for-path "/posts/not-a-post-id"))))
