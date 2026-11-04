(ns slopbook.client.ai-progress-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [slopbook.client.ai-progress :as ai-progress]))

(deftest only-active-ai-transformations-open-the-progress-layer
  (testing "idle and unrelated loading states stay out of the global layer"
    (is (nil? (ai-progress/active-operation {})))
    (is (nil? (ai-progress/active-operation {:feed {:status :loading}
                                             :media {:post [{:status :uploading}]}}))))

  (testing "every user-facing text generation flow has contextual copy"
    (is (= {:kind :greeting :title-key :ai.progress.greeting :body-key :ai.progress.body}
           (ai-progress/active-operation {:persona {:greeting {:status :loading}}})))
    (is (= {:kind :post :title-key :ai.progress.post :body-key :ai.progress.body}
           (ai-progress/active-operation {:compose {:status :loading}})))
    (is (= {:kind :comment :title-key :ai.progress.comment :body-key :ai.progress.body}
           (ai-progress/active-operation {:thread {:status :submitting}})))
    (is (= {:kind :message :title-key :ai.progress.message :body-key :ai.progress.body}
           (ai-progress/active-operation {:messages {:detail-status :sending}})))
    (is (= {:kind :reaction :title-key :ai.progress.reaction
            :body-key :ai.progress.reaction_body}
           (ai-progress/active-operation {:reaction-ai-pending #{[:post "post-1"]}}))))

  (testing "a greeting preview takes precedence if stale states overlap"
    (is (= :greeting
           (:kind (ai-progress/active-operation
                   {:persona {:greeting {:status :loading}}
                    :compose {:status :loading}
                    :thread {:status :submitting}
                    :messages {:detail-status :sending}
                    :reaction-ai-pending #{[:post "post-1"]}}))))))
