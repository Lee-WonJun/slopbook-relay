(ns slopbook.transformation.adapters.out.resilient-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopbook.platform.routes :as platform-routes]
            [slopbook.transformation.adapters.out.resilient :as resilient]
            [slopbook.transformation.ports.rewriter :as rewriter-port])
  (:import [java.util.concurrent Callable CountDownLatch Executors TimeUnit]))

(def envelope {:input_text "QUEUE_RAW_CANARY" :identity "IDENTITY_CANARY"})

(defn error [status code]
  (ex-info "safe synthetic failure" {:slopbook/error true :status status :code code}))

(defn delegate [models call]
  {:rewrite! #(call (first models) % 8000)
   :rewrite-with-model! call
   :eligible-models (constantly models)
   :available? (constantly true)
   :capability-snapshot (constantly {:probe_status "complete"})
   :refresh-capabilities! (constantly true)
   :credit-status (constantly :available)})

(defn wait-for-metrics [adapter predicate]
  (loop [remaining 300]
    (let [value (resilient/runtime-metrics adapter)]
      (cond
        (predicate value) value
        (zero? remaining) value
        :else (do (Thread/sleep 10) (recur (dec remaining)))))))

(deftest active-and-waiting-capacity-is-bounded
  (let [release (CountDownLatch. 1)
        entered (CountDownLatch. resilient/active-limit)
        ai (resilient/adapter
            (delegate ["model-a"]
                      (fn [_ _ _]
                        (.countDown entered)
                        (.await release 5 TimeUnit/SECONDS)
                        {:status "ok"}))
            {}
            {:credit-check (constantly :available)})
        executor (Executors/newFixedThreadPool (+ resilient/active-limit resilient/waiting-limit))
        futures (mapv (fn [_]
                        (.submit executor
                                 ^Callable (reify Callable
                                             (call [_] (rewriter-port/rewrite! ai envelope)))))
                      (range (+ resilient/active-limit resilient/waiting-limit)))]
    (try
      (is (.await entered 2 TimeUnit/SECONDS))
      (let [metrics (wait-for-metrics ai #(= resilient/waiting-limit (:waiting %)))]
        (is (= resilient/active-limit (:active metrics)))
        (is (= resilient/waiting-limit (:waiting metrics))))
      (is (= "AI_BUSY"
             (try
               (rewriter-port/rewrite! ai envelope)
               nil
               (catch clojure.lang.ExceptionInfo exception (:code (ex-data exception))))))
      (is (= 1 (:rejected (resilient/runtime-metrics ai))))
      (finally
        (.countDown release)
        (doseq [future futures] (.get future 5 TimeUnit/SECONDS))
        (.shutdownNow executor)))))

(deftest only-retryable-failures-use-a-second-model
  (let [calls (atom [])
        ai (resilient/adapter
            (delegate ["model-a" "model-b"]
                      (fn [model _ _]
                        (swap! calls conj model)
                        (if (= "model-a" model)
                          (throw (error 503 "AI_PROVIDER_ERROR"))
                          {:status "ok" :model model})))
            {}
            {:credit-check (constantly :available)})]
    (is (= "model-b" (:model (rewriter-port/rewrite! ai envelope))))
    (is (= ["model-a" "model-b"] @calls)))
  (let [calls (atom [])
        ai (resilient/adapter
            (delegate ["model-a" "model-b"]
                      (fn [model _ _]
                        (swap! calls conj model)
                        (throw (error 502 "AI_OUTPUT_INVALID"))))
            {}
            {:credit-check (constantly :available)})]
    (is (= "AI_OUTPUT_INVALID"
           (try (rewriter-port/rewrite! ai envelope) nil
                (catch clojure.lang.ExceptionInfo exception (:code (ex-data exception))))))
    (is (= ["model-a"] @calls))))

(deftest status-reflects-open-breaker-when-no-model-can-run
  (let [clock (atom 1000)
        ai (resilient/adapter
            (delegate ["model-a"]
                      (fn [_ _ _]
                        (throw (error 503 "AI_PROVIDER_ERROR"))))
            {}
            {:credit-check (constantly :available)
             :clock-ms #(deref clock)})]
    (dotimes [_ 5]
      (is (= "AI_PROVIDER_ERROR"
             (try (rewriter-port/rewrite! ai envelope) nil
                  (catch clojure.lang.ExceptionInfo exception
                    (:code (ex-data exception)))))))
    (is (false? (rewriter-port/available? ai)))
    (is (= "read_only"
           (:mode (platform-routes/status-payload {}
                                                  #(rewriter-port/available? ai)))))
    (swap! clock + 30000)
    (is (true? (rewriter-port/available? ai)))))

(deftest exhausted-credit-closes-only-ai-and-metrics-are-content-free
  (let [ai (resilient/adapter
            (delegate ["model-a"] (fn [_ _ _] {:status "ok"}))
            {}
            {:credit-check (constantly :exhausted)})
        status (platform-routes/status-payload {} #(rewriter-port/available? ai))]
    (is (= "AI_CREDITS_EXHAUSTED"
           (try (rewriter-port/rewrite! ai envelope) nil
                (catch clojure.lang.ExceptionInfo exception (:code (ex-data exception))))))
    (is (= "read_only" (:mode status)))
    (is (= "available" (get-in status [:features :public_read])))
    (let [rendered (pr-str (resilient/runtime-metrics ai))]
      (is (not (.contains rendered "QUEUE_RAW_CANARY")))
      (is (not (.contains rendered "IDENTITY_CANARY"))))))
