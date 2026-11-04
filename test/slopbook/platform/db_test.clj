(ns slopbook.platform.db-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopbook.platform.db :as db]))

(deftest current-schema-keeps-public-extension-objects-visible
  (testing "dedicated schema is first and public remains on the search path"
    (is (= "jdbc:postgresql://db/slopbook?sslmode=require&currentSchema=slopbook_app%2Cpublic"
           (db/with-current-schema
            "jdbc:postgresql://db/slopbook?sslmode=require&currentSchema=old"
            "slopbook_app"))))
  (testing "blank schema leaves the URL unchanged"
    (is (= "jdbc:postgresql://db/slopbook"
           (db/with-current-schema "jdbc:postgresql://db/slopbook" nil)))))
