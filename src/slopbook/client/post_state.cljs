(ns slopbook.client.post-state)

(defn- adjust-post [post post-id delta]
  (if (and post (= post-id (:id post)))
    (update post :comment_count
            #(max 0 (+ (or % 0) delta)))
    post))

(defn- adjust-posts [posts post-id delta]
  (when posts
    (mapv #(adjust-post % post-id delta) posts)))

(defn adjust-comment-count [db post-id delta]
  (-> db
      (update-in [:thread :post] adjust-post post-id delta)
      (update-in [:feed :items] adjust-posts post-id delta)
      (update-in [:profile :value :posts :items] adjust-posts post-id delta)))
