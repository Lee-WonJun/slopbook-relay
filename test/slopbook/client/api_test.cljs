(ns slopbook.client.api-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [slopbook.client.api :as api]))

(deftest hard-browser-deadline-only-wraps-ai-writes
  (testing "AI-backed write routes end a stuck loading state"
    (doseq [path ["/api/v1/posts"
                  "/api/v1/posts/post-1/comments"
                  "/api/v1/targets/post/post-1/reaction"
                  "/api/v1/character-greetings/preview"
                  "/api/v1/conversations/conversation-1/messages"]]
      (is (api/ai-request? "POST" path))))

  (testing "reads, deletes, and non-AI writes are not mislabeled AI_TIMEOUT"
    (doseq [[method path] [["GET" "/api/v1/feed"]
                           ["DELETE" "/api/v1/comments/comment-1"]
                           ["POST" "/api/v1/characters"]
                           ["POST" "/api/v1/media"]
                           ["POST" "/api/v1/character-greetings"]]]
      (is (not (api/ai-request? method path))))))
