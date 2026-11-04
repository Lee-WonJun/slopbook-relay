(ns slopbook.publishing.ports.posts)

(def required-operations
  #{:reserve! :publish! :fail-reservation! :feed :find-post :find-internal :delete!
    :profile-posts :profile-counts})

(defn assert-adapter! [adapter]
  (let [missing (remove #(fn? (get adapter %)) required-operations)]
    (when (seq missing)
      (throw (ex-info "Publishing post adapter is incomplete" {:missing (vec missing)})))
    adapter))

(defn reserve! [adapter reservation] ((:reserve! adapter) reservation))
(defn publish! [adapter publication] ((:publish! adapter) publication))
(defn fail-reservation! [adapter reservation] ((:fail-reservation! adapter) reservation))
(defn feed [adapter query] ((:feed adapter) query))
(defn find-post
  ([adapter id] ((:find-post adapter) {:id id :viewer-character-id nil}))
  ([adapter id viewer-character-id]
   ((:find-post adapter) {:id id :viewer-character-id viewer-character-id})))
(defn find-internal [adapter id] ((:find-internal adapter) id))
(defn delete! [adapter command] ((:delete! adapter) command))
(defn profile-posts [adapter query] ((:profile-posts adapter) query))
(defn profile-counts [adapter character-id] ((:profile-counts adapter) character-id))
