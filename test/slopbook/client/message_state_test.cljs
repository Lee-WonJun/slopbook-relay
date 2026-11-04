(ns slopbook.client.message-state-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [slopbook.client.message-state :as message-state]))

;; Regression: ISSUE-004 — deleted private text remained in list previews.
;; Found by /qa on 2026-07-17
;; Report: .gstack/qa-reports/qa-report-127-0-0-1-2026-07-17.md

(def conversation-id "f0185575-ca74-4e5b-982b-90139967c346")

(deftest deleting-the-last-visible-message-clears-its-list-preview
  (let [message {:id "message-1" :text "private text"}
        db {:messages {:active {:conversation {:id conversation-id}
                                :messages {:items [message]}}
                       :items [{:id conversation-id :preview "private text"}
                               {:id "other" :preview "keep me"}]}}
        updated (message-state/remove-visible-message db (:id message))]
    (is (empty? (get-in updated [:messages :active :messages :items])))
    (is (nil? (get-in updated [:messages :items 0 :preview])))
    (is (= "keep me" (get-in updated [:messages :items 1 :preview])))))

(deftest deleting-the-newest-message-restores-the-previous-visible-preview
  (let [db {:messages {:active {:conversation {:id conversation-id}
                                :messages {:items [{:id "older" :text "previous"}
                                                   {:id "newest" :text "delete me"}]}}
                       :items [{:id conversation-id :preview "delete me"}]}}
        updated (message-state/remove-visible-message db "newest")]
    (is (= ["older"]
           (mapv :id (get-in updated [:messages :active :messages :items]))))
    (is (= "previous" (get-in updated [:messages :items 0 :preview])))))
