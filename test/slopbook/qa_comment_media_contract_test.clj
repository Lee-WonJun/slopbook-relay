(ns slopbook.qa-comment-media-contract-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(deftest comment-media-is-hidden-in-the-client-and-guarded-on-the-server
  (let [app-source (slurp (io/file "src/slopbook/client/app.cljs"))
        state-source (slurp (io/file "src/slopbook/client/state.cljs"))
        server-source (slurp (io/file "src/slopbook/publishing/application/comments.clj"))]
    (testing "the comment composer does not render an upload picker"
      (is (not (str/includes? app-source
                              "[media-picker db \"comment\""))))
    (testing "the client never sends comment media IDs"
      (let [submit-start (.indexOf state-source ":comment/submit")
            submit-end (.indexOf state-source ":comment/success")
            submit-block (subs state-source submit-start submit-end)]
        (is (not (str/includes? submit-block ":media_ids")))))
    (testing "the server rejects non-empty legacy media input before rewriting"
      (is (str/includes? server-source "(when (seq (:media_ids input))"))
      (is (str/includes? server-source "\"COMMENT_MEDIA_DISABLED\"")))))
