(ns slopbook.publishing.application.profiles
  (:require [clojure.string :as str]
            [slopbook.identity.application.characters :as characters]
            [slopbook.platform.cursor :as cursor]
            [slopbook.platform.errors :as errors]
            [slopbook.publishing.ports.posts :as post-port])
  (:import [java.time Instant]
           [java.util UUID]))

(def page-size 20)

(defn- decode-cursor [cursor-secret expected-scope encoded]
  (when encoded
    (let [value (cursor/decode cursor-secret encoded)]
      (try
        (when-not (and (= expected-scope (:scope value))
                       (string? (:published_at value))
                       (string? (:id value)))
          (throw (IllegalArgumentException. "invalid profile cursor shape")))
        {:published-at (str (Instant/parse (:published_at value)))
         :id (str (UUID/fromString (:id value)))}
        (catch Exception _
          (errors/fail! 400 "CURSOR_INVALID" :invalid-request))))))

(defn public-by-handle
  ([deps handle cursor]
   (public-by-handle deps nil handle nil cursor))
  ([{:keys [store identity cursor-secret]} account-id handle viewer-character-id encoded-cursor]
   (let [character (characters/public-by-handle identity (some-> handle str/lower-case))
         _viewer (when viewer-character-id
                   (characters/require-owned-internal identity account-id viewer-character-id))
         scope (str "profile:" (:id character))
         boundary (decode-cursor cursor-secret scope encoded-cursor)
         rows (post-port/profile-posts store {:character-id (:id character)
                                              :viewer-character-id viewer-character-id
                                              :cursor boundary
                                              :limit (inc page-size)})
         page (vec (take page-size rows))
         has-more? (> (count rows) page-size)
         last-row (peek page)]
     (assoc character
            :counts (post-port/profile-counts store (:id character))
            :posts {:items (mapv #(dissoc % :_published_at) page)
                    :next_cursor (when (and has-more? last-row)
                                   (cursor/encode cursor-secret
                                                  {:scope scope
                                                   :published_at (:_published_at last-row)
                                                   :id (:id last-row)}))}))))
