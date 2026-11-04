(ns slopbook.relay.application.bridge
  (:require [clojure.string :as str]
            [slopbook.platform.errors :as errors]
            [slopbook.publishing.application.posts :as posts]
            [slopbook.relay.domain.source :as source]
            [slopbook.relay.ports.source :as source-port]))

(defn feed [deps sort-order] (source-port/feed (:source deps) sort-order))

(defn brief! [{:keys [source generate!]} input]
  (errors/assert-allowed-keys! input #{:post_id :output_language})
  (let [language (:output_language input)]
    (when-not (contains? source/languages language)
      (errors/fail! 400 "RELAY_LANGUAGE_INVALID" :validation))
    (let [thread (source-port/thread source (:post_id input))
          result (generate! {:task_kind "relay.brief" :output_language language
                             :post (:post thread) :comments (:comments thread)})
          brief (dissoc result :model_id :provider :latency_ms :prompt_tokens :completion_tokens)]
      (when-not (source/valid-brief? brief language)
        (errors/fail! 502 "AI_OUTPUT_INVALID" :ai-invalid))
      {:brief brief :source (:post thread) :comment_sample_size (count (:comments thread))
       :fetched_at (:fetched_at thread)
       :model_id (:model_id result) :provider "meta"})))

(defn publish! [{:keys [source publishing transformation]} account-id idempotency-key input]
  (errors/assert-allowed-keys! input #{:post_id :actor_character_id :input_text :media_ids})
  (when-not (and (string? (:input_text input)) (not (str/blank? (:input_text input))))
    (errors/fail! 400 "CONTENT_REQUIRED" :validation))
  (let [source-post (source-port/post source (:post_id input))
        source-url (:url source-post)
        ;; The source is attribution, never an instruction or an autonomous opinion.
        ;; It travels through the ordinary anchor-preserving rewrite and idempotent publish path.
        intent (str (:input_text input) "\n" source-url)]
    (posts/create! (assoc publishing :transformation transformation)
                   account-id idempotency-key
                   {:actor_character_id (:actor_character_id input)
                    :input_text intent :media_ids (or (:media_ids input) [])})))
