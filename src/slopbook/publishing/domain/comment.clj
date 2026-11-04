(ns slopbook.publishing.domain.comment
  (:require [slopbook.shared.domain.text :as text]))

(def reaction-types [:like :love :insightful :celebrate :support :funny])

(defn empty-reaction-counts []
  (zipmap reaction-types (repeat 0)))

(defn valid-input? [value]
  (text/within-graphemes? value 1 180))

(defn comment-value
  [{:keys [id post-id parent-id thread-root-id now character anonymous-session-id
           anonymous-persona transformed-text media]}]
  (let [anonymous? (some? anonymous-session-id)]
    {:id id
     :type "comment"
     :post_id post-id
     :parent_comment_id parent-id
     :thread_root_id (or thread-root-id id)
     :character_id (:id character)
     :character character
     :anonymous_session_id anonymous-session-id
     :anonymous_persona_snapshot anonymous-persona
     :anonymous_persona anonymous-persona
     :anonymous anonymous?
     :text transformed-text
     :media (vec media)
     :media_ids (mapv :id media)
     :status "published"
     :created_at now
     :reply_to nil
     :reaction_counts (empty-reaction-counts)
     :viewer_reaction nil}))

(def public-fields
  [:id :type :post_id :parent_comment_id :thread_root_id :character :anonymous :anonymous_persona
   :text :status :created_at :reply_to :reaction_counts :viewer_reaction :media])

(defn public-comment [comment]
  (when (and comment
             (or (= "published" (:status comment))
                 (and (= "deleted" (:status comment)) (:tombstone comment))))
    (select-keys comment public-fields)))

(defn deletion-transition [comment reply-count now]
  (when-not (= "published" (:status comment))
    (throw (ex-info "Comment is not deletable" {:code :not-found})))
  (if (pos? reply-count)
    (assoc comment :status "deleted" :text nil :deleted_at now :tombstone true)
    (assoc comment :status "deleted" :text nil :deleted_at now :tombstone false)))
