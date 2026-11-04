(ns slopbook.architecture
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def bounded-contexts
  #{"identity" "transformation" "publishing" "social" "messaging" "media" "trust"})

(def private-layers #{"domain" "ports" "adapters"})

(defn namespace-parts [namespace-symbol]
  (str/split (str namespace-symbol) #"\."))

(defn context-and-layer [namespace-symbol]
  (let [[root context layer & _] (namespace-parts namespace-symbol)]
    (when (and (= "slopbook" root) (contains? bounded-contexts context))
      {:context context :layer layer})))

(defn dependency-violations [source dependency]
  (let [{source-context :context source-layer :layer} (context-and-layer source)
        {dependency-context :context dependency-layer :layer} (context-and-layer dependency)
        foreign-context? (and source-context dependency-context
                              (not= source-context dependency-context))]
    (cond-> []
      (and (= "domain" source-layer)
           (or (= "platform" (second (namespace-parts dependency)))
               (contains? #{"application" "ports" "adapters"} dependency-layer)))
      (conj {:rule :domain-dependency :source source :dependency dependency})

      (and (= "application" source-layer)
           (= "adapters" dependency-layer))
      (conj {:rule :application-to-adapter :source source :dependency dependency})

      (and foreign-context? (contains? private-layers dependency-layer))
      (conj {:rule :foreign-private-context :source source :dependency dependency}))))

(defn- namespace-form [file]
  (with-open [reader (java.io.PushbackReader. (io/reader file))]
    (read {:eof nil} reader)))

(defn namespace-dependencies [file]
  (let [form (namespace-form file)
        source (second form)]
    {:source source
     :dependencies (->> (tree-seq coll? seq form)
                        (filter symbol?)
                        (filter #(str/starts-with? (str %) "slopbook."))
                        (remove #{source})
                        set)}))

(defn source-files
  ([] (source-files (io/file "src")))
  ([root]
   (->> (file-seq root)
        (filter #(.isFile %))
        (filter #(str/ends-with? (.getName %) ".clj")))))

(defn inspect-source
  ([] (inspect-source (source-files)))
  ([files]
   (mapcat (fn [file]
             (let [{:keys [source dependencies]} (namespace-dependencies file)]
               (mapcat #(dependency-violations source %) dependencies)))
           files)))

(defn assert-clean! []
  (let [violations (vec (inspect-source))]
    (when (seq violations)
      (throw (ex-info "Architecture dependency rules failed" {:violations violations})))
    {:namespaces (count (source-files)) :violations 0}))

(defn -main [& _]
  (println (pr-str (assert-clean!))))
