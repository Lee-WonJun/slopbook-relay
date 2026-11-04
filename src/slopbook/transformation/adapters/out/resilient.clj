(ns slopbook.transformation.adapters.out.resilient
  (:require [slopbook.platform.errors :as errors]
            [slopbook.transformation.domain.circuit-breaker :as breaker]
            [slopbook.transformation.ports.rewriter :as rewriter-port])
  (:import [java.util.concurrent Semaphore TimeUnit]))

(def active-limit 8)
(def waiting-limit 64)
(def overall-timeout-ms 12000)
(def attempt-timeout-ms 8000)
(def credit-cache-ms 60000)

(defn- retryable? [exception]
  (contains? #{429 503 504} (:status (ex-data exception))))

(defn- now-ms [] (System/currentTimeMillis))

(defn adapter
  ([delegate config] (adapter delegate config {}))
  ([delegate _config {:keys [credit-check clock-ms]}]
   (let [clock-ms (or clock-ms now-ms)
         credit-check (or credit-check #(rewriter-port/credit-status delegate))
         admission (Semaphore. (+ active-limit waiting-limit) true)
         active (Semaphore. active-limit true)
         metrics (atom {:active 0 :waiting 0 :rejected 0})
         breakers (atom {})
         credit (atom {:state :unknown :checked-at 0})
         refresh-credit!
         (fn []
           (let [state (try (credit-check) (catch Exception _ :unknown))
                 snapshot {:state (if (contains? #{:available :exhausted} state) state :unknown)
                           :checked-at (clock-ms)}]
             (reset! credit snapshot)
             snapshot))
         current-credit
         (fn []
           (when (>= (- (clock-ms) (:checked-at @credit)) credit-cache-ms)
             (locking credit
               (when (>= (- (clock-ms) (:checked-at @credit)) credit-cache-ms)
                 (refresh-credit!))))
           (:state @credit))
         breaker-state #(get @breakers % (breaker/initial-state))
         reserve-model!
         (fn [model]
           (loop []
             (let [before @breakers
                   state (get before model (breaker/initial-state))
                   decision (breaker/attempt-decision state (clock-ms))]
               (case decision
                 :allow true
                 :deny false
                 :half-open (if (compare-and-set! breakers before (assoc before model (assoc state :mode :half-open)))
                              true
                              (recur))))))
         record-success!
         (fn [model latency]
           (swap! breakers update model #(breaker/record-success (or % (breaker/initial-state)) latency)))
         record-failure!
         (fn [model latency]
           (swap! breakers update model #(breaker/record-failure (or % (breaker/initial-state))
                                                                  latency (clock-ms))))
         sorted-models
         (fn [envelope]
           (->> (rewriter-port/eligible-models-for delegate envelope)
                (sort-by (fn [model]
                           (let [state (breaker-state model)]
                             [(:ewma_error state) (:ewma_latency_ms state)])))
                vec))
         model-available-now?
         (fn [model]
           (not= :deny (breaker/attempt-decision (breaker-state model) (clock-ms))))
         any-model-available?
         (fn []
           (boolean (some model-available-now? (rewriter-port/eligible-models delegate))))
         acquire-active!
         (fn [deadline]
           (if (.tryAcquire active)
             true
             (do
               (swap! metrics update :waiting inc)
               (try
                 (.tryAcquire active (max 1 (- deadline (clock-ms))) TimeUnit/MILLISECONDS)
                 (finally
                   (swap! metrics update :waiting dec))))))
         run!
         (fn [envelope]
           (when (= :exhausted (current-credit))
             (errors/fail! 402 "AI_CREDITS_EXHAUSTED" :ai-unavailable))
           (when (= :unknown (current-credit))
             (errors/fail! 503 "AI_UNAVAILABLE" :ai-unavailable))
           (when-not (.tryAcquire admission)
             (swap! metrics update :rejected inc)
             (errors/fail! 429 "AI_BUSY" :ai-unavailable))
           (let [deadline (+ (clock-ms) overall-timeout-ms)]
             (try
               (when-not (acquire-active! deadline)
                 (errors/fail! 504 "AI_TIMEOUT" :ai-unavailable))
               (swap! metrics update :active inc)
               (try
                 (let [models (->> (sorted-models envelope) (filter reserve-model!) (take 2) vec)]
                   (when (empty? models)
                     (errors/fail! 503 "AI_UNAVAILABLE" :ai-unavailable))
                   (loop [[model & remaining] models]
                     (let [started (clock-ms)
                           remaining-ms (- deadline started)]
                       (when-not (pos? remaining-ms)
                         (errors/fail! 504 "AI_TIMEOUT" :ai-unavailable))
                       (let [outcome (try
                                       {:result (rewriter-port/rewrite-with-model!
                                                 delegate model envelope
                                                 (min attempt-timeout-ms remaining-ms))}
                                       (catch clojure.lang.ExceptionInfo exception
                                         {:exception exception}))]
                         (if-let [exception (:exception outcome)]
                           (do
                             (when (retryable? exception)
                               (record-failure! model (- (clock-ms) started)))
                             (if (and (retryable? exception) (seq remaining))
                               (recur remaining)
                               (throw exception)))
                           (do
                             (record-success! model (- (clock-ms) started))
                             (:result outcome)))))))
                 (finally
                   (swap! metrics update :active dec)
                   (.release active)))
               (finally
                 (.release admission)))))
         adapter-map
         {:rewrite! run!
          :available? #(boolean (and (rewriter-port/available? delegate)
                                     (= :available (current-credit))
                                     (any-model-available?)))
          :capability-snapshot #(assoc (rewriter-port/capability-snapshot delegate)
                                       :credit_state (name (:state @credit)))
          :refresh-capabilities! #(do (rewriter-port/refresh-capabilities! delegate)
                                      (refresh-credit!))
          :eligible-models #(rewriter-port/eligible-models delegate)
          :eligible-models-for #(rewriter-port/eligible-models-for delegate %)
          :runtime-metrics #(assoc @metrics
                                   :breakers (into {}
                                                   (map (fn [[model state]]
                                                          [model (select-keys state
                                                                              [:mode :consecutive_failures
                                                                               :samples :ewma_error
                                                                               :ewma_latency_ms])]))
                                                   @breakers))}]
     (refresh-credit!)
     (rewriter-port/assert-adapter! adapter-map))))

(defn runtime-metrics [adapter]
  (when-let [metrics (:runtime-metrics adapter)] (metrics)))
