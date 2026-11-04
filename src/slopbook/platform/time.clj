(ns slopbook.platform.time
  (:import [java.time Instant]))

(defn now [] (Instant/now))
(defn now-string [] (str (now)))

