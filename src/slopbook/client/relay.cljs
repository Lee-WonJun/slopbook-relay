(ns slopbook.client.relay
  (:require [re-frame.core :as rf]
            [reagent.core :as r]
            [slopbook.client.api :as api]
            [slopbook.client.i18n :as i18n]))

(defn- t [db key] (i18n/t (:locale db) key))
(defn- error-value [error] {:code (or (.-code error) "REQUEST_FAILED")})

(defn load-feed! [state sort-order]
  (let [request-id (random-uuid)]
    (swap! state assoc :feed-status :loading :feed-error nil :sort sort-order :feed-request request-id)
    (-> (api/request "GET" (str "/api/v1/relay/posts?sort=" sort-order))
        (.then #(when (= request-id (:feed-request @state))
                  (swap! state assoc :feed-status :success :items (:items %))))
        (.catch #(when (= request-id (:feed-request @state))
                   (swap! state assoc :feed-status :error :feed-error (error-value %)))))))

(defn- brief! [state language]
  (let [request-id (random-uuid)
        post-id (get-in @state [:selected :id])]
    (swap! state assoc :brief-status :loading :brief-error nil :brief-request request-id)
    (-> (api/request "POST" "/api/v1/relay/brief" {:post_id post-id :output_language language})
        (.then #(when (= request-id (:brief-request @state))
                  (swap! state assoc :brief-status :success :brief %)))
        (.catch #(when (= request-id (:brief-request @state))
                   (swap! state assoc :brief-status :error :brief-error (error-value %)))))))

(defn- inline-error [db value]
  [:p.inline-error {:role "alert"} (i18n/error-text (:locale db) value)])

(defn- source-row [db state post]
  [:li.relay-source
   [:button.relay-source-button
    {:type "button"
     :on-click #(swap! state assoc :selected post :brief nil :brief-status :idle
                      :brief-error nil :brief-request nil)}
    [:span.relay-source-meta (str "m/" (:community post) " · @" (:author post))]
    [:span.relay-source-title (:title post)]
    [:span.relay-source-meta
     (str (:comment_count post) " " (t db :relay.comments))
     [:span.relay-source-open (t db :relay.open)]]]])

(defn- discussion [db state]
  (let [{:keys [selected brief brief-status brief-error]} @state
        current-brief? (= (:locale db) (get-in brief [:brief :output_language]))]
    [:<>
     [:article.relay-discussion
      [:button.quiet-button
       {:type "button" :on-click #(swap! state assoc :selected nil :brief-request nil)}
       (str "← " (t db :nav.relay))]
      [:div.relay-source-meta (str "m/" (:community selected) " · @" (:author selected))]
      [:h2 (:title selected)]
      [:p.relay-source-content {:lang "en"} (:content selected)]
      [:a.relay-source-link {:href (:url selected) :target "_blank" :rel "noopener noreferrer"}
       (t db :relay.original) " ↗"]]
     [:details.relay-brief
      [:summary (t db :relay.brief_title)]
      (cond
        (= :loading brief-status)
        [:p.relay-help {:role "status"} (t db :relay.brief_loading)]

        current-brief?
        [:div.relay-brief-result {:aria-live "polite"}
         (for [[key label] [[:summary :relay.claim] [:different_view :relay.other_voices]
                            [:open_question :relay.question]]]
           ^{:key key} [:section.relay-brief-item
                        [:h3 (t db label)] [:p (get-in brief [:brief key])]])
         [:p.relay-provenance
          (str (t db :relay.sample) " " (:comment_sample_size brief) " · " (t db :relay.unverified))]]

        :else [:p.relay-help (t db :relay.brief_hint)])
      (when brief-error [inline-error db brief-error])
      (when-not current-brief?
        [:button.secondary-button
         {:type "button" :disabled (= :loading brief-status)
          :on-click #(brief! state (:locale db))}
         (t db (if brief-error :common.retry :relay.make_brief))])]
     [:div.relay-response
      [:button.primary-button
       {:type "button" :on-click #(rf/dispatch [:compose/source selected])}
       (t db :relay.respond_title)]]]))

(defn view [db]
  (r/with-let [state (r/atom {:sort "hot" :feed-status :loading :items []})
               _ (load-feed! state "hot")]
    [:section.feed {:aria-label (t db :relay.live_feed)}
     (if (:selected @state)
       [discussion db state]
       [:<>
        [:div.relay-feed-heading
         [:span.handle (t db :relay.live_feed)]
         [:div.relay-sort {:role "group" :aria-label (t db :relay.sort)}
          (for [[sort-order label] [["hot" :relay.hot] ["new" :relay.latest]]]
            ^{:key sort-order}
            [:button.quiet-button {:type "button" :aria-pressed (= sort-order (:sort @state))
                                   :on-click #(load-feed! state sort-order)}
             (t db label)])]]
        (case (:feed-status @state)
          :loading [:p.relay-state {:role "status"} (t db :common.loading)]
          :error [:div.relay-state [inline-error db (:feed-error @state)]
                  [:button.quiet-button {:type "button" :on-click #(load-feed! state (:sort @state))}
                   (t db :common.retry)]]
          (if (seq (:items @state))
            [:ul.relay-source-list
             (for [post (:items @state)] ^{:key (:id post)} [source-row db state post])]
            [:p.relay-state (t db :relay.empty)]))])]))
