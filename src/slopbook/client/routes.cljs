(ns slopbook.client.routes)

(def route-paths
  {:feed "/"
   :compose "/compose"
   :persona "/characters"
   :search "/search"
   :notifications "/notifications"
   :messages "/messages"
   :privacy "/privacy"
   :terms "/terms"
   :ai-policy "/ai-policy"
   :community-guidelines "/community-guidelines"
   :security "/security"})

(def path-routes (into {} (map (fn [[route path]] [path route]) route-paths)))

(defn- normalized-path [path]
  (if (and (string? path) (> (count path) 1))
    (.replace path #"/+$" "")
    (or path "/")))

(defn target-for-path [path]
  (let [path (normalized-path path)
        profile-match (re-matches #"^/characters/([a-z][a-z0-9_]{2,19})$" path)
        post-match (re-matches #"^/posts/([0-9a-fA-F-]{36})$" path)]
    (cond
      (get path-routes path) {:route (get path-routes path)}
      profile-match {:route :profile :handle (second profile-match)}
      post-match {:route :thread :post-id (second post-match)}
      :else {:route :feed})))

(defn profile-path [handle]
  (str "/characters/" (js/encodeURIComponent handle)))

(defn post-path [post-id]
  (str "/posts/" (js/encodeURIComponent post-id)))

(defn navigation-effect [db route]
  (cond-> {:db (assoc db :route route)}
    (get route-paths route) (assoc :history/push (get route-paths route))))
