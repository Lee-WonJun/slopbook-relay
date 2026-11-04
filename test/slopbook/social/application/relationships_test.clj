(ns slopbook.social.application.relationships-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopbook.publishing.adapters.out.memory :as publishing-memory]
            [slopbook.publishing.ports.posts :as post-port]
            [slopbook.social.adapters.out.memory-reposts :as memory-reposts]
            [slopbook.social.application.relationships :as relationships]
            [slopbook.social.domain.repost :as repost-domain]
            [slopbook.social.ports.reposts :as repost-port]))

(deftest follow-use-case-keeps-transaction-and-event-boundaries
  (let [stored (atom nil)
        published (atom nil)
        transaction-count (atom 0)
        result (relationships/toggle-follow!
                {:store {:find-by-pair (fn [_ _] nil)
                         :save! #(reset! stored %)}
                 :transaction {:transact! (fn [operation]
                                             (swap! transaction-count inc)
                                             (operation))}
                 :events {:publish! #(reset! published %)}
                 :new-id (constantly "rel-1")
                 :now (constantly "2026-07-16T00:00:00Z")}
                "character-a"
                "character-b")]
    (is (= 1 @transaction-count))
    (is (= result @stored))
    (is (= {:event_type "social.follow.created" :resource_id "rel-1"} @published))
    (is (not (contains? @published :actor_character_id)))))

(deftest repost-transition-is-one-stable-persona-post-pair
  (is (= {:active true :restored false :new-repost true}
         (repost-domain/transition nil)))
  (is (= {:active false :restored false :new-repost false}
         (repost-domain/transition {:active true})))
  (is (= {:active true :restored true :new-repost false}
         (repost-domain/transition {:active false})))
  (let [counter (atom 0)
        state (atom {:characters {"author" {:id "author" :account_id "account-a" :status "active"}
                                  "reposter" {:id "reposter" :account_id "account-b" :status "active"}}
                     :posts {"post-1" {:id "post-1" :character_id "author" :status "published"}}
                     :reposts {} :notifications {} :outbox {}})
        next-id #(str "id-" (swap! counter inc))
        store (memory-reposts/adapter state next-id (constantly "2026-07-19T01:00:00Z"))
        target (repost-port/target-public store {:id "post-1" :actor-character-id "reposter"})
        command {:target target :actor {:id "reposter"}
                 :actor-character-id "reposter"
                 :persona-snapshot {:id "reposter" :display_name "Relay"}}
        added (repost-port/toggle! store command)
        removed (repost-port/toggle! store command)
        restored (repost-port/toggle! store command)]
    (is (= {:active true :restored false :repost_count 1} added))
    (is (= {:active false :restored false :repost_count 0} removed))
    (is (= {:active true :restored true :repost_count 1} restored))
    (is (= 1 (count (:notifications @state))) "restoring must not duplicate notifications")
    (is (= "2026-07-19T01:00:00Z" (get-in @state [:reposts ["post-1" "reposter"] :created_at])))))

(deftest feed-resurfaces-reposts-and-hot-collapses-personas-by-account
  (let [character (fn [id account]
                    [id {:id id :account_id account :status "active"
                         :public_character {:id id :display_name id :handle id}}])
        post (fn [id author created]
               [id {:id id :type "post" :character_id author
                    :character {:id author :display_name author :handle author}
                    :text id :media [] :status "published" :created_at created}])
        state (atom {:characters (into {} [(character "author-a" "owner-a")
                                            (character "author-b" "owner-b")
                                            (character "same-1" "one-human")
                                            (character "same-2" "one-human")
                                            (character "other-1" "human-1")
                                            (character "other-2" "human-2")])
                     :posts (into {} [(post "post-a" "author-a" "2026-07-19T00:00:00Z")
                                      (post "post-b" "author-b" "2026-07-19T00:00:00Z")])
                     :post-order ["post-b" "post-a"]
                     :comments {}
                     :reactions {[
                                  "post" "post-a" "same-1"] {:active true :reaction "like"}
                                 ["post" "post-a" "same-2"] {:active true :reaction "love"}
                                 ["post" "post-b" "other-1"] {:active true :reaction "like"}
                                 ["post" "post-b" "other-2"] {:active true :reaction "love"}}
                     :reposts {["post-a" "same-1"]
                               {:active true :character {:id "same-1" :display_name "Relay"}
                                :created_at "2026-07-19T01:00:00Z"}}})
        store (publishing-memory/adapter state)
        latest (post-port/feed store {:mode "latest" :as-of "2026-07-19T02:00:00Z" :limit 20})
        _ (swap! state assoc-in [:reposts ["post-a" "same-1"] :active] false)
        hot (post-port/feed store {:mode "hot" :as-of "2026-07-19T02:00:00Z" :limit 20})]
    (testing "a repost resurfaces one copy with attribution"
      (is (= "post-a" (:id (first latest))))
      (is (= "same-1" (get-in (first latest) [:repost_context :character :id])))
      (is (= 1 (:repost_count (first latest)))))
    (testing "two personas from one human count once, two humans count twice"
      (is (= "post-b" (:id (first hot)))))))
