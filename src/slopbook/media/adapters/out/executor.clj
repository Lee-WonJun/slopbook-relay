(ns slopbook.media.adapters.out.executor
  (:import [java.util.concurrent ArrayBlockingQueue RejectedExecutionException ThreadFactory
            ThreadPoolExecutor ThreadPoolExecutor$AbortPolicy TimeUnit]
           [java.util.concurrent.atomic AtomicInteger]))

(defn create []
  (let [sequence (AtomicInteger.)
        factory (reify ThreadFactory
                  (newThread [_ runnable]
                    (doto (Thread. runnable (str "slopbook-media-" (.incrementAndGet sequence)))
                      (.setDaemon true))))]
    (ThreadPoolExecutor. 1 1 0 TimeUnit/MILLISECONDS
                         (ArrayBlockingQueue. 8) factory (ThreadPoolExecutor$AbortPolicy.))))

(defn submit! [^ThreadPoolExecutor executor task]
  (try
    (.execute executor ^Runnable (reify Runnable (run [_] (task))))
    true
    (catch RejectedExecutionException _ false)))

(defn stop! [^ThreadPoolExecutor executor]
  (when executor (.shutdownNow executor)))
