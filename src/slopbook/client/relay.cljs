(ns slopbook.client.relay
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [slopbook.client.api :as api]
            [slopbook.client.grapheme :as grapheme]
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

(defn- choose! [state post]
  (swap! state assoc :selected post :brief nil :brief-status :idle :brief-error nil
         :brief-request nil :intent "" :publish-status :idle :publish-error nil
         :publish-key nil :published nil))

(defn- brief! [state language]
  (let [request-id (random-uuid)
        post-id (get-in @state [:selected :id])]
    (swap! state assoc :brief-status :loading :brief-error nil :brief-request request-id)
    (-> (api/request "POST" "/api/v1/relay/brief" {:post_id post-id :output_language language})
        (.then #(when (= request-id (:brief-request @state))
                  (swap! state assoc :brief-status :success :brief %)))
        (.catch #(when (= request-id (:brief-request @state))
                   (swap! state assoc :brief-status :error :brief-error (error-value %)))))))

(defn- publish! [state actor]
  (let [idempotency (or (:publish-key @state) (api/idempotency-key))
        post-id (get-in @state [:selected :id])
        payload {:post_id post-id :actor_character_id (:id actor) :input_text (:intent @state)}]
    (swap! state assoc :publish-key idempotency :publish-status :loading :publish-error nil)
    (-> (api/request "POST" "/api/v1/relay/posts" payload idempotency)
        (.then (fn [post]
                 (when (= idempotency (:publish-key @state))
                   ;; Clear private intent immediately after a successful publication.
                   (swap! state assoc :publish-status :success :published post :intent "")
                   (rf/dispatch [:feed/load :latest]))))
        (.catch #(when (= idempotency (:publish-key @state))
                   (swap! state assoc :publish-status :error :publish-error (error-value %)))))))

(defn- inline-error [db value]
  [:p.inline-error {:role "alert"} (i18n/error-text (:locale db) value)])

(defn- source-row [db state post]
  (let [selected? (= (:id post) (get-in @state [:selected :id]))]
    [:li.relay-source
     {:class (when selected? "is-selected")}
     [:button.relay-source-button
      {:type "button" :aria-pressed selected?
       :disabled (= :loading (:publish-status @state))
       :on-click #(choose! state post)}
      [:span.relay-source-meta (str "m/" (:community post) " · " (:author post))]
      [:span.relay-source-title (:title post)]
      [:span.relay-source-meta
       (str (:comment_count post) " " (t db :relay.comments))
       [:span.relay-source-open (if selected? (t db :relay.selected) (t db :relay.open))]]]]))

(defn- discussion [db state actor]
  (let [{:keys [selected brief brief-status brief-error intent publish-status publish-error published]} @state
        current-brief? (= (:locale db) (get-in brief [:brief :output_language]))
        busy? (= :loading publish-status)
        length (grapheme/length (str intent "\n" (:url selected)))]
    [:section.relay-discussion
     {:aria-labelledby "relay-discussion-title" :tab-index -1
      :ref (fn [element]
             (when (and element (not= (:id selected) (:focused-source @state)))
               (swap! state assoc :focused-source (:id selected))
               (.focus element)))}
     [:header.relay-discussion-header
      [:span.eyebrow (t db :relay.reading)]
      [:h2#relay-discussion-title (:title selected)]
      [:a.relay-source-link {:href (:url selected) :target "_blank" :rel "noopener noreferrer"}
       (t db :relay.original) " ↗"]]
     [:details.relay-excerpt
      [:summary (t db :relay.excerpt)]
      [:p {:lang "en"} (:content selected)]]
     [:section.relay-brief {:aria-label (t db :relay.brief_title) :aria-busy (= :loading brief-status)}
      [:div.relay-section-heading
       [:h3 (t db :relay.brief_title)]
       [:span.relay-model "Muse Spark 1.3"]]
      (cond
        (= :loading brief-status)
        [:div.relay-loading {:role "status"}
         [:span.relay-loading-line] [:span.relay-loading-line] [:span.relay-loading-line]
         [:p (t db :relay.brief_loading)]]

        current-brief?
        [:div.relay-brief-result {:aria-live "polite"}
         (for [[key label] [[:summary :relay.claim] [:different_view :relay.other_voices]
                            [:open_question :relay.question]]]
           ^{:key key} [:section.relay-brief-item
                        [:h4 (t db label)] [:p (get-in brief [:brief key])]])
         [:p.relay-provenance
          (str (t db :relay.sample) " " (:comment_sample_size brief) " · " (t db :relay.unverified))]]

        :else [:p.relay-help (t db :relay.brief_hint)])
      (when brief-error [inline-error db brief-error])
      (when-not current-brief?
        [:button.primary-button
         {:type "button" :disabled (= :loading brief-status)
          :on-click #(brief! state (:locale db))}
         (t db (if brief-error :common.retry :relay.make_brief))])]
     [:section.relay-response {:aria-label (t db :relay.respond_title)}
      [:div.relay-section-heading [:h3 (t db :relay.respond_title)]]
      (if published
        [:div.relay-published {:role "status"}
         [:span.eyebrow (t db :relay.published)]
         [:p (:text published)]
         [:button.quiet-button {:type "button" :on-click #(rf/dispatch [:thread/open published])}
          (t db :relay.view_post) " →"]]
        (if actor
          [:form {:on-submit #(do (.preventDefault %) (when-not busy? (publish! state actor)))}
           [:p.relay-actor (str (t db :relay.as) " " (:display_name actor) " · @" (:handle actor))]
           [:label {:for "relay-intent"} (t db :relay.intent)]
           [:textarea#relay-intent
            {:value intent :rows 3 :disabled busy? :aria-describedby "relay-intent-help"
             :placeholder (t db :relay.intent_placeholder)
             :on-change #(swap! state assoc :intent (.. % -target -value)
                               :publish-key nil :publish-error nil)}]
           [:p#relay-intent-help.relay-help (t db :relay.privacy)]
           [:div.relay-publish-footer
            [:span.relay-counter {:class (when (> length 280) "is-invalid")}
             (str length " / 280 · " (t db :relay.link_included))]
            [:button.primary-button
             {:type "submit" :disabled (or busy? (str/blank? intent) (> length 280))}
             (t db (if busy? :relay.publishing :relay.publish))]]]
          [:div.relay-sign-in
           [:p (t db :relay.sign_in_hint)]
           [:button.primary-button
            {:type "button" :on-click #(rf/dispatch (if (contains? #{:authenticated :development}
                                                                 (get-in db [:auth :status]))
                                                        [:navigate :persona] [:auth/login]))}
            (t db (if (contains? #{:authenticated :development} (get-in db [:auth :status]))
                    :roster.new :relay.sign_in))]]))
      (when publish-error [inline-error db publish-error])]]))

(defn view [db actor]
  (r/with-let [state (r/atom {:sort "hot" :feed-status :loading :items [] :intent ""})
               _ (load-feed! state "hot")]
    [:div.relay-view
     [:header.relay-header
      [:div.relay-edition "Meta Global AI Developer Hackathon · 2026"]
      [:p.relay-tagline "Character. Beyond Anonymity."]
      [:p.relay-intro (t db :relay.intro)]]
     [:div.relay-feed-heading
      [:h2 (t db :relay.live_feed)]
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
         [:p.relay-state (t db :relay.empty)]))
     (when (:selected @state) [discussion db state actor])
     [:p.relay-footnote (t db :relay.footnote)]]))
