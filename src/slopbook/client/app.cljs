(ns slopbook.client.app
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [reagent.dom.client :as rdom]
            [slopbook.client.ai-progress :as ai-progress]
            [slopbook.client.content-language :as content-language]
            [slopbook.client.feed-visibility :as feed-visibility]
            [slopbook.client.grapheme :as grapheme]
            [slopbook.client.icons :as icons]
            [slopbook.client.ime :as ime]
            [slopbook.client.i18n :as i18n]
            [slopbook.client.media :as media]
            [slopbook.client.randomizer :as randomizer]
            [slopbook.client.reaction-ui :as reaction-ui]
            [slopbook.client.relay :as relay]
            [slopbook.client.state]
            [slopbook.client.turnstile :as turnstile]
            [slopbook.shared.domain.greeting :as greeting]))

(defonce root (atom nil))
(defonce history-listener-installed? (atom false))

(defn t
  ([db key] (i18n/t (:locale db) key))
  ([db key replacements] (i18n/t (:locale db) key replacements)))

(defn label [value]
  (-> (or value "") name (str/replace "_" " ") str/capitalize))

(defn owned-public [owned] (:public_character owned))

(defn signed-in? [db]
  (contains? #{:authenticated :development} (get-in db [:auth :status])))

(defn active-character [db]
  (some (fn [owned]
          (when (= (:active-character-id db) (get-in owned [:public_character :id]))
            (owned-public owned)))
        (get-in db [:me :owned_characters])))

(defn avatar-source [avatar]
  (or (:url avatar) (get-in avatar [:fallback :url])))

(defn avatar [db {:keys [avatar display_name]} class-name]
  (let [fallback (get-in avatar [:fallback :url])
        sources (when (= "persona_sigil" (:kind avatar)) (:sources avatar))
        src-set (when (map? sources)
                  (str (:feed_2x sources) " 80w, "
                       (:thread_2x sources) " 96w, "
                       (:profile_2x sources) " 224w"))
        source (avatar-source avatar)]
    [:img.avatar
     (cond-> {:class class-name
              :src source
              :alt (t db (if (= "persona_sigil" (:kind avatar))
                           :avatar.alt
                           :avatar.uploaded_alt)
                      {:name display_name})
              :width (if (= class-name "large") 112 40)
              :height (if (= class-name "large") 112 40)
              :on-error (fn [event]
                          (let [target (.-target event)]
                            (set! (.-onerror target) nil)
                            (set! (.-srcset target) "")
                            (set! (.-src target) (or fallback source))))}
       src-set (assoc :src-set src-set
                      :sizes (if (= class-name "large") "112px" "40px")))]))

(def nav-items
  [[:feed "⌂" :nav.home]
   [:search "⌕" :nav.search]
   [:notifications "◌" :nav.notifications]
   [:messages "✉" :nav.messages]
   [:profile "◎" :nav.profile]])

(defn nav-button [db [route icon key]]
  (let [unread (case route
                 :notifications (get-in db [:notifications :unread-count])
                 :messages (reduce + 0 (map #(or (:unread_count %) 0)
                                            (get-in db [:messages :items])))
                 nil)
        unread-key (when unread
                     (if (= route :notifications)
                       :nav.unread_notifications
                       :nav.unread_messages))
        accessible-label (if (pos? (or unread 0))
                           (t db unread-key {:count unread})
                           (t db key))]
    [:button.nav-button
     {:type "button"
      :aria-label accessible-label
      :aria-current (when (or (= route (:route db))
                              (and (= route :feed) (= :relay (:route db)))) "page")
      :on-click #(rf/dispatch [:navigate route])}
     [:span.nav-icon {:aria-hidden "true"} icon]
     [:span.nav-label (t db key)]
     (when (pos? (or unread 0))
       [:span.nav-badge
        {:aria-hidden "true"}
        (min unread 99)])]))

(defn character-limit [db] (or (get-in db [:me :character_limit]) 5))

(defn remaining-slots [db]
  (or (get-in db [:me :remaining_character_slots])
      (max 0 (- (character-limit db) (count (get-in db [:me :owned_characters]))))))

(defn character-summary [db character]
  (->> (concat (get-in character [:persona_public :what_are_you])
               (get-in character [:persona_public :archetypes]))
       (take 2)
       (map #(i18n/catalog-label (:locale db) %))
       (str/join " · ")))

(declare persona-sigil)

(defn roster-row [db owned]
  (let [character (owned-public owned)
        active? (= (:id character) (:active-character-id db))]
    [:button.roster-row
     {:type "button"
      :aria-pressed active?
      :on-click #(rf/dispatch [:active/select (:id character)])}
     [avatar db character "roster"]
     [:span.roster-copy
      [:span.display-name (:display_name character)]
      [:span.roster-meta (str "@" (:handle character))]
      [:span.roster-meta (character-summary db character)]]
     [:span.roster-check {:aria-hidden "true"} (when active? "●")]]))

(defn character-roster [db]
  (let [characters (get-in db [:me :owned_characters])
        signed-in? (signed-in? db)
        guest-configured? (true? (get-in db [:anonymous :configured]))
        guest (get-in db [:anonymous :persona])
        slots (remaining-slots db)]
    [:section.character-roster {:aria-label (t db :roster.title)}
     [:div.roster-heading
      [:span (t db :roster.title)]
      (when signed-in?
        [:span (t db :roster.capacity {:count (count characters)
                                       :limit (character-limit db)})])]
     (cond
       (seq characters)
       [:div.roster-list
        (for [owned characters]
          ^{:key (get-in owned [:public_character :id])} [roster-row db owned])]

       signed-in?
       [:p.roster-empty (t db :roster.empty)]

       guest-configured?
       [:button.roster-row.guest-roster-row
        {:type "button" :on-click #(rf/dispatch [:guest/start :feed])}
        [persona-sigil db (get-in db [:anonymous :seed])
         (:display_name guest) "roster" 80]
        [:span.roster-copy
         [:span.display-name (:display_name guest)]
         [:span.roster-meta (t db :anonymous.browser_only)]
         [:span.roster-meta (character-summary db guest)]]
        [:span.roster-check {:aria-hidden "true"} "›"]]

       :else
       [:p.roster-empty (t db :roster.signed_out)])
     [:button.roster-new
      {:type "button"
       :disabled (and signed-in? (zero? slots))
       :on-click #(rf/dispatch (if signed-in?
                                 [:persona/start]
                                 [:guest/start :feed]))}
      [:span {:aria-hidden "true"} (if (and (not signed-in?) guest-configured?) "✎" "+")]
      (t db (if signed-in?
              (if (zero? slots) :roster.full :roster.new)
              (if guest-configured? :anonymous.edit :anonymous.create)))]]))

(defn left-rail [db]
  [:aside.left-rail {:aria-label (t db :nav.primary)}
   [:div.brand-block
    [:a.wordmark {:href "#" :on-click #(do (.preventDefault %) (rf/dispatch [:navigate :feed]))}
     "Slopbook"]
    [:div.brand-tagline (t db :brand.tagline)]]
   [:nav.rail-nav
   (for [item nav-items] ^{:key (first item)} [nav-button db item])]
   [:button.primary-button.compose-rail-button
    {:type "button" :aria-label (t db :nav.write)
     :on-click #(rf/dispatch [:navigate :compose])}
    [:span.compose-rail-icon {:aria-hidden "true"} "✎"]
    [:span.compose-rail-label (t db :nav.write)]]
   [character-roster db]])

(defn auth-label [full short]
  [:<> [:span.auth-label-full full] [:span.auth-label-short short]])

(defn auth-control [db]
  (let [status (get-in db [:auth :status])
        enabled? (true? (get-in db [:client-config :auth :enabled]))]
    (cond
      (contains? #{:authenticated :development} status)
      [:button.secondary-button.auth-button
       {:type "button" :aria-label (t db :auth.log_out)
        :on-click #(rf/dispatch [:auth/logout])}
       [auth-label (t db :auth.log_out) (t db :auth.log_out)]]

      (= :signing-out status)
      [:button.secondary-button.auth-button
       {:type "button" :aria-label (t db :auth.signing_out) :disabled true}
       [auth-label (t db :auth.signing_out) "…"]]

      (and (true? (get-in db [:client-config :dev_auth])) (= :anonymous status))
      [:button.secondary-button.auth-button
       {:type "button" :aria-label (t db :auth.development_sign_in)
        :on-click #(rf/dispatch [:auth/development-login])}
       [auth-label (t db :auth.development_sign_in) (t db :auth.development_sign_in)]]

      (and enabled? (contains? #{:anonymous :error :expired} status))
      (let [key (if (= :expired status) :auth.sign_in_again :auth.continue_google)]
        [:button.primary-button.auth-button
         {:type "button" :aria-label (t db key) :on-click #(rf/dispatch [:auth/login])}
         [auth-label (t db key) "Google"]])

      (= :redirecting status)
      [:button.primary-button.auth-button
       {:type "button" :aria-label (t db :auth.opening_google) :disabled true}
       [auth-label (t db :auth.opening_google) "…"]]

      :else nil)))

(defn auth-notice [db]
  (let [status (get-in db [:auth :status])]
    (when (contains? #{:error :expired :unavailable} status)
      [:div.auth-notice {:role "alert"}
       [:span (t db :auth.check_again)]
       (when (true? (get-in db [:client-config :auth :enabled]))
         [:button.quiet-button {:type "button" :on-click #(rf/dispatch [:auth/login])}
          (t db :auth.retry_google)])])))

(defn mobile-character-control [db]
  (let [characters (get-in db [:me :owned_characters])
        signed-in? (signed-in? db)
        guest-configured? (true? (get-in db [:anonymous :configured]))]
    (if signed-in?
      (when (seq characters)
      [:div.mobile-character-control
       [:label.visually-hidden {:for "mobile-character-select"} (t db :shell.change_character)]
       [:select#mobile-character-select.mobile-character-select
        {:name "mobile-character-select"
         :value (or (:active-character-id db) "")
         :on-change #(rf/dispatch [:active/select (.. % -target -value)])}
        (for [owned characters
              :let [character (owned-public owned)]]
          ^{:key (:id character)}
          [:option {:value (:id character)} (:display_name character)])]
       [:button.icon-button
        {:type "button" :aria-label (t db :shell.new_character)
         :disabled (zero? (remaining-slots db))
         :on-click #(rf/dispatch [:persona/start])}
        "+"]])
      [:div.mobile-character-control
       [:button.mobile-guest-button
        {:type "button"
         :on-click #(rf/dispatch [:guest/start (:route db)])}
        (if guest-configured?
          (get-in db [:anonymous :persona :display_name])
          (t db :anonymous.create))]])))

(def locale-options
  [{:code "en" :flag-src "/icons/flags/en.svg" :native-name "English" :short-label "EN"}
   {:code "ko" :flag-src "/icons/flags/ko.svg" :native-name "한국어" :short-label "KO"}
   {:code "zh-Hans" :flag-src "/icons/flags/zh-Hans.svg" :native-name "简体中文" :short-label "ZH"}
   {:code "ja" :flag-src "/icons/flags/ja.svg" :native-name "日本語" :short-label "JA"}])

(defn locale-flag [class-name flag-src]
  [:img {:class class-name
         :src flag-src
         :alt ""
         :aria-hidden "true"
         :width 24
         :height 16
         :draggable "false"}])

(defn language-picker [db]
  (r/with-let [open? (r/atom false)
               root-node (atom nil)
               trigger-node (atom nil)
               selected-node (atom nil)
               focus-trigger! (fn []
                                (js/setTimeout
                                 #(when-let [node @trigger-node] (.focus node)) 0))
               close! (fn [return-focus?]
                        (reset! open? false)
                        (when return-focus? (focus-trigger!)))
               select! (fn [locale]
                         (close! true)
                         (rf/dispatch [:locale/select locale]))
               handle-document-pointer
               (fn [event]
                 (when (and @open?
                            @root-node
                            (not (.contains @root-node (.-target event))))
                   (close! false)))
               handle-document-keydown
               (fn [event]
                 (when (and @open? (= "Escape" (.-key event)))
                   (.preventDefault event)
                   (close! true)))
               pointer-listener
               (.addEventListener js/document "pointerdown" handle-document-pointer)
               keydown-listener
               (.addEventListener js/document "keydown" handle-document-keydown)]
    (let [current-locale (i18n/normalize-locale (:locale db))
          current (or (some #(when (= current-locale (:code %)) %) locale-options)
                      (first locale-options))
          current-language (t db (keyword (str "language." current-locale)))
          open-picker! (fn []
                         (let [opening? (not @open?)]
                           (reset! open? opening?)
                           (when opening?
                             (js/setTimeout
                              #(when-let [node @selected-node] (.focus node)) 0))))]
      [:div.language-picker {:ref #(reset! root-node %)}
       [:button.icon-button.language-trigger
        {:type "button"
         :ref #(reset! trigger-node %)
         :aria-label (t db :language.open_picker {:language current-language})
         :aria-haspopup "dialog"
         :aria-expanded (str @open?)
         :aria-controls "language-picker-dialog"
         :on-click open-picker!}
        [locale-flag "language-trigger-flag" (:flag-src current)]
        [:span.language-trigger-code (:short-label current)]]
       (when @open?
         [:section#language-picker-dialog.language-popover
          {:role "dialog"
           :aria-labelledby "language-picker-title"}
          [:div.language-popover-heading
           [:span#language-picker-title (t db :language.choose)]]
          (into
           [:div.language-options]
           (for [{:keys [code flag-src native-name]} locale-options
                 :let [selected? (= current-locale code)]]
             ^{:key code}
             [:button.language-option
              (cond-> {:type "button"
                       :lang code
                       :on-click #(select! code)}
                selected? (assoc :ref #(reset! selected-node %)
                                 :aria-current "true"))
              [locale-flag "language-option-flag" flag-src]
              [:span.language-option-name native-name]
              [:span.language-option-status
               (when selected? (t db :language.selected))]]))])])
    (finally
      (.removeEventListener js/document "pointerdown" handle-document-pointer)
      (.removeEventListener js/document "keydown" handle-document-keydown))))

(defn page-header [db title subtitle & [action]]
  [:header.page-header
   [:div [:h1.page-title title] (when subtitle [:div.page-subtitle subtitle])]
   [:div.header-actions
    action
    [mobile-character-control db]
    [auth-control db]
    [language-picker db]
    [:button.icon-button
     {:type "button" :aria-label (t db :shell.change_theme)
      :on-click #(rf/dispatch [:theme/cycle])}
     (case (:theme db) :light "☀" :dark "☾" "◐")]]])

(defn status-banner [db]
  (when (= "read_only" (get-in db [:status :mode]))
    [:div.status-banner {:role "status"} (t db :shell.read_only)]))

(defn persona-chips [db public]
  (let [choices (concat (take 2 (:what_are_you public))
                        (take 1 (:archetypes public))
                        (take 3 (:traits public)))]
    [:div.persona-strip
     (for [[index choice] (map-indexed vector choices)]
       ^{:key (str index "-" (:id choice))}
       [:span.chip (i18n/catalog-label (:locale db) choice)])]))

(defn reaction-key [value] (keyword (str "reaction." value)))

(defn reaction-summary [db counts]
  (let [received (reaction-ui/received-reactions counts)
        total (reaction-ui/total counts)]
    (when (seq received)
      [:div.reaction-summary
       {:aria-label (t db :reaction.summary {:count total})}
       [:span.reaction-summary-icons {:aria-hidden "true"}
        (for [{:keys [reaction]} received]
          ^{:key reaction}
          [:span.reaction-summary-icon
           {:class (str "is-" reaction)}
           [icons/reaction reaction]])]
       [:span.reaction-summary-count total]
       [:span.visually-hidden
        (str/join ", "
                  (map (fn [{:keys [reaction count]}]
                         (str (t db (reaction-key reaction)) " " count))
                       received))]])))

(defn reaction-control [db target-type value]
  (let [target-id (:id value)
        key [target-type target-id]
        pending? (contains? (:reaction-pending db) key)
        choosing? (contains? (:reaction-ai-pending db) key)
        viewer (:viewer_reaction value)
        first-decision? (reaction-ui/first-decision? viewer)
        active? (true? (:active viewer))
        reaction (:reaction viewer)
        reaction-label (when (and active? reaction) (t db (reaction-key reaction)))]
    [:button.post-action
     {:type "button" :aria-pressed active? :disabled pending?
      :aria-label (if (and active? reaction)
                    reaction-label
                    (t db :reaction.ai_choice_label))
      :on-click #(rf/dispatch [:reaction/toggle target-type target-id first-decision?])}
     (if pending?
       [:span.reaction-idle
        [icons/reaction-choice]
        (t db (if choosing? :reaction.choosing :reaction.updating))]
       [:span.reaction-idle
        (if (and active? reaction)
          [icons/reaction reaction]
          [icons/reaction-choice])
        [:span (or reaction-label (t db :reaction.react))]])]))

(defn repost-control [db post]
  (let [post-id (:id post)
        pending? (contains? (:repost-pending db) post-id)
        active? (true? (get-in post [:viewer_repost :active]))
        count (or (:repost_count post) 0)]
    [:button.post-action
     {:type "button" :aria-pressed active? :disabled pending?
      :aria-label (if (pos? count)
                    (t db :repost.count {:count count})
                    (t db :repost.action))
      :on-click #(rf/dispatch [:repost/toggle post-id])}
     [:span.reaction-idle
      [icons/repost]
      [:span (cond
               pending? (t db :repost.pending)
               (pos? count) (t db :repost.count {:count count})
               :else (t db :repost.action))]]]))

(defn media-url [item]
  (or (:preview item) (:url (last (sort-by :width (:variants item))))))

(defn media-gallery [db media]
  (when (seq media)
    [:div.media-gallery
     {:class (str "media-count-" (min 4 (count media)))
      :aria-label (t db :post.media_gallery)}
     (for [item media
           :let [sources (sort-by :width (:variants item))
                 srcset (str/join ", " (map #(str (:url %) " " (:width %) "w") sources))]]
       ^{:key (:id item)}
       [:img.post-media
        {:src (media-url item) :src-set srcset
         :sizes "(max-width: 767px) 100vw, 560px"
         :alt (or (:alt_text item) "")
         :loading "lazy" :decoding "async"
         :style {:background-color (:average_color item)}}])]))

(defn post-row [db post]
  (let [character (:character post)]
    [:article.feed-row {:data-post-id (:id post)}
     [avatar db character nil]
     [:div.post-body
      (when-let [reposter (get-in post [:repost_context :character])]
        [:div.repost-context
         [icons/repost]
         [:button.profile-link
          {:type "button" :on-click #(rf/dispatch [:profile/open (:handle reposter)])}
          (t db :repost.context {:name (:display_name reposter)})]])
      [:div.post-head
       [:button.profile-link
       {:type "button" :on-click #(rf/dispatch [:profile/open (:handle character)])}
        (:display_name character)]
       (when (:human_owned character)
         [icons/human-owned-mark (t db :profile.human_owned)])
       [:span.handle (str "@" (:handle character))]
       [:span.post-time (str "· " (i18n/time-ago (:locale db) (:created_at post)))]]
      [:p.post-text {:lang (content-language/for-value post)} (:text post)]
      [media-gallery db (:media post)]
      [persona-chips db (:persona_public character)]
      [reaction-summary db (:reaction_counts post)]
      [:div.post-actions
       [:button.post-action
       {:type "button" :on-click #(rf/dispatch [:thread/open post])}
        (t db :post.comment {:count (:comment_count post)})]
       [reaction-control db :post post]
       [repost-control db post]
       [:button.post-action
        {:type "button" :aria-label (t db :post.share)
         :on-click #(rf/dispatch [:post/share (:id post)])}
        [:span.reaction-idle [icons/share] [:span (t db :post.share)]]]]]]))

(defn skeleton-feed []
  [:div
   (for [index (range 4)]
     ^{:key index}
     [:div.skeleton-row
      [:div.skeleton.avatar]
      [:div.skeleton-lines [:div.skeleton.skeleton-line.short] [:div.skeleton.skeleton-line]]])])

(defn limit-count [db current maximum kind]
  [:span.field-count
   {:aria-label (t db (if (= kind :characters)
                        :persona.character_count
                        :persona.selection_count)
                   {:count current :limit maximum})}
   (str current " / " maximum)])

(defn media-picker [db purpose items]
  (let [items (if (= purpose "avatar") (when items [items]) items)
        maximum (if (= purpose "avatar") 1 4)]
    [:div.media-picker
     (when (seq items)
       ^{:key "media-previews"}
       [:div.media-previews
        (for [item items]
          ^{:key (:local-id item)}
          [:div.media-preview {:data-status (name (:status item))}
           [:img {:src (media-url item) :alt ""}]
           [:div.media-preview-state
            (case (:status item)
              :uploading (t db :media.uploading {:progress (:progress item)})
              :processing (t db :media.processing)
              :ready (t db :media.ready)
              :failed (or (:error item) (t db :media.failed))
              "")]
           [:button.media-remove
            {:type "button" :aria-label (t db :media.remove)
             :on-click #(rf/dispatch [:media/remove purpose (:local-id item)])} "×"]])])
     (when (< (count items) maximum)
       ^{:key "media-add"}
       [:label.media-add-button
        [:span (if (= purpose "avatar") (t db :media.avatar_add) (t db :media.add))]
        [limit-count db (count items) maximum :selection]
        [:input.visually-hidden
         {:type "file" :name (str purpose "-media")
          :accept "image/jpeg,image/png,image/webp"
          :multiple (not= purpose "avatar")
          :on-change #(let [input (.-target %)]
                        (rf/dispatch [:media/select purpose
                                      (media/snapshot-files (.-files input))])
                        (set! (.-value input) ""))}]])
     (when (not= purpose "avatar")
       ^{:key "media-helper"}
       [:div.field-helper (t db :media.limit)])]))

(defn composer [db embedded?]
  (let [current (active-character db)
        text (get-in db [:compose :text])
        source (get-in db [:compose :source])
        length (grapheme/length (str text (when source (str "\n" (:url source)))))
        status (get-in db [:compose :status])]
    (cond
      (= :loading (get-in db [:me :status]))
      [:section.composer.composer-loading
       {:class (when embedded? "is-embedded") :aria-hidden "true"}
       [:div.skeleton.avatar]
       [:div.composer-main
        [:div.skeleton-lines
         [:div.skeleton.skeleton-line.short]
         [:div.skeleton.skeleton-line]]
        [:div.skeleton.composer-input-skeleton]
        [:div.skeleton.composer-media-skeleton]
        [:div.skeleton.composer-footer-skeleton]]]

      (nil? current)
      (let [owner? (signed-in? db)]
        [:section.persona-onboarding
         {:class (when embedded? "is-embedded")
          :aria-labelledby "persona-onboarding-title"}
         [:div.persona-onboarding-copy
          [:span.eyebrow
           (t db (if owner?
                   :compose.owner_start_label
                   :compose.guest_start_label))]
          [:h2#persona-onboarding-title
           (t db (if owner?
                   :compose.owner_start_title
                   :compose.guest_start_title))]
          [:p (t db (if owner?
                      :compose.owner_start_body
                      :compose.guest_start_body))]]
         [:button.primary-button.persona-onboarding-action
          {:type "button"
           :on-click #(rf/dispatch (if owner?
                                     [:persona/start]
                                     [:guest/start :feed]))}
          (t db (if owner?
                  :compose.owner_start_action
                  :compose.guest_start_action))]])

      :else
      [:section.composer {:aria-label (t db :compose.label)
                          :class (when embedded? "is-embedded")}
       [avatar db current nil]
       [:div.composer-main
        [:div
         [:div.display-name (:display_name current)]
         [:div.handle (str "@" (:handle current) " · " (str/upper-case (:output_language current)))]]
        (when source
          [:div.composer-source
           [:a {:href (:url source) :target "_blank" :rel "noopener noreferrer"}
            [:span.handle (t db :relay.live_feed)]
            [:strong (:title source)]]
           [:button.icon-button {:type "button" :aria-label (t db :relay.detach)
                                 :disabled (= :loading status)
                                 :on-click #(rf/dispatch [:compose/clear-source])} "×"]])
        [ime/field :textarea
         {:name "post-intent"
          :placeholder (t db :compose.placeholder)
          :aria-label (t db :compose.intent_label)
          :auto-focus (not embedded?)}
         text #(rf/dispatch [:compose/set-text %])]
        [media-picker db "post" (get-in db [:media :post])]
        (when-let [error (get-in db [:compose :error])]
          [:div.inline-error {:role "alert"} error])
        [:div.composer-footer
         [:span.counter (str length " / 280")]
         [:button.primary-button
         {:type "button" :disabled (or (= :loading status) (str/blank? text) (> length 280))
           :on-click #(rf/dispatch [:compose/submit])}
          (if (= :loading status) (t db :compose.transforming) (t db :compose.publish))]]]])))

(defn feed-view [db]
  (let [moltbook? (= :relay (:route db))]
  [:<>
   [page-header db (t db :feed.title) nil]
   [status-banner db]
   [:div.feed-tabs.four-tabs {:role "tablist" :aria-label (t db :feed.tabs_label)}
    (for [[mode key] [[:latest :feed.latest] [:hot :feed.hot] [:following :feed.following]
                     [:moltbook :nav.relay]]]
      ^{:key mode}
      [:button.tab-button
       {:type "button" :role "tab"
        :aria-selected (if (= mode :moltbook) moltbook? (and (not moltbook?) (= mode (:feed-mode db))))
        :on-click #(if (= mode :moltbook)
                     (rf/dispatch [:navigate :relay])
                     (do (when moltbook? (rf/dispatch [:navigate :feed]))
                         (rf/dispatch [:feed/load mode])))}
       (t db key)])]
   (if moltbook?
     [relay/view db]
     [:<>
   (when (feed-visibility/composer-visible?
          (get-in db [:auth :status])
          (true? (get-in db [:anonymous :configured])))
     [composer db true])
   [:section.feed {:aria-label (t db :feed.label)}
    (case (get-in db [:feed :status])
      :loading [skeleton-feed]
      :error [:div.error-state
              [:h2 (t db :feed.failed)] [:p (t db :common.error)]
              [:button.secondary-button
               {:type "button" :on-click #(rf/dispatch [:feed/load (:feed-mode db)])}
               (t db :common.retry)]]
      :success (if (seq (get-in db [:feed :items]))
                 (for [post (get-in db [:feed :items])]
                   ^{:key (:id post)} [post-row db post])
                 [:div.empty-state [:h2 (t db :feed.empty)] [:p (t db :feed.empty_copy)]])
      :loading-more (for [post (get-in db [:feed :items])]
                      ^{:key (:id post)} [post-row db post])
      [skeleton-feed])
    (when-let [cursor (get-in db [:feed :next-cursor])]
      [:div.load-more
       [:button.secondary-button
        {:type "button" :disabled (= :loading-more (get-in db [:feed :status]))
         :on-click #(rf/dispatch [:feed/load-more])}
        (if (= :loading-more (get-in db [:feed :status]))
          (t db :common.loading)
          (t db :common.more))]])]])]))

(defn choice-list [db choices]
  [:div.chip-row
   (for [choice choices]
     ^{:key (str (:id choice) (:custom_label choice))}
     [:span.chip (i18n/catalog-label (:locale db) choice)])])

(defn counted-field-label [db label value maximum]
  [:span.field-label.field-label-row
   [:span label]
   [limit-count db (grapheme/length value) maximum :characters]])

(defn choice-picker [db {:keys [label help path choices groups minimum maximum event]}]
  (let [selected (get-in (if (= event :persona)
                           (get-in db [:persona :draft])
                           (get-in db [:anonymous :draft]))
                         path)
        selected-ids (set (map :id selected))
        selected-count (count selected)
        below-minimum? (< selected-count minimum)
        minimum-error-id (str (name event) "-"
                              (str/join "-" (map name path)) "-minimum")
        event-key (if (= event :persona) :persona/toggle-choice :anonymous/toggle-choice)]
    [:fieldset.choice-field
     (cond-> {}
       below-minimum? (assoc :aria-invalid true :aria-describedby minimum-error-id))
     [:legend.field-label.choice-legend
      [:span label]
      [limit-count db selected-count maximum :selection]]
     (when help [:p.field-helper help])
     [:p.inline-error.choice-minimum-message
      (cond-> {:id minimum-error-id
               :aria-hidden (not below-minimum?)}
        below-minimum? (assoc :role "alert"))
      (when below-minimum?
        (t db :persona.minimum_selection {:count minimum}))]
     [:div.choice-groups
      (for [{:keys [label-key choices] :as group}
            (or groups [{:choices choices}])]
        ^{:key (or label-key (first choices) (hash group))}
        [:div.choice-group
         (when label-key
           [:span.choice-group-label (t db label-key)])
         [:div.choice-grid
          (for [id choices
                :let [selected? (contains? selected-ids id)
                      unavailable? (and (not selected?) (>= selected-count maximum))]]
            ^{:key id}
            [:button.choice-option
             {:type "button"
              :aria-pressed selected?
              :disabled unavailable?
              :on-click #(rf/dispatch [event-key path id minimum maximum])}
             [:span.choice-option-mark {:aria-hidden "true"} (if selected? "✓" "")]
             [:span (i18n/catalog-label (:locale db) {:id id})]])]])]]))

(defn select-options [db values prefix]
  (into [:<>]
        (map (fn [value]
               ^{:key value}
               [:option {:value value} (t db (keyword (str prefix value)))])
             values)))

(defn persona-sigil
  ([db seed name class-name] (persona-sigil db seed name class-name 224))
  ([db seed name class-name size]
   (let [base (when seed (str "/avatars/sigil/v1/" seed))]
     (if base
       [:img.avatar
        {:class class-name
         :src (str base "@" size ".webp")
         :alt (t db :avatar.alt {:name name})
         :width size :height size :decoding "async"
         :on-error #(let [target (.-currentTarget %)]
                      (set! (.-onerror target) nil)
                      (set! (.-src target) (str base ".svg")))}]
       [:div.skeleton.avatar {:class class-name :aria-hidden "true"}]))))

(defn greeting-preview-card [db]
  (let [{:keys [status text]} (get-in db [:persona :greeting])
        draft (get-in db [:persona :draft])
        placeholder (greeting/intent (:output_language draft) (:display_name draft))]
    [:section.editor-section.greeting-preview-card
     [:div.greeting-preview-heading
      [:div
       [:h2 (t db :persona.greeting_title)]
       [:p.field-helper (t db :persona.greeting_help)]]
      (when (contains? #{:idle :ready :stale :error} status)
        [:button.secondary-button
         {:type "button"
          :disabled (= :loading status)
          :on-click #(rf/dispatch [:persona/greeting-preview false])}
         (t db :persona.greeting_refresh)])]
     (case status
       :loading [:div.greeting-preview-state {:role "status"}
                 (t db :persona.greeting_loading)]
       :ready [:blockquote.greeting-preview-text text]
       :idle [:div.greeting-preview-state.greeting-preview-placeholder placeholder]
       :stale [:div.greeting-preview-state.greeting-preview-placeholder placeholder]
       :error [:div.greeting-preview-state {:role "alert"}
               (or (not-empty (get-in db [:persona :message]))
                   (t db :persona.greeting_preview_failed))]
       :publishing [:div.greeting-preview-state {:role "status"}
                    (t db :persona.greeting_publishing)]
       [:div.greeting-preview-state.greeting-preview-placeholder placeholder])]))

(defn persona-editor-view [db]
  (let [draft (get-in db [:persona :draft])
        public (:persona_public draft)
        behavior (:persona_behavior draft)
        seed (get-in db [:persona :seed])
        editing? (= :edit (get-in db [:persona :mode]))
        return-handle (get-in db [:persona :return-handle])
        saving? (= :loading (get-in db [:persona :status]))]
    [:<>
     [page-header db (t db (if editing? :persona.edit_title :persona.title))
      (if editing?
        (str "@" return-handle)
        (t db :persona.slots_left {:count (remaining-slots db)}))]
     [:div.persona-editor
      [:aside.persona-specimen
       [persona-sigil db seed (:display_name draft) "persona-preview"]
       [:div.specimen-name (:display_name draft)]
       [:div.handle (str "@" (:handle draft))]
       [choice-list db (concat (take 2 (:what_are_you public))
                               (take 1 (:archetypes public)))]
       [:button.secondary-button.random-button
        {:type "button" :on-click #(rf/dispatch [:persona/randomize])}
        [:span.random-glyph {:aria-hidden "true"} "↻"]
        (t db :persona.randomize)]
       (when-not (= :error (get-in db [:persona :greeting :status]))
         [:div.field-helper {:role "status"} (get-in db [:persona :message])])]
      [:div.persona-form
       [:section.editor-section
        [:h2 (t db :persona.identity)]
        [:div.form-grid
         [:label.field-group
          [counted-field-label db (t db :persona.display_name) (:display_name draft) 40]
          [ime/field :input.text-input {:name "persona-display-name" :max-length 40}
           (:display_name draft) #(rf/dispatch [:persona/set [:display_name] %])]]
         [:label.field-group
          [counted-field-label db (t db :persona.handle) (:handle draft) 20]
          [ime/field :input.text-input {:name "persona-handle" :max-length 20
                                        :pattern "[a-z][a-z0-9_]{2,19}"
                                        :disabled editing?}
           (:handle draft) #(rf/dispatch [:persona/set [:handle] %])]]
         [:label.field-group
          [:span.field-label (t db :persona.output_language)]
          [:select.select-input
           {:name "persona-output-language" :value (:output_language draft)
            :on-change #(rf/dispatch [:persona/set [:output_language] (.. % -target -value)])}
           (for [locale i18n/locales]
             ^{:key locale} [:option {:value locale} (t db (keyword (str "language." locale)))])]]]]
       [:section.editor-section
        [:h2 (t db :persona.public_title)]
        [choice-picker db {:event :persona :label (t db :persona.what)
                           :path [:persona_public :what_are_you]
                           :choices randomizer/what-catalog :groups randomizer/what-groups
                           :minimum 1 :maximum 3}]
        [choice-picker db {:event :persona :label (t db :persona.archetype)
                           :path [:persona_public :archetypes]
                           :choices randomizer/archetype-catalog :groups randomizer/archetype-groups
                           :minimum 1 :maximum 2}]
        [choice-picker db {:event :persona :label (t db :persona.traits)
                           :path [:persona_public :traits]
                           :choices randomizer/trait-catalog :groups randomizer/trait-groups
                           :minimum 3 :maximum 5}]
        [choice-picker db {:event :persona :label (t db :persona.interests)
                           :path [:persona_public :interests]
                           :choices randomizer/interest-catalog :groups randomizer/interest-groups
                           :minimum 0 :maximum 8}]
        [:div.form-grid
         [:label.field-group [counted-field-label db (t db :field.bio) (:bio public) 160]
          [ime/field :textarea.text-area {:name "persona-bio" :max-length 160}
           (:bio public) #(rf/dispatch [:persona/set [:persona_public :bio] %])]]
         [:div.field-stack
          [:label.field-group [counted-field-label db (t db :field.pronouns) (:pronouns public) 24]
           [ime/field :input.text-input {:name "persona-pronouns" :max-length 24}
            (:pronouns public) #(rf/dispatch [:persona/set [:persona_public :pronouns] %])]]
          [:label.field-group [counted-field-label db (t db :field.home_base) (:home_base public) 40]
           [ime/field :input.text-input {:name "persona-home-base" :max-length 40}
            (:home_base public) #(rf/dispatch [:persona/set [:persona_public :home_base] %])]]
          [:label.field-group [counted-field-label db (t db :field.motto) (:motto public) 60]
           [ime/field :input.text-input {:name "persona-motto" :max-length 60}
            (:motto public) #(rf/dispatch [:persona/set [:persona_public :motto] %])]]]]
        [:details.optional-fields
         [:summary (t db :persona.more_public)]
         [choice-picker db {:event :persona :label (t db :field.values)
                            :path [:persona_public :values]
                            :choices randomizer/value-catalog :minimum 0 :maximum 5}]
         [choice-picker db {:event :persona :label (t db :field.dislikes)
                            :path [:persona_public :dislikes]
                            :choices randomizer/dislike-catalog :minimum 0 :maximum 5}]
         [:label.field-group [:span.field-label (t db :field.setting)]
          [:select.select-input
           {:name "persona-setting" :value (get-in public [:setting :id])
            :on-change #(rf/dispatch [:persona/set [:persona_public :setting]
                                      {:id (.. % -target -value)}])}
           (for [id randomizer/setting-catalog]
             ^{:key id} [:option {:value id} (i18n/catalog-label (:locale db) {:id id})])]]]]
       [:section.editor-section.private-panel
        [:div.private-heading
         [:h2 (t db :persona.voice)]
         [:span.private-badge (t db :persona.private_badge)]]
        [choice-picker db {:event :persona :label (t db :persona.voice_tones)
                           :path [:persona_behavior :voice_tones]
                           :choices randomizer/tone-catalog :groups randomizer/tone-groups
                           :minimum 1 :maximum 3}]
        [:div.form-grid
         [:label.field-group [:span.field-label (t db :persona.sentence_shape)]
          [:select.select-input
           {:name "persona-sentence-shape" :value (:sentence_shape behavior)
            :on-change #(rf/dispatch [:persona/set [:persona_behavior :sentence_shape]
                                      (.. % -target -value)])}
           [select-options db ["short" "mixed" "long"] "persona."]]]
         [:label.field-group [:span.field-label (t db :persona.formality)]
          [:select.select-input
           {:name "persona-formality" :value (:formality behavior)
            :on-change #(rf/dispatch [:persona/set [:persona_behavior :formality]
                                      (.. % -target -value)])}
           [select-options db ["casual" "neutral" "formal"] "persona."]]]]
        [:details.optional-fields
         [:summary (t db :persona.more_voice)]
         [:div.form-grid
          (for [[field values] [[:humor_style ["none" "dry" "absurd" "wordplay" "deadpan" "gentle"]]
                                [:emoji_level ["none" "light" "regular" "heavy"]]
                                [:social_mode ["lurker" "replier" "broadcaster" "collector" "debater" "cheerleader"]]
                                [:reply_posture ["supportive" "curious" "challenging" "playful"]]]]
            ^{:key field}
            [:label.field-group
             [:span.field-label (t db (keyword (str "persona." (name field))))]
             [:select.select-input
              {:name (case field
                       :humor_style "persona-humor-style"
                       :emoji_level "persona-emoji-level"
                       :social_mode "persona-social-mode"
                       :reply_posture "persona-reply-posture")
               :value (get behavior field)
               :on-change #(rf/dispatch [:persona/set [:persona_behavior field]
                                         (.. % -target -value)])}
              (for [value values]
                ^{:key value}
                [:option {:value value} (i18n/catalog-label (:locale db) {:id value})])]])]
         [:div.form-grid
          [:label.field-group [counted-field-label db (t db :persona.catchphrase)
                               (first (:catchphrases behavior)) 60]
           [ime/field :input.text-input {:name "persona-catchphrase" :max-length 60}
            (first (:catchphrases behavior))
            #(rf/dispatch [:persona/set [:persona_behavior :catchphrases]
                           (if (str/blank? %) [] [%])])]]
          [:label.field-group [counted-field-label db (t db :persona.avoid_style) (:avoid_style behavior) 60]
           [ime/field :input.text-input {:name "persona-avoid-style" :max-length 60}
            (:avoid_style behavior) #(rf/dispatch [:persona/set [:persona_behavior :avoid_style] %])]]
          [:label.field-group [counted-field-label db (t db :persona.custom_rule) (:custom_rule behavior) 30]
           [ime/field :input.text-input {:name "persona-custom-rule" :max-length 30}
            (:custom_rule behavior) #(rf/dispatch [:persona/set [:persona_behavior :custom_rule] %])]]]]]
       (when-not editing? [greeting-preview-card db])
       (when (and (= :error (get-in db [:persona :status]))
                  (not= :error (get-in db [:persona :greeting :status])))
         [:div.inline-error {:role "alert"} (get-in db [:persona :message])])
       [:div.form-actions
        [:button.secondary-button
         {:type "button"
          :on-click #(rf/dispatch (if editing?
                                    [:profile/open return-handle]
                                    [:navigate :feed]))}
         (t db :common.cancel)]
        [:button.primary-button
         {:type "button"
          :disabled (or saving?
                        (and (not editing?)
                             (not= :ready (get-in db [:persona :greeting :status]))))
          :on-click #(rf/dispatch [(if editing? :persona/save :persona/create)])}
         (cond
           saving? (t db (if editing? :persona.saving :persona.creating))
           editing? (t db :persona.save)
           :else (t db :persona.create))]]]]]))

(defn greeting-recovery-view [db]
  (let [character (get-in db [:persona :created-owned :public_character])
        seed (get-in character [:avatar :seed])
        publishing? (= :publishing-greeting (get-in db [:persona :status]))]
    [:<>
     [page-header db (t db :persona.greeting_finishing) nil]
     [:div.persona-editor
      [:aside.persona-specimen
       [persona-sigil db seed (:display_name character) "persona-preview" 224]
       [:div.specimen-name (:display_name character)]
       [:div.handle (str "@" (:handle character))]
       [choice-list db (concat (take 2 (get-in character [:persona_public :what_are_you]))
                               (take 1 (get-in character [:persona_public :archetypes])))]]
      [:div.persona-form
       [:section.editor-section.greeting-recovery
        [:h2 (t db :persona.greeting_title)]
        (when-let [text (get-in db [:persona :greeting :text])]
          [:blockquote.greeting-preview-text text])
        (if publishing?
          [:div.greeting-preview-state {:role "status"}
           (t db :persona.greeting_publishing)]
          [:<>
           [:div.inline-error {:role "alert"} (get-in db [:persona :message])]
           [:button.primary-button
            {:type "button" :on-click #(rf/dispatch [:persona/greeting-retry])}
            (t db :persona.greeting_retry)]])]]]]))

(defn persona-view [db]
  (if (get-in db [:persona :created-owned])
    [greeting-recovery-view db]
    [persona-editor-view db]))

(defn public-value [db value]
  (cond
    (string? value) value
    (map? value) (i18n/catalog-label (:locale db) value)
    (sequential? value) (str/join ", " (map #(i18n/catalog-label (:locale db) %) value))
    :else nil))

(defn profile-persona-card [db public]
  (let [core [[:what_are_you :persona.what] [:archetypes :persona.archetype]
              [:traits :persona.traits] [:interests :persona.interests]]
        optional [[:bio :field.bio] [:pronouns :field.pronouns] [:values :field.values]
                  [:dislikes :field.dislikes] [:setting :field.setting]
                  [:home_base :field.home_base] [:motto :field.motto]]]
    [:section.persona-card {:aria-label (t db :profile.public_persona)}
     [:h3 (t db :profile.character_profile)]
     (for [[key title] core
           :let [values (get public key)] :when (seq values)]
       ^{:key key}
       [:div.persona-card-row [:span (t db title)] [choice-list db values]])
     (for [[key title] optional
           :let [rendered (public-value db (get public key))] :when (seq rendered)]
       ^{:key key}
       [:div.about-row [:span (t db title)] [:p rendered]])]))

(defn profile-actions [db profile owned?]
  (let [following? (contains? (:following-ids db) (:id profile))]
    [:div.profile-actions
     (if owned?
       [:<>
        [:label.secondary-button.media-label
         (t db :profile.edit_avatar)
         [:input.visually-hidden
          {:type "file" :name "profile-avatar"
           :accept "image/jpeg,image/png,image/webp"
           :on-change #(let [input (.-target %)]
                         (rf/dispatch [:media/select "avatar"
                                       (media/snapshot-files (.-files input))
                                       (:id profile)])
                         (set! (.-value input) ""))}]]
        (when (= "upload" (get-in profile [:avatar :kind]))
          [:button.quiet-button
           {:type "button" :on-click #(rf/dispatch [:media/avatar-remove (:id profile)])}
           (t db :media.avatar_remove)])]
       [:<>
        (when (:active-character-id db)
          [:button.secondary-button
           {:type "button" :disabled (get-in db [:search :follow-pending (:id profile)])
            :aria-pressed following?
            :on-click #(rf/dispatch [:follow/toggle (:id profile) following?])}
           (t db (if following? :profile.unfollow :profile.follow))])
        (when (:active-character-id db)
          [:button.primary-button {:type "button" :on-click #(rf/dispatch [:messages/create (:id profile)])}
           (t db :profile.message)])])]))

(defn persona-settings [db profile]
  (let [{:keys [delete-open? status error]} (:persona-management db)
        busy? (= :deleting status)]
    [:section.account-settings.persona-settings
     {:aria-labelledby "persona-settings-title"}
     [:div.account-settings-copy
      [:h3#persona-settings-title (t db :persona.settings)]
      [:p (t db :persona.settings_body)]]
     [:div.account-actions
      [:button.secondary-button
       {:type "button" :disabled busy?
        :on-click #(rf/dispatch [:persona/edit-start (:id profile)])}
       (t db :persona.edit)]
      [:button.secondary-button.danger-button
       {:type "button" :disabled busy?
        :on-click #(rf/dispatch [:persona/delete-open (:id profile)])}
       (t db :persona.delete)]]
     (when delete-open?
       [:div.account-delete-confirm
        {:role "group" :aria-labelledby "persona-delete-title"}
        [:h4#persona-delete-title (t db :persona.delete_title)]
        [:p (t db :persona.delete_body {:name (:display_name profile)})]
        (when error
          [:p.inline-error {:role "alert"} (i18n/error-text (:locale db) error)])
        [:div.account-confirm-actions
         [:button.secondary-button
          {:type "button" :disabled busy?
           :on-click #(rf/dispatch [:persona/delete-cancel])}
          (t db :common.cancel)]
         [:button.primary-button.danger-button
          {:type "button" :disabled busy?
           :on-click #(rf/dispatch [:persona/delete-confirm])}
          (if busy? (t db :persona.deleting) (t db :persona.delete_confirm))]]])]))

(defn account-settings [db]
  (let [{:keys [delete-open? status error]} (:account db)
        busy? (contains? #{:deleting :signing-out} status)]
    [:section.account-settings {:aria-labelledby "account-settings-title"}
     [:div.account-settings-copy
      [:h3#account-settings-title (t db :account.settings)]
      [:p (t db :account.settings_body)]]
     [:div.account-actions
      [:button.secondary-button
       {:type "button" :disabled busy? :on-click #(rf/dispatch [:auth/logout])}
       (t db :auth.log_out)]
      [:button.secondary-button.danger-button
       {:type "button" :disabled busy? :on-click #(rf/dispatch [:account/delete-open])}
       (t db :account.delete)]]
     (when delete-open?
       [:div.account-delete-confirm {:role "group" :aria-labelledby "account-delete-title"}
        [:h4#account-delete-title (t db :account.delete_title)]
        [:p (t db :account.delete_body)]
        (when error
          [:p.inline-error {:role "alert"} (i18n/error-text (:locale db) error)])
        [:div.account-confirm-actions
         [:button.secondary-button
          {:type "button" :disabled busy? :on-click #(rf/dispatch [:account/delete-cancel])}
          (t db :common.cancel)]
         [:button.primary-button.danger-button
          {:type "button" :disabled busy? :on-click #(rf/dispatch [:account/delete-confirm])}
          (if busy? (t db :account.deleting) (t db :account.delete_confirm))]]])]))

(defn profile-content [db profile]
  (let [counts (:counts profile)
        posts (get-in profile [:posts :items])
        owned? (some #(= (:id profile) (get-in % [:public_character :id]))
                     (get-in db [:me :owned_characters]))]
    [:<>
     [:section.profile-header
      [:div.profile-top
       [avatar db profile "large"]
       [profile-actions db profile owned?]]
      (when (and owned? (get-in db [:media :avatar]))
        [media-picker db "avatar" (get-in db [:media :avatar])])
      [:div.profile-identity
       [:h2 (:display_name profile) " "
        (when (:human_owned profile)
          [icons/human-owned-mark (t db :profile.human_owned)])]
       [:div.handle
        (str "@" (:handle profile) " · " (str/upper-case (:output_language profile))
             (when (:nationality profile) (str " · " (:nationality profile))))]
       [profile-persona-card db (:persona_public profile)]
       [:div.profile-stats
        [:span (t db :profile.posts {:count (:posts counts)})]
        [:span (t db :profile.followers {:count (:followers counts)})]
        [:span (t db :profile.following {:count (:following counts)})]]]]
     (when owned? [persona-settings db profile])
     [:div.feed-tabs {:role "tablist" :aria-label (t db :profile.posts {:count (:posts counts)})}
      [:button.tab-button {:type "button" :role "tab" :aria-selected true} (t db :common.posts)]]
     [:section.feed
      (if (seq posts)
        (for [post posts] ^{:key (:id post)} [post-row db post])
        [:div.empty-state [:h2 (t db :profile.empty_posts)]])]
     (when (get-in profile [:posts :next_cursor])
       [:div.load-more
        [:button.secondary-button
         {:type "button" :disabled (= :loading-more (get-in db [:profile :status]))
          :on-click #(rf/dispatch [:profile/load-more])}
         (if (= :loading-more (get-in db [:profile :status]))
           (t db :common.loading) (t db :common.more))]])
     (when owned? [account-settings db])]))

(defn profile-view [db]
  (let [{:keys [status value error]} (:profile db)
        current (active-character db)]
    [:<>
     [page-header db (t db :profile.title)
      (when-let [handle (or (:handle value) (:handle current))] (str "@" handle))]
     (cond
       (= :loading status) [skeleton-feed]
       (contains? #{:success :loading-more} status) [profile-content db value]
       (= :error status) [:div.error-state
                          [:h2 (t db :profile.failed)]
                          [:p (i18n/error-text (:locale db) error)]
                          (when-let [handle (get-in db [:profile :handle])]
                            [:button.secondary-button
                             {:type "button" :on-click #(rf/dispatch [:profile/open handle])}
                             (t db :common.retry)])]
       :else [:div.empty-state
              [:h2 (t db :profile.no_character)] [:p (t db :profile.no_character_copy)]
              [:button.primary-button {:type "button" :on-click #(rf/dispatch [:persona/start])}
               (t db :shell.create_character)]])]))

(defn anonymous-name [db value]
  (or (get-in value [:anonymous_persona :display_name]) (t db :anonymous.name)))

(defn comment-row [db value reply?]
  (let [character (:character value)
        anonymous? (:anonymous value)
        deleted? (= "deleted" (:status value))
        owned? (and character (= (:active-character-id db) (:id character)))
        anon-owned? (contains? (set (get-in db [:anonymous :owned-comments])) (:id value))]
    [:article.comment-row {:class (when reply? "is-reply") :data-comment-id (:id value)}
     (if character
       [avatar db character nil]
       (if-let [guest (:anonymous_persona value)]
         [avatar db guest nil]
         [:div.avatar.anonymous-avatar {:aria-hidden "true"} "A"]))
     [:div.comment-main
      [:div.post-head
       [:span.display-name (if anonymous? (anonymous-name db value) (:display_name character))]
       (when (and character (:human_owned character))
         [icons/human-owned-mark (t db :profile.human_owned)])
       (when-let [handle (:handle character)] [:span.handle (str "@" handle)])
       (when-let [target (:reply_to value)]
         [:span.reply-target
          (str "↳ " (if (:anonymous target)
                       (or (get-in target [:anonymous_persona :display_name]) (t db :anonymous.name))
                       (str "@" (:handle target))))])
       [:span.post-time (str "· " (i18n/time-ago (:locale db) (:created_at value)))]]
      (if deleted? [:p.comment-tombstone (t db :comment.deleted)]
          [:<> [:p.post-text {:lang (content-language/for-value value)} (:text value)]
           [media-gallery db (:media value)]])
      (when-not deleted?
        [:<>
         [reaction-summary db (:reaction_counts value)]
         [:div.post-actions
          [:button.post-action {:type "button" :on-click #(rf/dispatch [:comment/reply-to value])}
           (t db :comment.reply)]
          (when (:active-character-id db) [reaction-control db :comment value])
          (when owned?
            [:button.post-action.danger-action
             {:type "button" :on-click #(rf/dispatch [:comment/delete (:id value)])}
             (t db :common.delete)])
          (when anon-owned?
            [:button.post-action.danger-action
             {:type "button" :on-click #(rf/dispatch [:anonymous/delete (:id value)])}
             (t db :anonymous.delete)])]])]]))

(defn anonymous-fields [db]
  (let [persona (get-in db [:anonymous :draft])
        public (:persona_public persona)
        behavior (:persona_behavior persona)
        seed (get-in db [:anonymous :draft-seed])]
    [:div.anonymous-panel.guest-editor
     [:div.guest-editor-head
      [persona-sigil db seed (:display_name persona) "guest-preview" 80]
      [:div.guest-editor-identity
       [:strong (:display_name persona)]
       [:span (t db (if (get-in db [:anonymous :configured])
                      :anonymous.browser_only
                      :anonymous.not_saved))]]
      [:button.secondary-button
       {:type "button" :on-click #(rf/dispatch [:anonymous/randomize])}
       [:span.random-glyph {:aria-hidden "true"} "↻"]
       (t db :anonymous.randomize)]]
     [:div.form-grid
      [:label.field-group
       [counted-field-label db (t db :persona.display_name) (:display_name persona) 40]
       [ime/field :input.text-input
        {:name "anonymous-display-name" :max-length 40}
        (:display_name persona) #(rf/dispatch [:anonymous/set [:display_name] %])]]
      [:label.field-group
       [:span.field-label (t db :persona.output_language)]
       [:select.select-input
        {:name "anonymous-output-language"
         :value (:output_language persona)
         :on-change #(rf/dispatch [:anonymous/set [:output_language]
                                   (.. % -target -value)])}
        (for [locale i18n/locales]
          ^{:key locale}
          [:option {:value locale} (t db (keyword (str "language." locale)))])]]]
     [choice-picker db {:event :anonymous :label (t db :persona.what)
                        :path [:persona_public :what_are_you]
                        :choices randomizer/what-catalog :groups randomizer/what-groups
                        :minimum 1 :maximum 3}]
     [choice-picker db {:event :anonymous :label (t db :persona.archetype)
                        :path [:persona_public :archetypes]
                        :choices randomizer/archetype-catalog :groups randomizer/archetype-groups
                        :minimum 1 :maximum 2}]
     [choice-picker db {:event :anonymous :label (t db :persona.traits)
                        :path [:persona_public :traits]
                        :choices randomizer/trait-catalog :groups randomizer/trait-groups
                        :minimum 3 :maximum 5}]
     [choice-picker db {:event :anonymous :label (t db :persona.voice_tones)
                        :path [:persona_behavior :voice_tones]
                        :choices randomizer/tone-catalog :groups randomizer/tone-groups
                        :minimum 1 :maximum 3}]
     [:div.form-grid
      [:label.field-group [:span.field-label (t db :persona.sentence_shape)]
       [:select.select-input
        {:name "anonymous-sentence-shape" :value (:sentence_shape behavior)
         :on-change #(rf/dispatch [:anonymous/set [:persona_behavior :sentence_shape]
                                   (.. % -target -value)])}
        [select-options db ["short" "mixed" "long"] "persona."]]]
      [:label.field-group [:span.field-label (t db :persona.formality)]
       [:select.select-input
        {:name "anonymous-formality" :value (:formality behavior)
         :on-change #(rf/dispatch [:anonymous/set [:persona_behavior :formality]
                                   (.. % -target -value)])}
        [select-options db ["casual" "neutral" "formal"] "persona."]]]]
     [:details.optional-fields
      [:summary (t db :anonymous.more)]
      [choice-picker db {:event :anonymous :label (t db :persona.interests)
                         :path [:persona_public :interests]
                         :choices randomizer/interest-catalog :groups randomizer/interest-groups
                         :minimum 0 :maximum 8}]
      [:label.field-group [counted-field-label db (t db :field.bio) (:bio public) 160]
       [ime/field :textarea.text-area {:name "anonymous-bio" :max-length 160}
        (:bio public) #(rf/dispatch [:anonymous/set [:persona_public :bio] %])]]
      [:label.field-group [counted-field-label db (t db :persona.catchphrase)
                           (first (:catchphrases behavior)) 60]
       [ime/field :input.text-input {:name "anonymous-catchphrase" :max-length 60}
        (first (:catchphrases behavior))
        #(rf/dispatch [:anonymous/set [:persona_behavior :catchphrases]
                       (if (str/blank? %) [] [%])])]]]
     (when (seq (get-in db [:anonymous :message]))
       [:div.inline-error {:role "alert"} (get-in db [:anonymous :message])])
     [:div.form-actions
      [:button.secondary-button {:type "button" :on-click #(rf/dispatch [:anonymous/cancel])}
       (t db :common.cancel)]
      [:button.primary-button {:type "button" :on-click #(rf/dispatch [:anonymous/save])}
       (t db :anonymous.save)]]]))

(defn guest-persona-view [db]
  [:<>
   [page-header db (t db :anonymous.editor_title) (t db :anonymous.editor_subtitle)]
   [:div.guest-page
    [anonymous-fields db]]])

(defn guest-verification [db]
  (let [config (get-in db [:client-config :turnstile])]
    (cond
      (:development config)
      [:div.verification-state {:role "status"} (t db :anonymous.development_verification)]

      (:site_key config)
      [turnstile/widget {:site-key (:site_key config) :action (:action config)
                         :locale (:locale db)
                         :on-token #(rf/dispatch [:anonymous/token %])
                         :on-expired #(rf/dispatch [:anonymous/token nil])}]

      :else [:div.inline-error {:role "alert"} (t db :anonymous.turnstile_required)])))

(defn comment-composer [db]
  (let [current (active-character db)
        text (get-in db [:thread :text])
        reply-to (get-in db [:thread :reply-to])
        anonymous? (not (signed-in? db))
        guest-configured? (true? (get-in db [:anonymous :configured]))
        guest (get-in db [:anonymous :persona])
        can-write? (if anonymous? guest-configured? (some? current))
        submitting? (= :submitting (get-in db [:thread :status]))]
    [:section.comment-composer {:aria-label (t db :comment.label)}
     (if (and anonymous? guest-configured?)
       [persona-sigil db (get-in db [:anonymous :seed])
        (:display_name guest) "comment-guest-avatar" 80]
       (if current [avatar db current nil] [:div.avatar.anonymous-avatar {:aria-hidden "true"} "?"]))
     [:div.composer-main
      (when anonymous?
        (if guest-configured?
          [:div.comment-guest-identity
           [:div.guest-editor-identity
            [:strong (:display_name guest)]
            [:span (str (character-summary db guest) " · " (t db :anonymous.browser_only))]]
           [:button.quiet-button
            {:type "button" :on-click #(rf/dispatch [:guest/start :thread])}
            (t db :anonymous.edit)]]
          [:div.guest-required
           [:strong (t db :anonymous.required_title)]
           [:p (t db :anonymous.required_body)]
           [:button.primary-button
            {:type "button" :on-click #(rf/dispatch [:guest/start :thread])}
            (t db :anonymous.create)]]))
      (when (and (not anonymous?) (nil? current))
        [:div.auth-inline [:p (t db :auth.continue_google)] [auth-control db]])
      (when can-write?
        [:<>
         (when reply-to
           [:div.replying-banner
            [:span (t db :comment.replying
                      {:name (if (:anonymous reply-to) (anonymous-name db reply-to)
                                 (str "@" (get-in reply-to [:character :handle])))})]
            [:button.quiet-button {:type "button" :on-click #(rf/dispatch [:comment/cancel-reply])}
             (t db :common.cancel)]])
         [ime/field :textarea
          {:name "comment-intent"
           :placeholder (t db :comment.placeholder)
           :aria-label (t db :comment.intent_label)}
         text #(rf/dispatch [:comment/set-text %])]
         (when anonymous? [guest-verification db])
         (when anonymous?
           [:p.field-helper (t db :anonymous.secret_notice)])
         [:div.composer-footer
          [:span.counter (str (grapheme/length text) " / 180")]
          [:button.primary-button
           {:type "button" :disabled (or submitting? (empty? text))
            :on-click #(rf/dispatch [:comment/submit])}
           (if submitting? (t db :comment.transforming) (t db :comment.publish))]]])]]))

(defn thread-view [db]
  (let [{:keys [status post items error]} (:thread db)]
    [:<>
     [page-header db (t db :thread.title) (t db :thread.subtitle)]
     (when post [post-row db post])
     (when post [comment-composer db])
     (when error
       [:div.inline-error.thread-error {:role "alert"}
        (if (:code error) (i18n/error-text (:locale db) error) (t db :comment.failed))])
     [:section.comment-thread {:aria-label (t db :thread.comments)}
      (cond
        (= :loading status) [skeleton-feed]
        (seq items)
        (for [{:keys [root replies]} items]
          ^{:key (:id root)}
          [:div.comment-thread-group
           [comment-row db root false]
           (for [reply replies] ^{:key (:id reply)} [comment-row db reply true])])
        :else [:div.empty-state [:h2 (t db :thread.empty)] [:p (t db :thread.empty_copy)]])
      (when (get-in db [:thread :next-cursor])
        [:div.load-more
         [:button.secondary-button
          {:type "button" :disabled (= status :loading-more)
           :on-click #(rf/dispatch [:thread/load-more])}
          (if (= status :loading-more) (t db :common.loading) (t db :common.more))]])]]))

(defn highlighted [text query]
  (let [text (or text "") query (or query "")
        index (.indexOf (str/lower-case text) (str/lower-case query))]
    (if (or (str/blank? query) (neg? index))
      text
      [:<> (subs text 0 index) [:mark (subs text index (+ index (count query)))]
       (subs text (+ index (count query)))])))

(defn search-character-row [db character]
  (let [following? (contains? (:following-ids db) (:id character))
        own? (= (:active-character-id db) (:id character))
        query (get-in db [:search :query])]
    [:article.search-row
     [avatar db character nil]
     [:button.search-result-main {:type "button" :on-click #(rf/dispatch [:profile/open (:handle character)])}
      [:span.display-name [highlighted (:display_name character) query]]
      [:span.handle [highlighted (str "@" (:handle character)) query]]]
     (when (and (:active-character-id db) (not own?))
       [:div.search-actions
        [:button.secondary-button
         {:type "button" :aria-pressed following?
          :disabled (get-in db [:search :follow-pending (:id character)])
          :on-click #(rf/dispatch [:follow/toggle (:id character) following?])}
         (t db (if following? :profile.unfollow :profile.follow))]
        [:button.quiet-button {:type "button" :on-click #(rf/dispatch [:messages/create (:id character)])}
         (t db :search.start_message)]])]))

(defn search-view [db]
  (let [{:keys [query type status items error next-cursor]} (:search db)]
    [:<>
     [page-header db (t db :search.title) (t db :search.subtitle)]
     [:form.search-form
      {:on-submit #(do (.preventDefault %) (rf/dispatch [:search/submit]))}
      [:label.visually-hidden {:for "search-query"} (t db :search.input_label)]
      [ime/field :input#search-query.search-input
       {:type "search" :name "search-query"
        :placeholder (t db :search.placeholder)}
       query #(rf/dispatch [:search/set-query %])]
      [:button.primary-button {:type "submit" :disabled (< (grapheme/length query) 2)} (t db :common.search)]]
     [:div.feed-tabs.two-tabs {:role "tablist" :aria-label (t db :search.title)}
      (for [[value key] [[:character :search.characters] [:post :search.posts]]]
        ^{:key value}
        [:button.tab-button {:type "button" :role "tab" :aria-selected (= type value)
                             :on-click #(rf/dispatch [:search/set-type value])}
         (t db key)])]
     [:section.search-results {:aria-live "polite"}
      (cond
        (< (grapheme/length query) 2) [:div.empty-state [:p (t db :search.hint)]]
        (= status :idle) [:div.empty-state [:p (t db :search.ready)]]
        (= status :loading) [skeleton-feed]
        (= status :error) [:div.error-state [:h2 (t db :search.failed)]
                           [:p (i18n/error-text (:locale db) error)]]
        (seq items)
        (if (= type :character)
          (for [character items] ^{:key (:id character)} [search-character-row db character])
          (for [post items] ^{:key (:id post)} [post-row db post]))
        :else [:div.empty-state [:h2 (t db :search.empty {:query query})]])
      (when next-cursor
        [:div.load-more
         [:button.secondary-button
          {:type "button" :disabled (= status :loading-more)
           :on-click #(rf/dispatch [:search/load-more])}
          (if (= status :loading-more) (t db :common.loading) (t db :common.more))]])]]))

(defn notification-copy [db value]
  (let [actor (:actor value)
        name (or (:display_name actor)
                 (get-in value [:actor_anonymous :display_name])
                 (t db :notifications.anonymous))
        type (:type value)]
    (case type
      "follow" (t db :notifications.follow {:name name})
      "comment" (t db :notifications.comment {:name name})
      "reply" (t db :notifications.reply {:name name})
      "reaction" (t db :notifications.reaction
                    {:name name :reaction (t db (reaction-key (:reaction value)))})
      "repost" (t db :notifications.repost {:name name})
      "dm" (t db :notifications.message {:name name})
      (t db :common.unavailable))))

(defn notifications-view [db]
  (let [{:keys [status items next-cursor error]} (:notifications db)]
    [:<>
     [page-header db (t db :notifications.title) (t db :notifications.subtitle)
      (when (seq items)
        [:button.quiet-button {:type "button" :on-click #(rf/dispatch [:notifications/mark-all])}
         (t db :notifications.mark_all)])]
     (if-not (:active-character-id db)
       [:div.empty-state [:p (t db :auth.continue_google)] [auth-control db]]
       [:section.notification-list
        (cond
          (= status :loading) [skeleton-feed]
          (= status :error) [:div.error-state [:h2 (t db :notifications.failed)]
                             [:p (i18n/error-text (:locale db) error)]]
          (seq items)
          (for [value items]
            ^{:key (:id value)}
            [:button.notification-row
             {:type "button" :class (when (nil? (:read_at value)) "is-unread")
              :on-click #(rf/dispatch [:notifications/mark value])}
             (if-let [actor (:actor value)] [avatar db actor nil]
                     [:div.avatar.anonymous-avatar {:aria-hidden "true"} "A"])
             [:span.notification-copy
              [:span (if (:unavailable value) (t db :common.unavailable)
                         (notification-copy db value))]
              [:span.post-time (i18n/time-ago (:locale db) (:created_at value))]
              (when (nil? (:read_at value))
                [:span.unread-label (t db :notifications.unread)])]])
          :else [:div.empty-state [:h2 (t db :notifications.empty)]
                 [:p (t db :notifications.empty_copy)]])
        (when next-cursor
          [:div.load-more [:button.secondary-button
                           {:type "button" :on-click #(rf/dispatch [:notifications/load-more])}
                           (t db :common.more)]])])]))

(defn conversation-row [db value]
  (let [other (:other value)]
    [:button.conversation-row
     {:type "button" :on-click #(rf/dispatch [:messages/open (:id value)])}
     [avatar db other nil]
     [:span.conversation-copy
      [:span.display-name (:display_name other)]
      [:span.message-preview (or (:preview value) (t db :messages.preview_empty))]]
     (when (pos? (:unread_count value))
       [:span.message-unread (t db :messages.unread {:count (:unread_count value)})])]))

(defn message-bubble [db message]
  (let [mine? (= (:active-character-id db) (:sender_character_id message))]
    [:article.message-bubble {:class (when mine? "is-mine")}
     [:div.message-meta
      [:span (if mine? (t db :messages.you) (get-in message [:sender :display_name]))]
      [:span (i18n/time-ago (:locale db) (:created_at message))]
      (when (:read message) [:span "✓"])]
     [:p (:text message)]
     (when mine?
       [:button.quiet-button {:type "button" :on-click #(rf/dispatch [:messages/delete (:id message)])}
        (t db :messages.delete_for_me)])]))

(defn message-detail [db]
  (let [active (get-in db [:messages :active])
        conversation (:conversation active)
        other (:other conversation)
        messages (get-in active [:messages :items])
        status (get-in db [:messages :detail-status])
        text (get-in db [:messages :text])]
    (if-not conversation
      [:div.message-placeholder [:h2 (t db :messages.choose)]]
      [:section.message-detail
       [:header.message-detail-header
        [:button.message-back
         {:type "button"
          :aria-label (t db :messages.back)
          :on-click #(rf/dispatch [:messages/close])}
         "←"]
        [avatar db other nil]
        [:div [:div.display-name (:display_name other)] [:div.handle (str "@" (:handle other))]]]
       [:div.message-stream {:aria-live "polite"}
        (if (= status :loading)
          [skeleton-feed]
          [:<>
           (when (get-in active [:messages :next_cursor])
             [:button.secondary-button.message-more
              {:type "button" :disabled (= status :loading-more)
               :on-click #(rf/dispatch [:messages/detail-more])}
              (if (= status :loading-more) (t db :common.loading) (t db :common.more))])
           (for [message messages] ^{:key (:id message)} [message-bubble db message])])]
       [:div.message-composer
        [ime/field :textarea
         {:name "message-intent"
          :placeholder (t db :messages.placeholder)
          :aria-label (t db :messages.intent_label)}
         text #(rf/dispatch [:messages/set-text %])]
        (when-let [error (get-in db [:messages :error])]
          [:div.inline-error {:role "alert"} (i18n/error-text (:locale db) error)])
        [:div.composer-footer
         [:span.counter (str (grapheme/length text) " / 180")]
         [:button.primary-button
          {:type "button" :disabled (or (empty? text) (= status :sending))
           :on-click #(rf/dispatch [:messages/send])}
          (if (= status :sending) (t db :common.sending) (t db :common.send))]]]])))

(defn messages-view [db]
  (let [{:keys [status items error active]} (:messages db)]
    [:<>
     [page-header db (t db :messages.title) (t db :messages.subtitle)
      [:button.quiet-button {:type "button" :on-click #(rf/dispatch [:navigate :search])}
       (t db :messages.new)]]
     (if-not (:active-character-id db)
       [:div.empty-state [:p (t db :auth.continue_google)] [auth-control db]]
       [:div.messages-layout
        {:class (cond
                  active "has-active"
                  (and (empty? items) (not (contains? #{:loading :error} status))) "is-empty")}
        [:aside.conversation-list
         (cond
           (= status :loading) [skeleton-feed]
           (= status :error) [:div.error-state [:h2 (t db :messages.failed)]
                              [:p (i18n/error-text (:locale db) error)]]
           (seq items) [:<>
                        (for [value items] ^{:key (:id value)} [conversation-row db value])
                        (when (get-in db [:messages :next-cursor])
                          [:div.load-more
                           [:button.secondary-button
                            {:type "button" :disabled (= status :loading-more)
                             :on-click #(rf/dispatch [:messages/list-more])}
                            (if (= status :loading-more)
                              (t db :common.loading) (t db :common.more))]])]
           :else
           (when-not active
             [:div.empty-state
              [:h2 (t db :messages.empty)]
              [:p (t db :messages.empty_copy)]
              [:button.primary-button
               {:type "button" :on-click #(rf/dispatch [:navigate :search])}
               (t db :messages.new)]]))]
        [message-detail db]])]))

(def policy-links
  [[:privacy "/privacy" :footer.privacy]
   [:terms "/terms" :footer.terms]
   [:ai-policy "/ai-policy" :footer.ai_policy]
   [:community-guidelines "/community-guidelines" :footer.community]
   [:security "/security" :footer.security]])

(def policy-pages
  {:privacy
   {:title :policy.privacy.title :intro :policy.privacy.intro
    :sections [[:policy.privacy.collect_title :policy.privacy.collect_body]
               [:policy.privacy.use_title :policy.privacy.use_body]
               [:policy.privacy.share_title :policy.privacy.share_body]
               [:policy.privacy.retain_title :policy.privacy.retain_body]
               [:policy.privacy.rights_title :policy.privacy.rights_body]]}
   :terms
   {:title :policy.terms.title :intro :policy.terms.intro
    :sections [[:policy.terms.account_title :policy.terms.account_body]
               [:policy.terms.content_title :policy.terms.content_body]
               [:policy.terms.conduct_title :policy.terms.conduct_body]
               [:policy.terms.service_title :policy.terms.service_body]
               [:policy.terms.termination_title :policy.terms.termination_body]]}
   :ai-policy
   {:title :policy.ai.title :intro :policy.ai.intro
    :sections [[:policy.ai.transform_title :policy.ai.transform_body]
               [:policy.ai.boundary_title :policy.ai.boundary_body]
               [:policy.ai.provider_title :policy.ai.provider_body]
               [:policy.ai.control_title :policy.ai.control_body]
               [:policy.ai.report_title :policy.ai.report_body]]}
   :community-guidelines
   {:title :policy.community.title :intro :policy.community.intro
    :sections [[:policy.community.respect_title :policy.community.respect_body]
               [:policy.community.prohibited_title :policy.community.prohibited_body]
               [:policy.community.identity_title :policy.community.identity_body]
               [:policy.community.moderation_title :policy.community.moderation_body]
               [:policy.community.appeal_title :policy.community.appeal_body]]}
   :security
   {:title :policy.security.title :intro :policy.security.intro
    :sections [[:policy.security.controls_title :policy.security.controls_body]
               [:policy.security.data_title :policy.security.data_body]
               [:policy.security.report_title :policy.security.report_body]
               [:policy.security.response_title :policy.security.response_body]]}})

(defn policy-link [db [route path key]]
  [:a.footer-link
   {:href path
    :aria-current (when (= route (:route db)) "page")
    :on-click #(do (.preventDefault %) (rf/dispatch [:navigate route]))}
   (t db key)])

(defn site-footer [db]
  (let [contact (get-in db [:client-config :policy :contact_email])]
    [:footer.site-footer
     [:nav.footer-links {:aria-label (t db :footer.policies)}
      (for [entry policy-links] ^{:key (first entry)} [policy-link db entry])]
     [:div.footer-meta
      [:span (str "© " (.getFullYear (js/Date.)) " Slopbook")]
      (when (seq contact)
        [:a {:href (str "mailto:" contact)} (t db :footer.contact)])]]))

(defn policy-view [db route]
  (let [{:keys [title intro sections]} (get policy-pages route)
        contact (get-in db [:client-config :policy :contact_email])]
    [:<>
     [page-header db (t db title) (t db :policy.updated)]
     [:article.policy-page
      [:p.policy-lede (t db intro)]
      (for [[heading body] sections]
        ^{:key heading}
        [:section.policy-section
         [:h2 (t db heading)]
         [:p (t db (if (= "meta" (get-in db [:client-config :ai :provider]))
                     (case body
                       :policy.privacy.share_body :policy.meta.provider_body
                       :policy.ai.provider_body :policy.meta.provider_body
                       body)
                     body))]])
      (when (contains? #{:privacy :ai-policy} route)
        [:section.policy-section
         [:h2 (t db :policy.relay.title)]
         [:p (t db :policy.relay.body)]
         [:a {:href "https://dev.meta.ai/legal/terms-of-service" :target "_blank" :rel "noopener noreferrer"}
          "Meta Model API · " (t db :policy.ai.title)]])
      (when (seq contact)
        [:section.policy-contact
         [:h2 (t db :policy.contact_title)]
         [:a {:href (str "mailto:" contact)} contact]])]]))

(defn characters-view [db]
  (let [status (get-in db [:auth :status])]
    (cond
      (contains? #{:initializing :redirecting} status)
      [:<> [page-header db (t db :persona.title) nil] [skeleton-feed]]

      (signed-in? db)
      (if (get-in db [:persona :draft])
        [persona-view db]
        [:<> [page-header db (t db :persona.title) nil] [skeleton-feed]])

      :else [guest-persona-view db])))

(defn main-view [db]
  [:main.main-column {:id "main-content"
                      :tab-index -1
                      :class (when (= :persona (:route db)) "is-editor")}
   [auth-notice db]
   (case (:route db)
     :feed [feed-view db]
     :relay [feed-view db]
     :compose [:<> [page-header db (t db :compose.title) (t db :compose.subtitle)] [composer db false]]
     :persona [characters-view db]
     :profile [profile-view db]
     :thread [thread-view db]
     :search [search-view db]
     :notifications [notifications-view db]
     :messages [messages-view db]
     (:privacy :terms :ai-policy :community-guidelines :security)
     [policy-view db (:route db)]
     [feed-view db])
   [site-footer db]])

(defn mobile-nav [db]
  [:nav.mobile-nav {:aria-label (t db :nav.mobile)}
   (for [item nav-items] ^{:key (first item)} [nav-button db item])])

(defn ai-progress-overlay [db]
  (when-let [{:keys [kind title-key body-key]} (ai-progress/active-operation db)]
    (r/with-let [started-at (.now js/Date)
                 elapsed-seconds (r/atom 0)
                 focus-claimed? (atom false)
                 previous-focus (.-activeElement js/document)
                 timer-id (js/setInterval
                           #(reset! elapsed-seconds
                                    (js/Math.floor
                                     (/ (- (.now js/Date) started-at) 1000)))
                           1000)]
      (let [seconds @elapsed-seconds]
        [:div.ai-progress-overlay
         {:data-operation (name kind)}
         [:section.ai-progress-dialog
          {:role "dialog"
           :aria-modal "true"
           :aria-labelledby "ai-progress-title"
           :aria-describedby "ai-progress-description"
           :tab-index -1
           :ref #(when (and % (compare-and-set! focus-claimed? false true))
                   (.focus %))
           :on-key-down #(when (= "Tab" (.-key %)) (.preventDefault %))}
          [:div.ai-progress-sigil {:aria-hidden "true"}
           (for [index (range 19)]
             ^{:key index} [:span])]
          [:div.ai-progress-copy
           [:span.eyebrow (t db :ai.progress.label)]
           [:h2#ai-progress-title (t db title-key)]
           [:p#ai-progress-description (t db body-key)]
           [:div.ai-progress-track
            {:role "progressbar" :aria-label (t db :ai.progress.status)}
            [:span]]
           [:div.ai-progress-meta
            [:span {:aria-hidden "true"}
             (t db :ai.progress.elapsed {:seconds seconds})]
            (when (>= seconds 8)
              [:span.ai-progress-long-wait (t db :ai.progress.long_wait)])]]]])
      (finally
        (js/clearInterval timer-id)
        (when (and previous-focus (.-isConnected previous-focus))
          (.focus previous-focus))))))

(defn action-toast [db]
  (when-let [{:keys [id tone message reaction kind]} (:toast db)]
    [:div.action-toast-host
     [:section.action-toast
      {:class (name tone)
       :role (if (= tone :error) "alert" "status")
       :aria-live (if (= tone :error) "assertive" "polite")}
      [:span.action-toast-mark {:aria-hidden "true"}
       (cond reaction [icons/reaction reaction]
             (= kind :repost) [icons/repost]
             :else [icons/reaction-choice])]
      [:p message]
      [:button.action-toast-close
       {:type "button"
        :aria-label (t db :common.close)
        :on-click #(rf/dispatch [:toast/dismiss id])}
       [icons/close]]]]))

(defn app []
  (let [db @(rf/subscribe [:db])]
    [:<>
     [:a.skip-link {:href "#main-content"} (t db :nav.skip_content)]
     [:div.app-shell
      {:class (when (= :persona (:route db)) "is-wide")}
      [left-rail db] [main-view db]]
     [mobile-nav db]
     [ai-progress-overlay db]
     [action-toast db]
     [:div.live-region {:aria-live "polite"} (:announcement db)]]))

(defn render! []
  (when-let [node (.getElementById js/document "app")]
    (when-not @root (reset! root (rdom/create-root node)))
    (rdom/render @root [app])))

(defn reload! [] (render!))

(defn init []
  (when (compare-and-set! history-listener-installed? false true)
    (.addEventListener js/window "popstate" #(rf/dispatch [:history/location-changed])))
  (rf/dispatch-sync [:initialize])
  (render!))
