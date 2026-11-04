(ns slopbook.i18n-contract-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn source-files []
  (->> (file-seq (io/file "src"))
       (filter #(.isFile %))
       (filter #(re-find #"\.clj[sc]?$" (.getName %)))))

(defn declared-error-codes []
  (->> (source-files)
       (mapcat (fn [file]
                 (let [source (slurp file)]
                   (concat
                    (map second (re-seq #"errors/fail!\s+\d+\s+\"([A-Z][A-Z0-9_]+)\"" source))
                    (map second (re-seq #":code\s+\"([A-Z][A-Z0-9_]+)\"" source))))))
       set))

(defn used-interface-message-keys []
  (->> [(io/file "src/slopbook/client/app.cljs")
        (io/file "src/slopbook/client/state.cljs")
        (io/file "src/slopbook/client/relay.cljs")]
       (mapcat (fn [file]
                 (map second
                      (re-seq #"\((?:t|tr)\s+db\s+:([A-Za-z0-9_.-]+)"
                              (slurp file)))))
       set))

(deftest every-source-error-code-is-present-in-the-client-catalog
  (let [catalog (slurp (io/file "src/slopbook/client/i18n.cljs"))
        missing (->> (declared-error-codes)
                     (remove #(or (str/includes? catalog (str ":error." % " ["))
                                  (str/includes? catalog (str "\"" % "\""))))
                     sort vec)]
    (is (empty? missing) (str "missing i18n error codes: " missing))))

(deftest every-static-interface-message-key-is-present-in-the-client-catalog
  (let [catalog (slurp (io/file "src/slopbook/client/i18n.cljs"))
        missing (->> (used-interface-message-keys)
                     (remove #(str/includes? catalog (str ":" % " [")))
                     sort vec)]
    (is (empty? missing) (str "missing i18n interface keys: " missing))))
