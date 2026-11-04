(ns slopbook.client.feed-visibility)

(defn composer-visible? [auth-status guest-configured?]
  (or (contains? #{:authenticated :development} auth-status)
      (not guest-configured?)))
