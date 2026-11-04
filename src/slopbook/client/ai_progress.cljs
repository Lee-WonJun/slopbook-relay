(ns slopbook.client.ai-progress)

(defn active-operation [db]
  (cond
    (= :loading (get-in db [:persona :greeting :status]))
    {:kind :greeting :title-key :ai.progress.greeting :body-key :ai.progress.body}

    (= :loading (get-in db [:compose :status]))
    {:kind :post :title-key :ai.progress.post :body-key :ai.progress.body}

    (= :submitting (get-in db [:thread :status]))
    {:kind :comment :title-key :ai.progress.comment :body-key :ai.progress.body}

    (= :sending (get-in db [:messages :detail-status]))
    {:kind :message :title-key :ai.progress.message :body-key :ai.progress.body}

    (seq (:reaction-ai-pending db))
    {:kind :reaction :title-key :ai.progress.reaction :body-key :ai.progress.reaction_body}

    :else nil))
