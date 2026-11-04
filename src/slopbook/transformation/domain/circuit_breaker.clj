(ns slopbook.transformation.domain.circuit-breaker)

(def alpha 0.2)
(def open-ms 30000)

(defn initial-state []
  {:mode :closed
   :consecutive_failures 0
   :samples 0
   :ewma_error 0.0
   :ewma_latency_ms 0.0
   :opened_at nil})

(defn attempt-decision [state now-ms]
  (case (:mode state)
    :closed :allow
    :half-open :deny
    :open (if (>= (- now-ms (:opened_at state)) open-ms) :half-open :deny)
    :allow))

(defn- ewma [previous sample samples]
  (if (zero? samples)
    (double sample)
    (+ (* alpha sample) (* (- 1.0 alpha) previous))))

(defn record-success [state latency-ms]
  (let [samples (:samples state)]
    (assoc state
           :mode :closed
           :consecutive_failures 0
           :samples (inc samples)
           :ewma_error (ewma (:ewma_error state) 0.0 samples)
           :ewma_latency_ms (ewma (:ewma_latency_ms state) latency-ms samples)
           :opened_at nil)))

(defn record-failure [state latency-ms now-ms]
  (let [samples (:samples state)
        failures (inc (:consecutive_failures state))
        error-rate (ewma (:ewma_error state) 1.0 samples)
        should-open? (or (= :half-open (:mode state))
                         (>= failures 5)
                         (and (>= (inc samples) 10) (>= error-rate 0.5)))]
    (assoc state
           :mode (if should-open? :open :closed)
           :consecutive_failures failures
           :samples (inc samples)
           :ewma_error error-rate
           :ewma_latency_ms (ewma (:ewma_latency_ms state) latency-ms samples)
           :opened_at (when should-open? now-ms))))
