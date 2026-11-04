(ns slopbook.platform.outbox
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as result-set])
  (:import [java.sql Timestamp]))

(def query-options {:builder-fn result-set/as-unqualified-lower-maps})
(def client-event-types
  #{"notification.created" "notification.read" "dm.message.created" "dm.message.read"
    "system.status"})

(defn- instant-string [value]
  (if (instance? Timestamp value) (str (.toInstant ^Timestamp value)) (str value)))

(defn- event-value [row]
  {:event_id (str (:id row))
   :type (:event_type row)
   :character_id (some-> (:target_character_id row) str)
   :resource_id (str (:aggregate_id row))
   :created_at (instant-string (:created_at row))})

(defn postgres-adapter [datasource]
  {:claim!
   (fn []
     (mapv event-value
           (jdbc/execute!
            datasource
            ["WITH picked AS (
                SELECT id FROM outbox_events
                 WHERE published_at IS NULL AND available_at <= now()
                   AND (claimed_at IS NULL OR claimed_at < now() - interval '30 seconds')
                 ORDER BY available_at, id
                 FOR UPDATE SKIP LOCKED LIMIT 50)
              UPDATE outbox_events event
                 SET claimed_at = now(), attempt_count = attempt_count + 1
                FROM picked WHERE event.id = picked.id
              RETURNING event.id, event.event_type, event.aggregate_id,
                        event.target_character_id, event.created_at"]
            query-options)))
   :publish!
   (fn [event-id]
     (boolean
      (jdbc/execute-one!
       datasource
       ["UPDATE outbox_events SET published_at = now(), last_error_code = NULL
          WHERE id = ?::uuid AND published_at IS NULL RETURNING id" event-id]
       query-options)))})

(defn memory-adapter [state]
  {:claim!
   (fn []
     (locking state
       (let [rows (->> (:outbox @state)
                       vals
                       (filter #(nil? (:published_at %)))
                       (sort-by (juxt :created_at :id))
                       (take 50)
                       vec)]
         (doseq [row rows]
           (swap! state update-in [:outbox (:id row) :attempt_count] (fnil inc 0)))
         (mapv event-value rows))))
   :publish!
   (fn [event-id]
     (swap! state assoc-in [:outbox event-id :published_at] (str (java.time.Instant/now)))
     true)})

(defn start! [adapter deliver!]
  (let [running? (atom true)
        worker
        (future
          (while @running?
            (try
              (let [events ((:claim! adapter))]
                (doseq [event events]
                  (when (and (contains? client-event-types (:type event))
                             (:character_id event))
                    (deliver! event))
                  ((:publish! adapter) (:event_id event)))
                (Thread/sleep (if (seq events) 20 250)))
              (catch InterruptedException _
                (reset! running? false))
              (catch Exception exception
                (binding [*out* *err*]
                  (println "Outbox dispatcher error class:" (.getName (class exception))))
                (Thread/sleep 1000)))))]
    {:running? running? :worker worker}))

(defn stop! [{:keys [running? worker]}]
  (when running? (reset! running? false))
  (when worker (future-cancel worker)))
