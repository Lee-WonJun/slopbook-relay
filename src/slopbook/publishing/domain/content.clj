(ns slopbook.publishing.domain.content
  (:require [slopbook.shared.domain.text :as text])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]))

(defn valid-input? [value maximum]
  (text/within-graphemes? value 1 maximum))

(defn post-value [{:keys [id now character transformed-text media]}]
  {:id id
   :type "post"
   :character_id (:id character)
   :character character
   :text transformed-text
   :media (vec media)
   :media_ids (mapv :id media)
   :status "published"
   :created_at now
   :comment_count 0
   :reaction_counts {:like 0 :love 0 :insightful 0 :celebrate 0 :support 0 :funny 0}
   :viewer_reaction nil
   :repost_count 0
   :viewer_repost nil
   :repost_context nil})

(def public-post-fields
  [:id :type :character :text :media :created_at :comment_count :reaction_counts :viewer_reaction
   :repost_count :viewer_repost :repost_context])

(defn public-post [post]
  (when (and post (= "published" (:status post)))
    (select-keys post public-post-fields)))

(defn- sha256 [value]
  (.digest (MessageDigest/getInstance "SHA-256")
           (.getBytes value StandardCharsets/UTF_8)))

(defn idempotency-fingerprint
  ([actor-character-id persona-version media-ids]
   (idempotency-fingerprint "posts.create" actor-character-id persona-version media-ids))
  ([route actor-id persona-version media-ids]
   (sha256 (text/canonical-json
            {:actor_id actor-id
             :media_ids (vec (sort (or media-ids [])))
             :persona_version persona-version
             :route route}))))
