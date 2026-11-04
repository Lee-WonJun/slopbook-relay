(ns slopbook.tickets
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str])
  (:import [java.time LocalDate DateTimeException]))

(def allowed-statuses #{"backlog" "in-progress" "review" "blocked" "done"})
(def allowed-priorities #{"P0" "P1" "P2"})
(def allowed-milestones (set (map #(str "M" %) (range 0 10))))
(def required-fields
  [:id :title :status :priority :milestone :prd :requirements :owner :depends_on
   :planned_start :planned_end :estimate :acceptance :evidence])

(defn- inline-list [value]
  (let [inner (some-> value (str/replace-first #"^\[" "") (str/replace-first #"\]$" ""))]
    (if (str/blank? inner)
      []
      (mapv str/trim (str/split inner #",")))))

(defn parse-frontmatter [text]
  (let [lines (str/split-lines text)
        closing-index (->> (rest lines)
                           (keep-indexed #(when (= "---" %2) (inc %1)))
                           first)]
    (when-not (and (= "---" (first lines)) closing-index)
      (throw (ex-info "Ticket has no YAML frontmatter" {})))
    (loop [remaining (subvec (vec lines) 1 closing-index)
           result {}
           block-key nil]
      (if-let [line (first remaining)]
        (cond
          (re-matches #"\s+-\s+.+" line)
          (recur (subvec remaining 1)
                 (update result block-key (fnil conj []) (str/trim (subs (str/trim line) 1)))
                 block-key)

          :else
          (let [[_ key raw] (re-matches #"([^:]+):\s*(.*)" line)
                keyword-key (keyword key)
                value (cond
                        (str/starts-with? raw "[") (inline-list raw)
                        (str/blank? raw) []
                        :else raw)]
            (recur (subvec remaining 1)
                   (assoc result keyword-key value)
                   (when (str/blank? raw) keyword-key))))
        result))))

(defn read-tickets
  ([] (read-tickets (io/file "tickets")))
  ([directory]
   (->> (file-seq directory)
        (filter #(.isFile %))
        (filter #(re-matches #"SLOP-\d{3}\.md" (.getName %)))
        (mapv (fn [file]
                (assoc (parse-frontmatter (slurp file)) :file (.getPath file)))))))

(defn- valid-date? [value]
  (try
    (LocalDate/parse value)
    true
    (catch DateTimeException _ false)))

(defn- cycle-errors [tickets]
  (let [graph (into {} (map (juxt :id #(set (:depends_on %))) tickets))
        errors (atom [])]
    (letfn [(visit [node visiting visited]
              (cond
                (contains? visiting node)
                (swap! errors conj {:code :dependency-cycle :ticket node})

                (contains? visited node) visited

                :else
                (reduce (fn [seen dependency]
                          (visit dependency (conj visiting node) seen))
                        (conj visited node)
                        (get graph node))))]
      (reduce (fn [visited node] (visit node #{} visited)) #{} (keys graph))
      (vec (distinct @errors)))))

(defn validate-ticket-set [tickets]
  (let [ids (map :id tickets)
        id-set (set ids)
        duplicate-ids (->> (frequencies ids) (keep #(when (> (val %) 1) (key %))))
        in-progress (filter #(= "in-progress" (:status %)) tickets)]
    (vec
     (concat
      (map #(hash-map :code :duplicate-id :ticket %) duplicate-ids)
      (when (> (count in-progress) 1)
        [{:code :multiple-in-progress :tickets (mapv :id in-progress)}])
      (mapcat
       (fn [ticket]
         (let [missing-fields (filter #(nil? (get ticket %)) required-fields)
               missing-dependencies (set/difference (set (:depends_on ticket)) id-set)]
           (concat
            (map #(hash-map :code :missing-field :ticket (:id ticket) :field %) missing-fields)
            (when-not (contains? allowed-statuses (:status ticket))
              [{:code :invalid-status :ticket (:id ticket)}])
            (when-not (contains? allowed-priorities (:priority ticket))
              [{:code :invalid-priority :ticket (:id ticket)}])
            (when-not (contains? allowed-milestones (:milestone ticket))
              [{:code :invalid-milestone :ticket (:id ticket)}])
            (when (str/blank? (:prd ticket))
              [{:code :missing-prd :ticket (:id ticket)}])
            (when (empty? (:requirements ticket))
              [{:code :missing-requirements :ticket (:id ticket)}])
            (map #(hash-map :code :missing-dependency :ticket (:id ticket) :dependency %)
                 missing-dependencies)
            (when-not (and (valid-date? (:planned_start ticket))
                           (valid-date? (:planned_end ticket)))
              [{:code :invalid-date :ticket (:id ticket)}])
            (when (and (valid-date? (:planned_start ticket))
                       (valid-date? (:planned_end ticket))
                       (.isAfter (LocalDate/parse (:planned_start ticket))
                                 (LocalDate/parse (:planned_end ticket))))
              [{:code :date-order :ticket (:id ticket)}])
            (when (and (= "done" (:status ticket))
                       (not= "passed" (:acceptance ticket)))
              [{:code :done-acceptance :ticket (:id ticket)}])
            (when (and (= "done" (:status ticket))
                       (empty? (:evidence ticket)))
              [{:code :done-evidence :ticket (:id ticket)}]))))
       tickets)
      (cycle-errors tickets)))))

(defn- board-statuses [readme]
  (->> (str/split-lines readme)
       (keep (fn [line]
               (when (str/starts-with? line "| [SLOP-")
                 (let [cells (mapv str/trim (str/split line #"\|"))
                       id (re-find #"SLOP-\d{3}" (get cells 1 ""))]
                   [id (get cells 3)]))))
       (into {})))

(defn repository-errors [tickets]
  (let [board (board-statuses (slurp "tickets/README.md"))]
    (vec
     (concat
      (validate-ticket-set tickets)
      (mapcat (fn [{:keys [id status evidence]}]
                (concat
                 (when (not= status (get board id))
                   [{:code :board-drift :ticket id :board (get board id) :actual status}])
                 (when (= "done" status)
                   (for [path evidence :when (not (.exists (io/file path)))]
                     {:code :missing-evidence-path :ticket id :path path}))))
              tickets)))))

(defn check! []
  (let [tickets (read-tickets)
        errors (repository-errors tickets)]
    (when (seq errors)
      (throw (ex-info "Ticket validation failed" {:errors errors})))
    {:tickets (count tickets) :errors 0 :in-progress (->> tickets
                                                          (filter #(= "in-progress" (:status %)))
                                                          first
                                                          :id)}))

(defn -main [& args]
  (when-not (= ["check"] (vec args))
    (throw (ex-info "Usage: clojure -M:tickets check" {})))
  (println (pr-str (check!))))
