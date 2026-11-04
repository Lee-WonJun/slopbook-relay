(ns slopbook.architecture-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopbook.architecture :as architecture]))

(deftest source-tree-obeys-dependency-rule
  (is (empty? (architecture/inspect-source))))

(deftest forbidden-import-fixtures
  (testing "domain cannot import platform or application"
    (is (= :domain-dependency
           (:rule (first (architecture/dependency-violations
                          'slopbook.identity.domain.character
                          'slopbook.platform.http))))))
  (testing "application cannot import an adapter"
    (is (= :application-to-adapter
           (:rule (first (architecture/dependency-violations
                          'slopbook.publishing.application.posts
                          'slopbook.publishing.adapters.out.memory))))))
  (testing "a context cannot import another context private layer"
    (is (= :foreign-private-context
           (:rule (first (architecture/dependency-violations
                          'slopbook.publishing.application.posts
                          'slopbook.identity.domain.character)))))))
