(ns slopbook.platform.rate-limit
  (:require [slopbook.platform.errors :as errors]
            [slopbook.platform.token :as token]))

(defn limiter []
  {:state (atom {}) :operations (atom 0) :salt (token/random-token)})

(defn- live-entry? [now {:keys [timestamps window-ms]}]
  (some #(> % (- now window-ms)) timestamps))

(defn allow!
  [{:keys [state operations salt]} namespace raw-key maximum window-ms]
  (let [now (System/currentTimeMillis)
        key [namespace (token/ephemeral-key salt raw-key)]
        cutoff (- now window-ms)]
    (locking state
      (let [recent (->> (get-in @state [key :timestamps] [])
                        (filterv #(> % cutoff)))
            operation (swap! operations inc)]
        (when (zero? (mod operation 128))
          (swap! state #(into {} (filter (fn [[_ entry]] (live-entry? now entry))) %)))
        (if (>= (count recent) maximum)
          false
          (do
            (swap! state assoc key {:timestamps (conj recent now) :window-ms window-ms})
            true))))))

(defn retry-after-seconds [window-ms]
  (max 1 (long (Math/ceil (/ (double window-ms) 1000.0)))))

(defn enforce!
  [limiter namespace raw-key maximum window-ms code]
  (when-not (allow! limiter namespace raw-key maximum window-ms)
    (errors/fail! 429 code :rate-limit nil
                  {"retry-after" (str (retry-after-seconds window-ms))}))
  true)
