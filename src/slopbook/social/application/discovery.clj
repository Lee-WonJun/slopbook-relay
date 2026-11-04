(ns slopbook.social.application.discovery
  (:require [clojure.string :as str]
            [slopbook.identity.application.characters :as characters]
            [slopbook.platform.cursor :as cursor]
            [slopbook.platform.errors :as errors]
            [slopbook.platform.token :as token]
            [slopbook.shared.domain.text :as text]
            [slopbook.social.ports.discovery :as discovery-port])
  (:import [java.util Base64]))

(def actor-keys #{:actor_character_id})
(def search-types #{"character" "post"})
(def connection-types #{:followers :following})
(def page-size 20)
(def cursor-encoder (.withoutPadding (Base64/getUrlEncoder)))

(defn- cursor-scope [kind value]
  (str kind ":" (.encodeToString cursor-encoder (token/digest value))))

(defn- offset-for [cursor-secret encoded expected-scope]
  (if-not encoded
    0
    (let [value (cursor/decode cursor-secret encoded)
          offset (:offset value)]
      (when-not (and (= expected-scope (:scope value))
                     (integer? offset) (<= 0 offset 10000))
        (errors/fail! 400 "CURSOR_INVALID" :invalid-request))
      offset)))

(defn- page [cursor-secret scope offset rows project]
  (let [more? (> (count rows) page-size)
        items (mapv project (take page-size rows))]
    {:items items
     :next_cursor (when more?
                    (cursor/encode cursor-secret
                                   {:scope scope :offset (+ offset page-size)}))}))

(defn follow!
  [{:keys [discovery identity]} account-id target-id body]
  (errors/assert-allowed-keys! body actor-keys)
  (let [actor (characters/require-owned-internal identity account-id (:actor_character_id body))]
    (when (= (:id actor) target-id)
      (errors/fail! 400 "FOLLOW_SELF_FORBIDDEN" :validation))
    (or (discovery-port/follow! discovery {:actor-character-id (:id actor)
                                           :target-character-id target-id})
        (errors/fail! 404 "CHARACTER_NOT_FOUND" :not-found))))

(defn unfollow!
  [{:keys [discovery identity]} account-id target-id body]
  (errors/assert-allowed-keys! body actor-keys)
  (let [actor (characters/require-owned-internal identity account-id (:actor_character_id body))]
    (discovery-port/unfollow! discovery {:actor-character-id (:id actor)
                                         :target-character-id target-id})
    true))

(defn connections
  [{:keys [discovery cursor-secret]} type character-id viewer-character-id encoded-cursor]
  (when-not (contains? connection-types type)
    (errors/fail! 400 "CONNECTION_TYPE_INVALID" :validation))
  (let [scope (cursor-scope "connections" (str (name type) ":" character-id))
        offset (offset-for cursor-secret encoded-cursor scope)
        rows (:items (discovery-port/list-connections
                      discovery
                      {:type type :character-id character-id
                       :viewer-character-id viewer-character-id
                       :offset offset :limit (inc page-size)}))]
    (page cursor-secret scope offset rows characters/public-projection)))

(defn owned-viewer-id [{:keys [identity]} account-id requested-id]
  (when requested-id
    (:id (characters/require-owned-internal identity account-id requested-id))))

(defn search
  [{:keys [discovery cursor-secret]} query type viewer-character-id encoded-cursor]
  (let [query (text/normalize-text query)]
    (when-not (and (contains? search-types type)
                   (text/within-graphemes? query 2 80))
      (errors/fail! 400 "SEARCH_QUERY_INVALID" :validation))
    (let [scope (cursor-scope "search" (str type ":" query))
          offset (offset-for cursor-secret encoded-cursor scope)
          rows (:items (discovery-port/search discovery {:query query :type type
                                                         :viewer-character-id viewer-character-id
                                                         :offset offset
                                                         :limit (inc page-size)}))]
      (page cursor-secret scope offset rows
            (if (= "character" type) characters/public-projection identity)))))
