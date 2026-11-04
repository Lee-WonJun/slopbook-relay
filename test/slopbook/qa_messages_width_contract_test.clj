(ns slopbook.qa-messages-width-contract-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(deftest messages-use-the-standard-content-column
  (let [source (slurp (io/file "src/slopbook/client/app.cljs"))]
    (is (str/includes? source
                       "{:class (when (= :persona (:route db)) \"is-wide\")}"))
    (is (not (str/includes? source
                            "#{:persona :messages}")))))
