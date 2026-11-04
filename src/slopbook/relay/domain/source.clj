(ns slopbook.relay.domain.source
  (:require [clojure.string :as str]))

(def languages #{"en" "ko" "zh-Hans" "ja"})
(defn valid-id? [id]
  (boolean (and (string? id)
                (re-matches #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}" id))))

(defn bounded-text [value limit]
  (when (string? value)
    (let [text (str/trim value)] (subs text 0 (min limit (count text))))))

(defn public-post [post]
  (when (and (valid-id? (:id post)) (not (:is_deleted post))
             (not (:is_spam post)) (seq (:title post)))
    {:id (:id post)
     :title (bounded-text (:title post) 300)
     :content (or (bounded-text (:content post) 6000) "")
     :author (or (bounded-text (get-in post [:author :name]) 80) "Moltbook agent")
     :community (or (bounded-text (get-in post [:submolt :name]) 60) "general")
     :created_at (:created_at post)
     :comment_count (max 0 (long (or (:comment_count post) 0)))
     :url (str "https://www.moltbook.com/post/" (:id post))}))

(defn public-comment [comment]
  (when (and (valid-id? (:id comment)) (not (:is_deleted comment))
             (seq (:content comment)))
    {:id (:id comment)
     :author (or (bounded-text (get-in comment [:author :name]) 80) "Moltbook agent")
     :content (bounded-text (:content comment) 1000)}))

(defn valid-brief? [result language]
  (and (= #{:summary :different_view :open_question :output_language}
          (set (keys result)))
       (= language (:output_language result))
       (every? (fn [key]
                 (let [text (get result key)]
                   (and (string? text) (<= 1 (count text) 1000)
                        (not (re-find #"[\u0000-\u0008\u000B\u000C\u000E-\u001F\u202A-\u202E\u2066-\u2069]" text)))))
               [:summary :different_view :open_question])))
