(ns slopbook.qa-choice-layout-contract-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(deftest minimum-selection-message-keeps-a-reserved-layout-slot
  (let [app-source (slurp (io/file "src/slopbook/client/app.cljs"))
        css-source (slurp (io/file "resources/public/css/app.css"))]
    (testing "the validation node remains mounted when the message is empty"
      (is (str/includes? app-source "[:p.inline-error.choice-minimum-message"))
      (is (str/includes? app-source ":aria-hidden (not below-minimum?)")))
    (testing "the validation slot has a stable block size"
      (is (str/includes? css-source
                         ".choice-minimum-message { min-height: 20px;")))))
