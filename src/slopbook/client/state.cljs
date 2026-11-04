(ns slopbook.client.state
  (:require [re-frame.core :as rf]
            [slopbook.client.active-character :as active-character]
            [slopbook.client.api :as api]
            [slopbook.client.auth :as auth]
            [slopbook.client.choice :as choice]
            [slopbook.client.grapheme :as grapheme]
            [slopbook.client.i18n :as i18n]
            [slopbook.client.media :as media]
            [slopbook.client.message-state :as message-state]
            [slopbook.client.post-state :as post-state]
            [slopbook.client.realtime :as realtime]
            [slopbook.client.randomizer :as randomizer]
            [slopbook.client.routes :as routes]))

(defn- read-anonymous []
  (try
    (when-let [value (.getItem js/localStorage "slopbook.anonymous")]
      (update (js->clj (.parse js/JSON value) :keywordize-keys true)
              :owned-comments #(set (or % []))))
    (catch :default _ nil)))

(def initial-locale-value (i18n/initial-locale))

(def route-paths routes/route-paths)

(defn route-target-for-location []
  (routes/target-for-path (.-pathname js/location)))

(defn route-for-location []
  (:route (route-target-for-location)))

(defn persona-edit-handle-for-location []
  (when (= :persona (route-for-location))
    (some-> (js/URLSearchParams. (.-search js/location))
            (.get "edit")
            not-empty)))

(defn- guest-persona-ready? [persona]
  (and (= 1 (:schema_version persona))
       (seq (:display_name persona))
       (contains? (set i18n/locales) (:output_language persona))
       (map? (:persona_public persona))
       (seq (get-in persona [:persona_public :what_are_you]))
       (seq (get-in persona [:persona_public :archetypes]))
       (<= 3 (count (get-in persona [:persona_public :traits])) 5)
       (map? (:persona_behavior persona))
       (seq (get-in persona [:persona_behavior :voice_tones]))))

(defn- initial-anonymous [locale]
  (let [stored (or (read-anonymous) {})
        stored-persona (:persona stored)
        stored-ready? (guest-persona-ready? stored-persona)
        persona (if stored-ready?
                  stored-persona
                  (randomizer/random-guest locale))
        configured? (if (contains? stored :configured)
                      (and stored-ready? (true? (:configured stored)))
                      stored-ready?)]
    (merge {:configured configured? :status :idle :token nil :seed nil
            :draft persona :draft-seed nil :return-route :feed :owned-comments #{}}
           stored
           {:configured configured? :token nil :seed nil :draft persona
            :draft-seed nil :return-route :feed :persona persona})))

(defn- tr
  ([db key] (i18n/t (:locale db) key))
  ([db key replacements] (i18n/t (:locale db) key replacements)))

(def greeting-ai-error-codes
  #{"AI_UNAVAILABLE" "AI_PROVIDER_ERROR" "AI_CREDITS_EXHAUSTED"
    "AI_BUSY" "AI_TIMEOUT" "AI_OUTPUT_INVALID"})

(defn- greeting-error-text [db error]
  (if (contains? greeting-ai-error-codes (:code error))
    (tr db :persona.greeting_ai_unavailable)
    (i18n/error-text (:locale db) error)))

(defn signed-in-session? [db]
  (contains? #{:authenticated :development} (get-in db [:auth :status])))

(def initial-db
  {:route (route-for-location)
   :locale initial-locale-value
   :theme (keyword (or (.getItem js/localStorage "slopbook.theme") "system"))
   :feed-mode :latest
   :feed {:status :loading :items [] :next-cursor nil :error nil}
   :client-config nil
   :auth {:status :initializing :error nil}
   :account {:delete-open? false :status :idle :error nil}
   :persona-management {:delete-open? false :status :idle :error nil :target-id nil}
   :status nil
   :me {:status :loading :owned_characters [] :character_limit 5
        :remaining_character_slots 5}
   :preferred-character-id (active-character/read-preference)
   :active-character-id nil
   :profile {:status :idle :handle nil :value nil :error nil}
   :thread {:status :idle :post nil :items [] :next-cursor nil
            :text "" :reply-to nil :error nil}
   :reaction-pending #{}
   :reaction-ai-pending #{}
   :repost-pending #{}
   :compose {:text "" :status :idle :error nil :media []}
   :search {:query "" :type :character :status :idle :items [] :next-cursor nil
            :submitted-query nil :submitted-type nil :follow-pending {} :error nil}
   :following-ids #{}
   :notifications {:status :idle :items [] :next-cursor nil :unread-count 0
                   :connected false :seen-events #{} :error nil}
   :messages {:status :idle :items [] :next-cursor nil :active nil
              :detail-status :idle :text "" :error nil}
   :media {:post [] :comment [] :avatar nil}
   :anonymous (initial-anonymous initial-locale-value)
   :persona {:mode :create :editing-id nil :return-handle nil
             :draft nil :seed nil :status :idle :message ""
             :greeting {:status :idle :text nil :preview-token nil :expires-at nil}
             :created-owned nil :publish-after-preview false :retry-needs-preview false}
   :announcement ""
   :toast nil})

(rf/reg-fx
 :api
 (fn [{:keys [promise request success failure]}]
   (-> (if request (request) promise)
       (.then #(rf/dispatch (conj success %)))
       (.catch #(rf/dispatch (conj failure {:message (.-message %) :code (aget % "code")}))))))

(rf/reg-fx :api/configure api/configure!)
(rf/reg-fx :api/token api/set-access-token!)
(rf/reg-fx :api/development-auth api/set-development-auth!)

(rf/reg-fx
 :locale
 (fn [locale]
   (.setItem js/localStorage "slopbook.locale" locale)
   (.setAttribute js/document.documentElement "lang" locale)
   (.setAttribute js/document.documentElement "dir" "ltr")))

(rf/reg-fx
 :storage/anonymous
 (fn [value]
   (.setItem js/localStorage "slopbook.anonymous"
             (.stringify js/JSON
                         (clj->js (select-keys value
                                              [:session :persona :configured :owned-comments]))))))

(rf/reg-fx
 :media/revoke
 (fn [items]
   (doseq [item (if (sequential? items) items [items])]
     (when-let [url (:preview item)]
       (.revokeObjectURL js/URL url)))))

(rf/reg-fx :realtime/start #(realtime/start! % rf/dispatch))
(rf/reg-fx :realtime/stop (fn [_] (realtime/stop!)))

(rf/reg-fx
 :media/upload
 (fn [{:keys [file actor purpose language local-id]}]
   (-> (api/upload-media file actor purpose language
                         #(rf/dispatch [:media/progress purpose local-id %]))
       (.then #(rf/dispatch [:media/accepted purpose local-id %]))
       (.catch #(rf/dispatch [:media/failure purpose local-id
                              {:message (.-message %) :code (aget % "code")}])))))

(rf/reg-fx
 :auth/initialize
 (fn [config]
   (auth/initialize! config rf/dispatch)))

(rf/reg-fx
 :auth/login
 (fn [redirect-url]
   (-> (auth/sign-in! redirect-url)
       (.catch #(rf/dispatch [:auth/action-failure {:code "AUTH_START_FAILED"
                                                    :message (.-message %)}])))))

(rf/reg-fx
 :auth/logout
 (fn [success-event]
   (-> (auth/sign-out!)
       (.then #(rf/dispatch (if (vector? success-event)
                              success-event
                              [:auth/session nil "SIGNED_OUT"])))
       (.catch #(rf/dispatch [:auth/action-failure {:code "AUTH_SIGNOUT_FAILED"
                                                    :message (.-message %)}])))))

(rf/reg-fx
 :theme
 (fn [theme]
   (let [name (name theme)]
     (.setItem js/localStorage "slopbook.theme" name)
     (if (= :system theme)
       (.removeAttribute js/document.documentElement "data-theme")
       (.setAttribute js/document.documentElement "data-theme" name)))))

(rf/reg-fx :active-character active-character/store-preference!)

(rf/reg-fx
 :post/share
 (fn [post-id]
   (let [url (str (.-origin js/location) "/posts/" post-id)
         shared (when (.-share js/navigator)
                  (.share js/navigator #js {:title "Slopbook" :url url}))]
     (if shared
       (-> shared
           (.then #(rf/dispatch [:post/share-result true]))
           (.catch #(when (not= "AbortError" (.-name %))
                      (rf/dispatch [:post/share-result false]))))
       (if-let [clipboard (.-clipboard js/navigator)]
         (-> (.writeText clipboard url)
             (.then #(rf/dispatch [:post/share-result true]))
             (.catch #(rf/dispatch [:post/share-result false])))
         (rf/dispatch [:post/share-result false]))))))

(rf/reg-fx
 :history/push
 (fn [path]
   (when (and path (not= path (.-pathname js/location)))
     (.pushState js/history nil "" path))))

(rf/reg-fx
 :sigil
 (fn [draft]
   (-> (randomizer/sigil-seed draft)
       (.then #(rf/dispatch [:persona/sigil-ready draft %])))))

(rf/reg-fx
 :anonymous-sigil
 (fn [draft]
   (-> (randomizer/sigil-seed draft)
       (.then #(rf/dispatch [:anonymous/sigil-ready draft %])))))

(rf/reg-event-fx
 :initialize
 (fn [_ _]
   (let [{:keys [route handle post-id]} (route-target-for-location)
         route-effect (case route
                        :profile [[:dispatch [:profile/open handle]]]
                        :thread [[:dispatch [:thread/open-id post-id]]]
                        [])]
     {:db initial-db
      :theme (:theme initial-db)
      :locale (:locale initial-db)
      :anonymous-sigil (get-in initial-db [:anonymous :persona])
      :fx (vec
           (concat
            [[:api {:promise (api/client-config) :success [:config/success] :failure [:config/failure]}]
             [:api {:promise (api/status) :success [:status/success] :failure [:status/failure]}]
             [:api {:promise (api/feed :latest) :success [:feed/success false] :failure [:feed/failure]}]]
            route-effect))})))

(rf/reg-event-fx
 :config/success
 (fn [{:keys [db]} [_ config]]
   {:db (assoc db :client-config config)
    :fx [[:api/configure config]
         [:auth/initialize config]]}))

(rf/reg-event-db
 :config/failure
 (fn [db _]
   (assoc db :auth {:status :unavailable
                    :error {:code "AUTH_CONFIG_UNAVAILABLE"
                            :message (tr db :auth.config_failed)}})))

(rf/reg-event-fx
 :auth/session
 (fn [{:keys [db]} [_ token event]]
   (let [dev-auth? (true? (get-in db [:client-config :dev_auth]))
         enabled? (true? (get-in db [:client-config :auth :enabled]))
         authenticated? (some? token)
         next-status (cond authenticated? :authenticated
                           (not enabled?) (if dev-auth? :development :disabled)
                           :else :anonymous)]
     (cond->
      {:db (-> db
               (assoc :auth {:status next-status :error nil :event event})
               (assoc :me (if (or authenticated? dev-auth?)
                            {:status :loading :owned_characters [] :character_limit 5
                             :remaining_character_slots 5}
                            {:status :anonymous :owned_characters [] :character_limit 5
                             :remaining_character_slots 5}))
               (assoc :active-character-id nil)
               (cond-> (not (or authenticated? dev-auth?))
                 (assoc :preferred-character-id nil)))
       :fx (vec (concat
                 (auth/session-effects
                  token
                  (when (or authenticated? dev-auth?)
                    {:request api/me
                     :success [:me/success]
                     :failure [:me/failure]}))
                 (when-not (or authenticated? dev-auth?) [[:realtime/stop true]])
                 (when (and (= :persona (:route db))
                            (not (or authenticated? dev-auth?)))
                   [[:dispatch [:guest/start :feed]]])))}
       (not (or authenticated? dev-auth?)) (assoc :active-character nil)))))

(rf/reg-event-db
 :auth/callback-failure
 (fn [db [_ error]]
   (assoc db :auth {:status :error :error error})))

(rf/reg-event-fx
 :auth/login
 (fn [{:keys [db]} _]
   {:db (assoc db :auth {:status :redirecting :error nil})
    :auth/login (get-in db [:client-config :auth :redirect_url])}))

(rf/reg-event-fx
 :auth/logout
 (fn [{:keys [db]} _]
   (if (= :development (get-in db [:auth :status]))
     {:db (-> db
              (assoc :auth {:status :anonymous :error nil}
                     :me {:status :anonymous :owned_characters [] :character_limit 5
                          :remaining_character_slots 5}
                     :active-character-id nil :preferred-character-id nil))
      :active-character nil
      :api/development-auth false
      :api/token nil
      :realtime/stop true}
     {:db (assoc db :auth {:status :signing-out :error nil})
      :auth/logout true})))

(rf/reg-event-fx
 :auth/development-login
 (fn [{:keys [db]} _]
   {:db (-> db
            (assoc :auth {:status :development :error nil})
            (assoc :me {:status :loading :owned_characters [] :character_limit 5
                        :remaining_character_slots 5}))
    :api/development-auth true
    :api {:promise (api/me) :success [:me/success] :failure [:me/failure]}}))

(rf/reg-event-db
 :account/delete-open
 (fn [db _]
   (assoc db :account {:delete-open? true :status :idle :error nil})))

(rf/reg-event-db
 :account/delete-cancel
 (fn [db _]
   (assoc db :account {:delete-open? false :status :idle :error nil})))

(rf/reg-event-fx
 :account/delete-confirm
 (fn [{:keys [db]} _]
   {:db (assoc db :account {:delete-open? true :status :deleting :error nil})
    :api {:promise (api/delete-account)
          :success [:account/delete-success]
          :failure [:account/delete-failure]}}))

(rf/reg-event-fx
 :account/delete-success
 (fn [{:keys [db]} _]
   {:db (assoc db :account {:delete-open? true :status :signing-out :error nil})
    :auth/logout [:account/signed-out]}))

(rf/reg-event-fx
 :account/signed-out
 (fn [{:keys [db]} _]
   {:db (-> db
            (assoc :route :feed
                   :auth {:status :anonymous :error nil}
                   :account {:delete-open? false :status :success :error nil}
                   :me {:status :anonymous :owned_characters [] :character_limit 5
                        :remaining_character_slots 5}
                   :active-character-id nil :preferred-character-id nil
                   :profile {:status :idle :handle nil :value nil :error nil}
                   :announcement (tr db :account.delete_requested)))
    :history/push (get route-paths :feed)
    :active-character nil
    :api/development-auth false
    :api/token nil
    :realtime/stop true}))

(rf/reg-event-db
 :account/delete-failure
 (fn [db [_ error]]
   (assoc db :account {:delete-open? true :status :error :error error})))

(rf/reg-event-db
 :auth/action-failure
 (fn [db [_ error]]
   (assoc db :auth {:status :error :error error})))

(rf/reg-event-db :status/success (fn [db [_ value]] (assoc db :status value)))
(rf/reg-event-db :status/failure (fn [db _] (assoc db :status {:mode "degraded"})))

(rf/reg-event-fx
 :me/success
 (fn [{:keys [db]} [_ value]]
   (let [characters (:owned_characters value)
         actor (active-character/select-owned-id
                characters (:active-character-id db) (:preferred-character-id db))
         edit-handle (persona-edit-handle-for-location)
         editing-id (some (fn [owned]
                            (when (= edit-handle (get-in owned [:public_character :handle]))
                              (get-in owned [:public_character :id])))
                          characters)
         fx (vec
             (concat
              (when actor
                [[:dispatch [:feed/load (:feed-mode db)]]
                 [:api {:promise (api/notifications actor nil)
                        :success [:notifications/success false]
                        :failure [:notifications/background-failure]}]
                 [:api {:promise (api/conversations actor nil)
                        :success [:messages/list-success false]
                        :failure [:messages/background-failure]}]
                 [:api {:promise (api/connections actor :following actor)
                        :success [:following/success false actor]
                        :failure [:following/failure]}]])
              (when (= :persona (:route db))
                [[:dispatch (if editing-id
                              [:persona/edit-start editing-id]
                              [:persona/start])]])))]
     (cond-> {:db (-> db
                      (assoc :me (assoc value :status :success))
                      (assoc :active-character-id actor :preferred-character-id actor))
              :active-character actor}
       actor (assoc :realtime/start actor)
       (seq fx) (assoc :fx fx)))))

(rf/reg-event-fx
 :me/failure
 (fn [{:keys [db]} [_ error]]
   (if (contains? #{"AUTH_EXPIRED" "AUTH_INVALID"} (:code error))
     {:db (-> db
              (assoc :auth {:status (if (= "AUTH_EXPIRED" (:code error)) :expired :error)
                            :error error})
              (assoc :me {:status :error :error error :owned_characters []
                          :character_limit 5 :remaining_character_slots 5})
              (assoc :active-character-id nil :preferred-character-id nil))
      :active-character nil
      :api/token nil}
     {:db (assoc db :me {:status :error :error error :owned_characters []
                         :character_limit 5 :remaining_character_slots 5})})))

(rf/reg-event-db
 :feed/success
 (fn [db [_ append? value]]
   (let [existing (if append? (get-in db [:feed :items]) [])]
     (assoc db :feed {:status :success
                      :items (vec (concat existing (:items value)))
                      :next-cursor (:next_cursor value)
                      :error nil}))))
(rf/reg-event-db :feed/failure (fn [db [_ error]] (assoc db :feed {:status :error :error error :items []})))

(rf/reg-event-fx
 :thread/open
 (fn [{:keys [db]} [_ post]]
   {:db (assoc db :route :thread
                  :thread {:status :loading :post post :items [] :next-cursor nil
                           :text "" :reply-to nil :error nil})
    :history/push (routes/post-path (:id post))
    :api {:promise (api/comments (:id post) nil (:active-character-id db))
          :success [:thread/success false]
          :failure [:thread/failure]}}))

(rf/reg-event-fx
 :thread/open-id
 (fn [{:keys [db]} [_ post-id]]
   {:db (assoc db :route :thread
                  :thread {:status :loading :post nil :items [] :next-cursor nil
                           :text "" :reply-to nil :error nil})
    :api {:promise (api/post post-id (:active-character-id db))
          :success [:thread/open]
          :failure [:thread/failure]}}))

(rf/reg-event-db
 :thread/success
 (fn [db [_ append? value]]
   (-> db
       (assoc-in [:thread :status] :success)
       (update-in [:thread :items]
                  #(if append? (vec (concat % (:items value))) (:items value)))
       (assoc-in [:thread :next-cursor] (:next_cursor value))
       (assoc-in [:thread :error] nil))))

(rf/reg-event-fx
 :thread/load-more
 (fn [{:keys [db]} _]
   (let [post-id (get-in db [:thread :post :id])
         cursor (get-in db [:thread :next-cursor])]
     (if-not cursor
       {:db db}
       {:db (assoc-in db [:thread :status] :loading-more)
        :api {:promise (api/comments post-id cursor (:active-character-id db))
              :success [:thread/success true]
              :failure [:thread/failure]}}))))

(rf/reg-event-db
 :thread/failure
 (fn [db [_ error]]
   (-> db
       (assoc-in [:thread :status] :error)
       (assoc-in [:thread :error] error))))

(rf/reg-event-db :comment/set-text (fn [db [_ value]] (assoc-in db [:thread :text] value)))
(rf/reg-event-db :comment/reply-to (fn [db [_ comment]] (assoc-in db [:thread :reply-to] comment)))
(rf/reg-event-db :comment/cancel-reply (fn [db _] (assoc-in db [:thread :reply-to] nil)))

(defn- append-comment [items value]
  (if-let [root-id (:parent_comment_id value)]
    (mapv (fn [thread]
            (if (= root-id (get-in thread [:root :id]))
              (update thread :replies conj value)
              (if (= (:thread_root_id value) (get-in thread [:root :id]))
                (update thread :replies conj value)
                thread)))
          items)
    (conj items {:root value :replies []})))

(defn- ready-media-ids [items]
  (->> items (filter #(= :ready (:status %))) (mapv :id)))

(defn- media-pending? [items]
  (some #(contains? #{:uploading :processing} (:status %)) items))

(rf/reg-event-fx
 :comment/submit
 (fn [{:keys [db]} _]
   (let [text (get-in db [:thread :text])
         actor (:active-character-id db)
         post-id (get-in db [:thread :post :id])
         reply-to (get-in db [:thread :reply-to])
         anonymous? (not (signed-in-session? db))
         guest-configured? (true? (get-in db [:anonymous :configured]))
         session (get-in db [:anonymous :session])
         persona (get-in db [:anonymous :persona])
         token (or (get-in db [:anonymous :token])
                   (when (get-in db [:client-config :turnstile :development]) "development-pass"))]
     (cond
       (and anonymous? (not guest-configured?))
       {:db (assoc-in db [:thread :error]
                      {:code "GUEST_CHARACTER_REQUIRED"
                       :message (tr db :anonymous.required)})}

       (or (empty? text) (> (grapheme/length text) 180)
           (and (not anonymous?) (nil? actor))
           (and anonymous? (not (guest-persona-ready? persona))))
       {:db (assoc-in db [:thread :error]
                      {:code "COMMENT_INVALID" :message (tr db :comment.invalid)})}

       (and anonymous? (nil? session))
       {:db (assoc-in db [:anonymous :status] :creating-session)
        :api {:promise (api/anonymous-session)
              :success [:anonymous/session-success :comment/submit]
              :failure [:comment/failure]}}

       (and anonymous? (nil? token))
       {:db (assoc-in db [:thread :error]
                      {:code "TURNSTILE_REQUIRED" :message (tr db :anonymous.turnstile_required)})}

       :else
       {:db (-> db
                (assoc-in [:thread :status] :submitting)
                (assoc-in [:thread :error] nil))
        :api {:promise
              (if anonymous?
                (api/create-anonymous-comment
                 post-id
                 (cond-> {:anonymous_session_id (:anonymous_session_id session)
                          :input_text text
                          :anonymous_persona persona
                          :turnstile_token token}
                   reply-to (assoc :parent_comment_id (:id reply-to))))
                (api/create-comment
                 post-id
                 (cond-> {:actor_character_id actor :input_text text}
                   reply-to (assoc :parent_comment_id (:id reply-to)))))
              :success [:comment/success anonymous?]
              :failure [:comment/failure]}}))))

(rf/reg-event-fx
 :comment/success
 (fn [{:keys [db]} [_ anonymous? value]]
   (let [post-id (get-in db [:thread :post :id])
         media (get-in db [:media :comment])
         next (-> db
                  (update-in [:thread :items] append-comment value)
                  (assoc-in [:thread :text] "")
                  (assoc-in [:thread :reply-to] nil)
                  (assoc-in [:anonymous :token] nil)
                  (cond-> anonymous?
                    (update-in [:anonymous :owned-comments] (fnil conj #{}) (:id value)))
                  (assoc-in [:media :comment] [])
                  (assoc-in [:thread :status] :success)
                  (post-state/adjust-comment-count post-id 1)
                  (assoc :announcement (tr db :comment.success)))]
     (cond-> {:db next :media/revoke media}
       anonymous? (assoc :storage/anonymous (:anonymous next))))))

(rf/reg-event-db
 :comment/failure
 (fn [db [_ error]]
   (-> db
       (assoc-in [:thread :status] :error)
       (assoc-in [:thread :error] error))))

(rf/reg-event-fx
 :comment/delete
 (fn [{:keys [db]} [_ comment-id]]
   {:db (assoc-in db [:thread :status] :deleting)
    :api {:promise (api/delete-comment comment-id (:active-character-id db))
          :success [:comment/delete-success comment-id]
          :failure [:comment/failure]}}))

(rf/reg-event-db
 :comment/delete-success
 (fn [db [_ comment-id result]]
   (let [post-id (get-in db [:thread :post :id])
         remove-or-tombstone
         (fn [value]
           (when value
             (if (= comment-id (:id value))
               (when (:tombstone result) (assoc value :status "deleted" :text nil))
               value)))
         items (->> (get-in db [:thread :items])
                    (keep (fn [thread]
                            (let [root (remove-or-tombstone (:root thread))
                                  replies (->> (:replies thread) (keep remove-or-tombstone) vec)]
                              (when root (assoc thread :root root :replies replies)))))
                    vec)]
     (-> db
         (assoc-in [:thread :items] items)
         (assoc-in [:thread :status] :success)
         (post-state/adjust-comment-count post-id -1)
         (assoc :announcement (tr db :comment.deleted_success))))))

(defn- update-target [db target-type target-id f]
  (case target-type
    :post
    (-> db
        (update-in [:feed :items]
                   #(mapv (fn [post] (if (= target-id (:id post)) (f post) post)) %))
        (update-in [:thread :post]
                   #(if (= target-id (:id %)) (f %) %))
        (update-in [:profile :value :posts :items]
                   #(mapv (fn [post] (if (= target-id (:id post)) (f post) post)) (or % []))))

    :comment
    (update-in db [:thread :items]
               #(mapv (fn [thread]
                        (-> thread
                            (update :root (fn [value] (if (= target-id (:id value)) (f value) value)))
                            (update :replies
                                    (fn [items]
                                      (mapv (fn [value]
                                              (if (= target-id (:id value)) (f value) value))
                                            items)))))
                      %))
    db))

(rf/reg-event-fx
 :reaction/toggle
 (fn [{:keys [db]} [_ target-type target-id first-decision?]]
   (let [key [target-type target-id]
         actor (:active-character-id db)]
     (cond
       (nil? actor)
       {:db (assoc db :announcement (tr db :reaction.login_required))}

       (contains? (:reaction-pending db) key) {:db db}

       :else
       {:db (cond-> (update db :reaction-pending conj key)
              first-decision? (update :reaction-ai-pending conj key))
        :api {:promise (api/toggle-reaction target-type target-id actor)
              :success [:reaction/success target-type target-id]
              :failure [:reaction/failure target-type target-id]}}))))

(rf/reg-event-fx
 :reaction/success
 (fn [{:keys [db]} [_ target-type target-id result]]
   (let [key [target-type target-id]
         viewer (select-keys result [:reaction :active :restored :display])
         actor-name (or (some (fn [owned]
                               (when (= (:active-character-id db)
                                        (get-in owned [:public_character :id]))
                                 (get-in owned [:public_character :display_name])))
                             (get-in db [:me :owned_characters]))
                        (tr db :reaction.persona))
         reaction-label (tr db (keyword (str "reaction." (:reaction result))))
         message (if (:active result)
                   (tr db :reaction.selected {:name actor-name :reaction reaction-label})
                   (tr db :reaction.removed {:name actor-name}))
         toast-id (str (random-uuid))
         next-db (-> db
                     (update :reaction-pending disj key)
                     (update :reaction-ai-pending disj key)
                     (update-target target-type target-id
                                    #(assoc % :reaction_counts (:counts result)
                                            :viewer_reaction viewer))
                     (assoc :announcement message)
                     (assoc :toast {:id toast-id :tone :success :message message
                                    :reaction (when (:active result) (:reaction result))}))]
     {:db next-db
      :dispatch-later {:ms 3600 :dispatch [:toast/dismiss toast-id]}})))

(rf/reg-event-fx
 :reaction/failure
 (fn [{:keys [db]} [_ target-type target-id error]]
   (let [message (i18n/error-text (:locale db) error)
         toast-id (str (random-uuid))]
     {:db (-> db
              (update :reaction-pending disj [target-type target-id])
              (update :reaction-ai-pending disj [target-type target-id])
              (assoc :announcement message)
              (assoc :toast {:id toast-id :tone :error :message message}))
      :dispatch-later {:ms 5200 :dispatch [:toast/dismiss toast-id]}})))

(rf/reg-event-fx
 :repost/toggle
 (fn [{:keys [db]} [_ post-id]]
   (let [actor (:active-character-id db)]
     (cond
       (nil? actor) {:db (assoc db :announcement (tr db :repost.login_required))}
       (contains? (:repost-pending db) post-id) {:db db}
       :else {:db (update db :repost-pending conj post-id)
              :api {:promise (api/toggle-repost post-id actor)
                    :success [:repost/success post-id]
                    :failure [:repost/failure post-id]}}))))

(rf/reg-event-fx
 :repost/success
 (fn [{:keys [db]} [_ post-id result]]
   (let [message (tr db (if (:active result) :repost.added :repost.removed))
         toast-id (str (random-uuid))]
     {:db (-> db
              (update :repost-pending disj post-id)
              (update-target :post post-id
                             #(assoc % :repost_count (:repost_count result)
                                     :viewer_repost (select-keys result [:active :restored])))
              (assoc :announcement message)
              (assoc :toast {:id toast-id :tone :success :kind :repost :message message}))
      :dispatch-later {:ms 3600 :dispatch [:toast/dismiss toast-id]}})))

(rf/reg-event-fx
 :repost/failure
 (fn [{:keys [db]} [_ post-id error]]
   (let [message (i18n/error-text (:locale db) error)
         toast-id (str (random-uuid))]
     {:db (-> db
              (update :repost-pending disj post-id)
              (assoc :announcement message)
              (assoc :toast {:id toast-id :tone :error :message message}))
      :dispatch-later {:ms 5200 :dispatch [:toast/dismiss toast-id]}})))

(rf/reg-event-fx
 :post/share
 (fn [{:keys [db]} [_ post-id]]
   {:db db :post/share post-id}))

(rf/reg-event-db
 :post/share-result
 (fn [db [_ success?]]
   (assoc db :announcement (tr db (if success? :post.share_success :post.share_failed)))))

(rf/reg-event-db
 :toast/dismiss
 (fn [db [_ toast-id]]
   (if (= toast-id (get-in db [:toast :id]))
     (assoc db :toast nil)
     db)))

(rf/reg-event-fx
 :feed/load
 (fn [{:keys [db]} [_ mode]]
   {:db (assoc db :feed-mode mode :feed (assoc (:feed db) :status :loading
                                               :next-cursor nil :error nil))
    :api {:promise (api/feed-for mode (:active-character-id db) nil)
          :success [:feed/success false] :failure [:feed/failure]}}))

(rf/reg-event-fx
 :feed/load-more
 (fn [{:keys [db]} _]
   (if-let [cursor (get-in db [:feed :next-cursor])]
     {:db (assoc-in db [:feed :status] :loading-more)
      :api {:promise (api/feed-for (:feed-mode db) (:active-character-id db) cursor)
            :success [:feed/success true]
            :failure [:feed/failure]}}
     {:db db})))

(defn owned-public-by-id [db character-id]
  (some (fn [owned]
          (let [public (:public_character owned)]
            (when (= character-id (:id public)) public)))
        (get-in db [:me :owned_characters])))

(defn active-public [db]
  (owned-public-by-id db (:active-character-id db)))

(rf/reg-event-fx
 :navigate
 (fn [{:keys [db]} [_ route]]
   (let [actor (:active-character-id db)
         profile (active-public db)
         history-path (if (and (= :profile route) profile)
                        (routes/profile-path (:handle profile))
                        (get route-paths route))]
     (cond->
      (cond
        (= :persona route)
        {:db (assoc db :route :persona)
         :dispatch (if (signed-in-session? db)
                     [:persona/start]
                     [:guest/start (:route db)])}

        (and (= :profile route) profile)
        (let [handle (:handle profile)]
          {:db (assoc db :route :profile
                         :profile {:status :loading :handle handle :value nil :error nil})
           :api {:promise (api/profile handle nil actor)
                 :success [:profile/success false]
                 :failure [:profile/failure]}})

        (= :notifications route)
        (if actor
          {:db (-> db (assoc :route route)
                   (assoc :notifications (assoc (:notifications db) :status :loading :error nil)))
           :api {:promise (api/notifications actor nil)
                 :success [:notifications/success false]
                 :failure [:notifications/failure]}}
          {:db (assoc db :route route)})

        (= :messages route)
        (if actor
          {:db (-> db (assoc :route route)
                   (assoc-in [:messages :status] :loading))
           :api {:promise (api/conversations actor nil)
                 :success [:messages/list-success false]
                 :failure [:messages/list-failure]}}
          {:db (assoc db :route route)})

        :else {:db (assoc db :route route)})
       history-path (assoc :history/push history-path)))))

(rf/reg-event-fx
 :history/location-changed
 (fn [_ _]
   (let [{:keys [route handle post-id]} (route-target-for-location)]
     {:dispatch (case route
                  :profile [:profile/open handle]
                  :thread [:thread/open-id post-id]
                  [:navigate route])})))

(rf/reg-event-fx
 :profile/open
 (fn [{:keys [db]} [_ handle]]
   {:db (assoc db :route :profile
                  :profile {:status :loading :handle handle :value nil :error nil}
                  :persona-management
                  {:delete-open? false :status :idle :error nil :target-id nil})
    :history/push (routes/profile-path handle)
    :api {:promise (api/profile handle nil (:active-character-id db))
          :success [:profile/success false]
          :failure [:profile/failure]}}))

(rf/reg-event-fx
 :profile/load-more
 (fn [{:keys [db]} _]
   (let [handle (get-in db [:profile :handle])
         cursor (get-in db [:profile :value :posts :next_cursor])]
     (if-not cursor
       {:db db}
       {:db (assoc-in db [:profile :status] :loading-more)
        :api {:promise (api/profile handle cursor (:active-character-id db))
              :success [:profile/success true]
              :failure [:profile/failure]}}))))

(rf/reg-event-db
 :profile/success
 (fn [db [_ append? value]]
   (let [existing (get-in db [:profile :value :posts :items])
         value (if append?
                 (update-in value [:posts :items] #(vec (concat existing %)))
                 value)]
     (assoc db :profile {:status :success :handle (:handle value) :value value :error nil}))))

(rf/reg-event-db
 :profile/failure
 (fn [db [_ error]]
   (assoc db :profile {:status :error
                       :handle (get-in db [:profile :handle])
                       :value (get-in db [:profile :value])
                       :error error})))
(rf/reg-event-fx
 :active/select
 (fn [{:keys [db]} [_ id]]
   {:db (-> db
            (assoc :active-character-id id :preferred-character-id id)
            (assoc :following-ids #{})
            (assoc :notifications (assoc (:notifications db) :status :idle :items []
                                         :unread-count 0 :next-cursor nil))
            (assoc :messages (assoc (:messages db) :status :idle :items [] :active nil)))
    :active-character id
    :realtime/start id
    :fx [[:dispatch [:navigate (:route db)]]
         [:api {:promise (api/notifications id nil)
                :success [:notifications/success false]
                :failure [:notifications/background-failure]}]
         [:api {:promise (api/conversations id nil)
                :success [:messages/list-success false]
                :failure [:messages/background-failure]}]
         [:api {:promise (api/connections id :following id)
                :success [:following/success false id]
                :failure [:following/failure]}]]}))

(rf/reg-event-fx
 :locale/select
 (fn [{:keys [db]} [_ requested-locale]]
   (let [locale (some #{requested-locale} i18n/locales)]
     (if (or (nil? locale) (= locale (:locale db)))
       {:db db}
       (let [language (i18n/t locale (keyword (str "language." locale)))]
         {:db (assoc db :locale locale
                        :announcement (i18n/t locale :language.changed {:language language}))
          :locale locale})))))

(rf/reg-event-fx
 :theme/cycle
 (fn [{:keys [db]} _]
   (let [next-theme ({:system :light :light :dark :dark :system} (:theme db))]
     {:db (assoc db :theme next-theme)
      :theme next-theme})))

(rf/reg-event-db :compose/set-text (fn [db [_ value]] (assoc-in db [:compose :text] value)))

(rf/reg-event-fx
 :compose/source
 (fn [{:keys [db]} [_ source]]
   {:db (-> db
            (assoc :route :compose)
            (assoc-in [:compose :source] (select-keys source [:id :url :title]))
            (assoc-in [:compose :status] :idle)
            (assoc-in [:compose :error] nil))
    :history/push (get route-paths :compose)}))

(rf/reg-event-db
 :compose/clear-source
 (fn [db _] (update db :compose dissoc :source :relay-request)))

(rf/reg-event-fx
 :compose/submit
 (fn [{:keys [db]} _]
   (let [text (get-in db [:compose :text])
         source (get-in db [:compose :source])
         actor (:active-character-id db)
         media (get-in db [:media :post])
         source-payload (when source {:post_id (:id source) :actor_character_id actor
                                     :input_text text :media_ids (ready-media-ids media)})
         previous (get-in db [:compose :relay-request])
         request-key (when source (if (= source-payload (:payload previous))
                                   (:key previous) (api/idempotency-key)))]
     (if (or (empty? text) (nil? actor)
             (> (grapheme/length (str text (when source (str "\n" (:url source))))) 280))
       {:db (assoc-in db [:compose :error] (tr db :compose.invalid))}
       (if (media-pending? media)
         {:db (assoc-in db [:compose :error] (tr db :media.processing))}
       {:db (update db :compose assoc :status :loading :error nil
                    :relay-request (when source {:payload source-payload :key request-key}))
        :api {:promise (if source
                         (api/request "POST" "/api/v1/relay/posts" source-payload request-key)
                         (api/create-post {:actor_character_id actor :input_text text
                                           :media_ids (ready-media-ids media)}))
              :success [:compose/success]
              :failure [:compose/failure]}})))))

(rf/reg-event-fx
 :compose/success
 (fn [{:keys [db]} [_ post]]
   (cond-> {:db (-> db
            (assoc :compose {:text "" :status :success :error nil})
            (assoc-in [:media :post] [])
            (update-in [:feed :items] #(vec (cons post %)))
            (assoc :announcement (tr db :compose.success)))
    :media/revoke (get-in db [:media :post])}
     (get-in db [:compose :source]) (assoc :dispatch [:thread/open post]))))

(rf/reg-event-db
 :compose/failure
 (fn [db [_ error]]
   (-> db
       (assoc-in [:compose :status] :error)
       (assoc-in [:compose :error] (i18n/error-text (:locale db) error)))))

(defn- remaining-character-slots [db]
  (or (get-in db [:me :remaining_character_slots])
      (max 0 (- (or (get-in db [:me :character_limit]) 5)
                (count (get-in db [:me :owned_characters]))))))

(defn- empty-greeting-state []
  {:status :idle :text nil :preview-token nil :expires-at nil})

(defn- new-persona-state
  ([draft] (new-persona-state draft :create nil nil))
  ([draft mode editing-id return-handle]
   {:mode mode :editing-id editing-id :return-handle return-handle
    :draft draft :seed nil :status :idle :message ""
    :greeting (empty-greeting-state)
    :created-owned nil :publish-after-preview false :retry-needs-preview false}))

(defn- owned-persona-draft [owned]
  (let [public (:public_character owned)]
    {:handle (:handle public)
     :display_name (:display_name public)
     :nationality (:nationality public)
     :output_language (:output_language public)
     :persona_public (:persona_public public)
     :persona_behavior (:persona_behavior owned)}))

(defn- replace-owned-persona [owned-personas updated]
  (let [updated-id (get-in updated [:public_character :id])]
    (mapv (fn [owned]
            (if (= updated-id (get-in owned [:public_character :id])) updated owned))
          owned-personas)))

(rf/reg-event-fx
 :persona/start
 (fn [{:keys [db]} _]
   (if (zero? (remaining-character-slots db))
     {:db (assoc db :announcement (tr db :persona.limit_reached))}
     (let [draft (randomizer/random-draft nil (:locale db))]
       {:db (-> db
                (assoc :route :persona)
                (assoc :persona (new-persona-state draft)))
        :history/push (get route-paths :persona)
        :sigil draft}))))

(rf/reg-event-fx
 :persona/edit-start
 (fn [{:keys [db]} [_ character-id]]
   (if-let [owned (some #(when (= character-id (get-in % [:public_character :id])) %)
                        (get-in db [:me :owned_characters]))]
     (let [draft (owned-persona-draft owned)
           handle (get-in owned [:public_character :handle])]
       {:db (-> db
                (assoc :route :persona)
                (assoc :persona (new-persona-state draft :edit character-id handle))
                (assoc :persona-management
                       {:delete-open? false :status :idle :error nil :target-id nil}))
        :history/push (str (get route-paths :persona)
                           "?edit=" (js/encodeURIComponent handle))
        :sigil draft})
     {:db (assoc db :announcement (tr db :error.CHARACTER_NOT_OWNED))})))

(rf/reg-event-fx
 :persona/randomize
 (fn [{:keys [db]} _]
   (let [draft (get-in db [:persona :draft])
         editing? (= :edit (get-in db [:persona :mode]))
         next-draft (cond-> (randomizer/random-draft draft)
                      editing? (assoc :handle (:handle draft)))]
     {:db (-> db
              (assoc-in [:persona :draft] next-draft)
              (assoc-in [:persona :status] :idle)
              (assoc-in [:persona :greeting] (empty-greeting-state))
              (assoc-in [:persona :message] (tr db :persona.rerolled))
              (assoc :announcement (tr db :persona.rerolled)))
      :sigil next-draft})))

(rf/reg-event-fx
 :persona/set
 (fn [{:keys [db]} [_ path value]]
   (let [next-db (assoc-in db (into [:persona :draft] path) value)
         draft (get-in next-db [:persona :draft])]
     {:db (-> next-db
              (assoc-in [:persona :status] :idle)
              (assoc-in [:persona :greeting]
                        (assoc (empty-greeting-state) :status :stale))
              (assoc-in [:persona :message] ""))
      :sigil draft})))

(rf/reg-event-fx
 :persona/toggle-choice
 (fn [{:keys [db]} [_ path id minimum maximum]]
   (let [full-path (into [:persona :draft] path)
         next-db (update-in db full-path choice/toggle-ref id minimum maximum)
         draft (get-in next-db [:persona :draft])]
     {:db (-> next-db
              (assoc-in [:persona :status] :idle)
              (assoc-in [:persona :greeting]
                        (assoc (empty-greeting-state) :status :stale))
              (assoc-in [:persona :message] ""))
      :sigil draft})))

(rf/reg-event-db
 :persona/sigil-ready
 (fn [db [_ draft seed]]
   (cond-> db
     (= draft (get-in db [:persona :draft])) (assoc-in [:persona :seed] seed))))

(rf/reg-event-fx
 :persona/greeting-preview
 (fn [{:keys [db]} [_ publish-after-preview?]]
   (let [draft (get-in db [:persona :draft])]
     {:db (-> db
              (assoc-in [:persona :status] :previewing)
              (assoc-in [:persona :publish-after-preview] (boolean publish-after-preview?))
              (assoc-in [:persona :greeting]
                        (assoc (empty-greeting-state) :status :loading)))
      :api {:promise (api/preview-character-greeting draft)
            :success [:persona/greeting-preview-success draft]
            :failure [:persona/greeting-preview-failure draft]}})))

(rf/reg-event-fx
 :persona/greeting-preview-success
 (fn [{:keys [db]} [_ requested-draft preview]]
   (if (not= requested-draft (get-in db [:persona :draft]))
     {:db db}
     (let [publish-after? (true? (get-in db [:persona :publish-after-preview]))
           next-db (-> db
                       (assoc-in [:persona :status] :idle)
                       (assoc-in [:persona :message] "")
                       (assoc-in [:persona :publish-after-preview] false)
                       (assoc-in [:persona :greeting]
                                 {:status :ready
                                  :text (:text preview)
                                  :preview-token (:preview_token preview)
                                  :expires-at (:expires_at preview)}))]
       (cond-> {:db next-db}
         publish-after? (assoc :dispatch [:persona/publish-greeting]))))))

(rf/reg-event-db
 :persona/greeting-preview-failure
 (fn [db [_ requested-draft error]]
   (if (not= requested-draft (get-in db [:persona :draft]))
     db
     (-> db
         (assoc-in [:persona :status] :error)
         (assoc-in [:persona :publish-after-preview] false)
         (assoc-in [:persona :greeting :status] :error)
         (assoc-in [:persona :message] (greeting-error-text db error))))))

(rf/reg-event-fx
 :persona/create
 (fn [{:keys [db]} _]
   (let [draft (get-in db [:persona :draft])
         greeting-ready? (= :ready (get-in db [:persona :greeting :status]))]
     (cond
       (zero? (remaining-character-slots db))
       {:db (-> db
                (assoc-in [:persona :status] :error)
                (assoc-in [:persona :message] (tr db :persona.limit_reached)))}

       (not greeting-ready?)
       {:db (-> db
                (assoc-in [:persona :status] :error)
                (assoc-in [:persona :message] (tr db :persona.greeting_refresh_required)))}

       :else
       {:db (assoc-in db [:persona :status] :loading)
        :api {:promise (api/create-character draft)
              :success [:persona/create-success]
              :failure [:persona/create-failure]}}))))

(rf/reg-event-fx
 :persona/create-success
 (fn [{:keys [db]} [_ owned]]
   (let [id (get-in owned [:public_character :id])]
     {:db (-> db
              (update-in [:me :owned_characters] conj owned)
              (update-in [:me :remaining_character_slots] #(max 0 (dec (or % 1))))
              (assoc :active-character-id id :preferred-character-id id :following-ids #{})
              (assoc-in [:persona :created-owned] owned)
              (assoc-in [:persona :status] :publishing-greeting)
              (assoc-in [:persona :greeting :status] :publishing)
              (assoc-in [:persona :retry-needs-preview] false)
              (assoc-in [:persona :message] ""))
      :active-character id
      :realtime/start id
      :dispatch [:persona/publish-greeting]})))

(rf/reg-event-fx
 :persona/publish-greeting
 (fn [{:keys [db]} _]
   (let [id (get-in db [:persona :created-owned :public_character :id])
         preview-token (get-in db [:persona :greeting :preview-token])]
     {:db (-> db
              (assoc-in [:persona :status] :publishing-greeting)
              (assoc-in [:persona :greeting :status] :publishing)
              (assoc-in [:persona :message] ""))
      :api {:promise (api/publish-character-greeting id preview-token)
            :success [:persona/greeting-publish-success]
            :failure [:persona/greeting-publish-failure]}})))

(rf/reg-event-fx
 :persona/greeting-publish-success
 (fn [{:keys [db]} [_ post]]
   (let [id (:active-character-id db)]
     {:db (-> db
              (assoc :route :feed)
              (update-in [:feed :items] #(vec (cons post (remove (fn [item]
                                                                   (= (:id item) (:id post))) %))))
              (assoc :persona {:draft nil :seed nil :status :success :message ""
                               :greeting (empty-greeting-state)
                               :created-owned nil :publish-after-preview false
                               :retry-needs-preview false})
              (assoc :announcement (tr db :persona.created_with_greeting)))
      :history/push (get route-paths :feed)
      :fx [[:api {:promise (api/notifications id nil)
                  :success [:notifications/success false]
                  :failure [:notifications/background-failure]}]
           [:api {:promise (api/conversations id nil)
                  :success [:messages/list-success false]
                  :failure [:messages/background-failure]}]]})))

(rf/reg-event-db
 :persona/greeting-publish-failure
 (fn [db [_ error]]
   (let [expired? (= "GREETING_PREVIEW_INVALID" (:code error))]
     (-> db
         (assoc-in [:persona :status] :greeting-error)
         (assoc-in [:persona :greeting :status] :error)
         (assoc-in [:persona :retry-needs-preview] expired?)
         (assoc-in [:persona :message]
                   (if expired?
                     (tr db :persona.greeting_expired)
                     (tr db :persona.greeting_publish_failed)))))))

(rf/reg-event-fx
 :persona/greeting-retry
 (fn [{:keys [db]} _]
   {:db (assoc-in db [:persona :message] "")
    :dispatch (if (get-in db [:persona :retry-needs-preview])
                [:persona/greeting-preview true]
                [:persona/publish-greeting])}))

(rf/reg-event-db
 :persona/create-failure
 (fn [db [_ error]]
   (-> db
       (assoc-in [:persona :status] :error)
       (assoc-in [:persona :message] (i18n/error-text (:locale db) error)))))

(rf/reg-event-fx
 :persona/save
 (fn [{:keys [db]} _]
   (let [character-id (get-in db [:persona :editing-id])
         draft (get-in db [:persona :draft])]
     (if (and (= :edit (get-in db [:persona :mode])) character-id)
       {:db (-> db
                (assoc-in [:persona :status] :loading)
                (assoc-in [:persona :message] ""))
        :api {:promise (api/update-character
                        character-id
                        (-> draft
                            (dissoc :handle)
                            (assoc :actor_character_id character-id)))
              :success [:persona/save-success]
              :failure [:persona/save-failure]}}
       {:db (assoc db :announcement (tr db :error.CHARACTER_NOT_OWNED))}))))

(rf/reg-event-fx
 :persona/save-success
 (fn [{:keys [db]} [_ owned]]
   (let [handle (get-in owned [:public_character :handle])]
     {:db (-> db
              (update-in [:me :owned_characters] replace-owned-persona owned)
              (assoc :persona (new-persona-state nil)
                     :announcement (tr db :persona.saved)))
      :dispatch [:profile/open handle]})))

(rf/reg-event-db
 :persona/save-failure
 (fn [db [_ error]]
   (-> db
       (assoc-in [:persona :status] :error)
       (assoc-in [:persona :message] (i18n/error-text (:locale db) error)))))

(rf/reg-event-db
 :persona/delete-open
 (fn [db [_ character-id]]
   (assoc db :persona-management
          {:delete-open? true :status :idle :error nil :target-id character-id})))

(rf/reg-event-db
 :persona/delete-cancel
 (fn [db _]
   (assoc db :persona-management
          {:delete-open? false :status :idle :error nil :target-id nil})))

(rf/reg-event-fx
 :persona/delete-confirm
 (fn [{:keys [db]} _]
   (if-let [character-id (get-in db [:persona-management :target-id])]
     {:db (assoc db :persona-management
                 {:delete-open? true :status :deleting :error nil
                  :target-id character-id})
      :api {:promise (api/delete-character character-id)
            :success [:persona/delete-success]
            :failure [:persona/delete-failure]}}
     {:db db})))

(rf/reg-event-fx
 :persona/delete-success
 (fn [{:keys [db]} _]
   (let [deleted-id (get-in db [:persona-management :target-id])
         remaining (->> (get-in db [:me :owned_characters])
                        (remove #(= deleted-id (get-in % [:public_character :id])))
                        vec)
         limit (or (get-in db [:me :character_limit]) 5)
         me (assoc (:me db)
                   :owned_characters remaining
                   :remaining_character_slots (max 0 (- limit (count remaining))))
         empty? (empty? remaining)
         next-db (-> db
                     (assoc :route :feed
                            :me me
                            :profile {:status :idle :handle nil :value nil :error nil}
                            :persona-management {:delete-open? false :status :success
                                                 :error nil :target-id nil}
                            :preferred-character-id
                            (when (not= deleted-id (:preferred-character-id db))
                              (:preferred-character-id db))
                            :following-ids #{}
                            :notifications {:status :idle :items [] :next-cursor nil
                                            :unread-count 0 :connected false
                                            :seen-events #{} :error nil}
                            :messages {:status :idle :items [] :next-cursor nil :active nil
                                       :detail-status :idle :text "" :error nil}
                            :announcement (tr db :persona.deleted)))]
     (cond-> {:db next-db
              :history/push (get route-paths :feed)
              :dispatch [:me/success me]}
       empty? (assoc :realtime/stop true)))))

(rf/reg-event-db
 :persona/delete-failure
 (fn [db [_ error]]
   (assoc db :persona-management
          {:delete-open? true :status :error :error error
           :target-id (get-in db [:persona-management :target-id])})))

;; Search and follows ---------------------------------------------------------

(rf/reg-event-db
 :search/set-query
 (fn [db [_ value]]
   (-> db
       (assoc-in [:search :query] value)
       (assoc-in [:search :status] :idle)
       (assoc-in [:search :items] [])
       (assoc-in [:search :next-cursor] nil)
       (assoc-in [:search :submitted-query] nil)
       (assoc-in [:search :submitted-type] nil)
       (assoc-in [:search :error] nil))))

(rf/reg-event-fx
 :search/set-type
 (fn [{:keys [db]} [_ type]]
   {:db (-> db
            (assoc-in [:search :type] type)
            (assoc-in [:search :status] :idle)
            (assoc-in [:search :items] [])
            (assoc-in [:search :next-cursor] nil)
            (assoc-in [:search :error] nil))
    :dispatch [:search/submit]}))

(rf/reg-event-fx
 :search/submit
 (fn [{:keys [db]} _]
   (let [query (get-in db [:search :query])
         type (get-in db [:search :type])]
     (if (< (grapheme/length query) 2)
       {:db (-> db
                (assoc-in [:search :status] :idle)
                (assoc-in [:search :items] [])
                (assoc-in [:search :submitted-query] nil)
                (assoc-in [:search :submitted-type] nil)
                (assoc-in [:search :error] nil))}
       {:db (-> db (assoc-in [:search :status] :loading)
                (assoc-in [:search :items] [])
                (assoc-in [:search :next-cursor] nil)
                (assoc-in [:search :submitted-query] query)
                (assoc-in [:search :submitted-type] type)
                (assoc-in [:search :error] nil))
        :api {:promise (api/search query type (:active-character-id db) nil)
              :success [:search/success false query type]
              :failure [:search/failure query type]}}))))

(rf/reg-event-db
 :search/success
 (fn [db [_ append? query type result]]
   (if (and (= query (get-in db [:search :submitted-query]))
            (= type (get-in db [:search :submitted-type])))
     (-> db (assoc-in [:search :status] :success)
         (update-in [:search :items]
                    #(if append? (vec (concat % (:items result))) (:items result)))
         (assoc-in [:search :next-cursor] (:next_cursor result))
         (assoc-in [:search :error] nil))
     db)))

(rf/reg-event-fx
 :search/load-more
 (fn [{:keys [db]} _]
   (let [{:keys [query type next-cursor]} (:search db)]
     (if-not next-cursor
       {:db db}
       {:db (assoc-in db [:search :status] :loading-more)
        :api {:promise (api/search query type (:active-character-id db) next-cursor)
              :success [:search/success true query type]
              :failure [:search/failure query type]}}))))

(rf/reg-event-db
 :search/failure
 (fn [db [_ query type error]]
   (if (and (= query (get-in db [:search :submitted-query]))
            (= type (get-in db [:search :submitted-type])))
     (-> db (assoc-in [:search :status] :error)
         (assoc-in [:search :error] error))
     db)))

(rf/reg-event-fx
 :follow/toggle
 (fn [{:keys [db]} [_ target-id following?]]
   (let [actor (:active-character-id db)]
     (if-not actor
       {:db (assoc db :announcement (tr db :auth.continue_google))}
       {:db (assoc-in db [:search :follow-pending target-id] true)
        :api {:promise ((if following? api/unfollow api/follow) target-id actor)
              :success [:follow/success target-id (not following?)]
              :failure [:follow/failure target-id]}}))))

(rf/reg-event-db
 :follow/success
 (fn [db [_ target-id following? _result]]
   (let [profile? (= target-id (get-in db [:profile :value :id]))
         delta (if following? 1 -1)]
     (cond-> (-> db
                 (update-in [:search :follow-pending] dissoc target-id)
                 (update :following-ids (fnil (if following? conj disj) #{}) target-id)
                 (update-in [:search :items]
                            #(mapv (fn [character]
                                     (if (= target-id (:id character))
                                       (assoc character :viewer_following following?)
                                       character)) %)))
       profile?
       (update-in [:profile :value :counts :followers]
                  #(max 0 (+ (or % 0) delta)))))))

(rf/reg-event-db
 :follow/failure
 (fn [db [_ target-id error]]
   (-> db
       (update-in [:search :follow-pending] dissoc target-id)
       (assoc :announcement (i18n/error-text (:locale db) error)))))

(rf/reg-event-fx
 :following/success
 (fn [{:keys [db]} [_ append? actor-id result]]
   (if-not (= actor-id (:active-character-id db))
     {:db db}
     (let [page-ids (into #{} (map :id) (:items result))
           next-db (update db :following-ids
                           (if append? into (fn [_ values] values)) page-ids)]
       (cond-> {:db next-db}
         (:next_cursor result)
         (assoc :api {:promise (api/connections actor-id :following actor-id
                                                (:next_cursor result))
                      :success [:following/success true actor-id]
                      :failure [:following/failure]}))))))

(rf/reg-event-db :following/failure (fn [db _] db))

;; Notifications and realtime ------------------------------------------------

(rf/reg-event-db
 :notifications/success
 (fn [db [_ append? result]]
   (let [existing (if append? (get-in db [:notifications :items]) [])]
     (-> db
         (assoc-in [:notifications :status] :success)
         (assoc-in [:notifications :items] (vec (concat existing (:items result))))
         (assoc-in [:notifications :next-cursor] (:next_cursor result))
         (assoc-in [:notifications :unread-count] (:unread_count result))
         (assoc-in [:notifications :error] nil)))))

(rf/reg-event-db
 :notifications/failure
 (fn [db [_ error]]
   (-> db (assoc-in [:notifications :status] :error)
       (assoc-in [:notifications :error] error))))

(rf/reg-event-db :notifications/background-failure (fn [db _] db))

(rf/reg-event-fx
 :notifications/load-more
 (fn [{:keys [db]} _]
   (let [cursor (get-in db [:notifications :next-cursor])]
     (if-not (and cursor (:active-character-id db))
       {:db db}
       {:db (assoc-in db [:notifications :status] :loading-more)
        :api {:promise (api/notifications (:active-character-id db) cursor)
              :success [:notifications/success true]
              :failure [:notifications/failure]}}))))

(rf/reg-event-fx
 :notifications/refresh
 (fn [{:keys [db]} _]
   (if-not (:active-character-id db)
     {:db db}
     {:api {:promise (api/notifications (:active-character-id db) nil)
            :success [:notifications/success false]
            :failure [:notifications/failure]}})))

(rf/reg-event-fx
 :notifications/mark
 (fn [{:keys [db]} [_ notification]]
   (let [open-effect
         (case (:resource_type notification)
           "post" [:notification/open-post (:resource_id notification)]
           "comment" [:notification/open-comment (:post_id notification)]
           "message" [:navigate :messages]
           "character" (when-let [handle (get-in notification [:actor :handle])]
                         [:profile/open handle])
           nil)]
     (cond-> {:db db}
       (and (nil? (:read_at notification)) (:active-character-id db))
       (assoc :api {:promise (api/mark-notification (:id notification)
                                                    (:active-character-id db))
                    :success [:notifications/mark-success (:id notification)]
                    :failure [:notifications/failure]})
       open-effect (assoc :dispatch open-effect)))))

(rf/reg-event-db
 :notifications/mark-success
 (fn [db [_ id result]]
   (-> db
       (update-in [:notifications :items]
                  #(mapv (fn [value]
                           (if (= id (:id value)) (assoc value :read_at (:read_at result)) value)) %))
       (assoc-in [:notifications :unread-count] (:unread_count result)))))

(rf/reg-event-fx
 :notifications/mark-all
 (fn [{:keys [db]} _]
   (if-not (:active-character-id db)
     {:db db}
     {:api {:promise (api/mark-all-notifications
                      (:active-character-id db) (.toISOString (js/Date.)))
            :success [:notifications/mark-all-success]
            :failure [:notifications/failure]}})))

(rf/reg-event-db
 :notifications/mark-all-success
 (fn [db [_ result]]
   (-> db
       (update-in [:notifications :items]
                  #(mapv (fn [value] (assoc value :read_at (or (:read_at value) "read"))) %))
       (assoc-in [:notifications :unread-count] (:unread_count result)))))

(rf/reg-event-fx
 :notification/open-post
 (fn [{:keys [db]} [_ post-id]]
   {:api {:promise (api/post post-id (:active-character-id db))
          :success [:thread/open]
          :failure [:notifications/failure]}}))

(rf/reg-event-fx
 :notification/open-comment
 (fn [{:keys [db]} [_ post-id]]
   (if post-id
     {:api {:promise (api/post post-id (:active-character-id db))
            :success [:thread/open]
            :failure [:notifications/failure]}}
     {:db (assoc db :announcement (tr db :common.unavailable))})))

(rf/reg-event-db :realtime/connected
                 (fn [db _] (assoc-in db [:notifications :connected] true)))

(rf/reg-event-fx
 :realtime/disconnected
 (fn [{:keys [db]} _]
   {:db (assoc-in db [:notifications :connected] false)
    :dispatch-later {:ms 15000 :dispatch [:notifications/refresh]}}))

(rf/reg-event-fx
 :realtime/hint
 (fn [{:keys [db]} [_ event]]
   (let [id (:event_id event)
         seen (get-in db [:notifications :seen-events])]
     (if (contains? seen id)
       {:db db}
       (let [seen (->> (conj seen id) (take-last 200) set)
             dm? (contains? #{"dm.message.created" "dm.message.read"} (:type event))]
         {:db (assoc-in db [:notifications :seen-events] seen)
          :dispatch-n (cond-> [[:notifications/refresh]]
                        dm? (conj [:messages/refresh]))})))))

;; Direct messages -----------------------------------------------------------

(rf/reg-event-db
 :messages/list-success
 (fn [db [_ append? result]]
   (let [existing (if append? (get-in db [:messages :items]) [])]
     (-> db (assoc-in [:messages :status] :success)
         (assoc-in [:messages :items] (vec (concat existing (:items result))))
         (assoc-in [:messages :next-cursor] (:next_cursor result))
         (assoc-in [:messages :error] nil)))))

(rf/reg-event-db
 :messages/list-failure
 (fn [db [_ error]]
   (-> db (assoc-in [:messages :status] :error)
       (assoc-in [:messages :error] error))))

(rf/reg-event-db :messages/background-failure (fn [db _] db))

(rf/reg-event-db
 :messages/detail-failure
 (fn [db [_ error]]
   (-> db (assoc-in [:messages :detail-status] :error)
       (assoc-in [:messages :error] error))))

(rf/reg-event-db
 :messages/action-failure
 (fn [db [_ error]]
   (-> db (assoc-in [:messages :detail-status] :success)
       (assoc-in [:messages :error] error)
       (assoc :announcement (i18n/error-text (:locale db) error)))))

(rf/reg-event-fx
 :messages/refresh
 (fn [{:keys [db]} _]
   (if-not (:active-character-id db)
     {:db db}
     {:fx (cond->
            [[:api {:promise (api/conversations (:active-character-id db) nil)
                    :success [:messages/list-success false]
                    :failure [:messages/background-failure]}]]
            (get-in db [:messages :active :conversation :id])
            (conj [:api {:promise (api/conversation
                                   (get-in db [:messages :active :conversation :id])
                                   (:active-character-id db) nil)
                         :success [:messages/detail-success false]
                         :failure [:messages/background-failure]}]))})))

(rf/reg-event-fx
 :messages/list-more
 (fn [{:keys [db]} _]
   (let [cursor (get-in db [:messages :next-cursor])]
     (if-not cursor
       {:db db}
       {:db (assoc-in db [:messages :status] :loading-more)
        :api {:promise (api/conversations (:active-character-id db) cursor)
              :success [:messages/list-success true]
              :failure [:messages/list-failure]}}))))

(rf/reg-event-fx
 :messages/create
 (fn [{:keys [db]} [_ recipient-id]]
   {:db (assoc-in db [:messages :status] :creating)
    :api {:promise (api/create-conversation (:active-character-id db) recipient-id)
          :success [:messages/create-success]
          :failure [:messages/action-failure]}}))

(rf/reg-event-fx
 :messages/create-success
 (fn [{:keys [db]} [_ result]]
   (-> (routes/navigation-effect db :messages)
       (update :db assoc-in [:messages :status] :success)
       (assoc :fx [[:dispatch [:messages/open (:id result)]]
                   [:api {:promise (api/conversations (:active-character-id db) nil)
                          :success [:messages/list-success false]
                          :failure [:messages/background-failure]}]]))))

(rf/reg-event-fx
 :messages/open
 (fn [{:keys [db]} [_ conversation-id]]
   (-> (routes/navigation-effect db :messages)
       (update :db assoc-in [:messages :detail-status] :loading)
       (assoc :api {:promise (api/conversation conversation-id (:active-character-id db) nil)
                    :success [:messages/detail-success false]
                    :failure [:messages/detail-failure]}))))

(rf/reg-event-db
 :messages/close
 (fn [db _]
   (-> db
       (assoc-in [:messages :active] nil)
       (assoc-in [:messages :detail-status] :idle)
       (assoc-in [:messages :error] nil))))

(rf/reg-event-fx
 :messages/detail-more
 (fn [{:keys [db]} _]
   (let [conversation-id (get-in db [:messages :active :conversation :id])
         cursor (get-in db [:messages :active :messages :next_cursor])]
     (if-not (and conversation-id cursor)
       {:db db}
       {:db (assoc-in db [:messages :detail-status] :loading-more)
        :api {:promise (api/conversation conversation-id (:active-character-id db) cursor)
              :success [:messages/detail-success true]
              :failure [:messages/detail-failure]}}))))

(rf/reg-event-fx
 :messages/detail-success
 (fn [{:keys [db]} [_ append? result]]
   (let [old (if append? (get-in db [:messages :active :messages :items]) [])
         result (if append?
                  (update-in result [:messages :items] #(vec (concat % old)))
                  result)
         messages (get-in result [:messages :items])
         last-message (peek messages)]
     (cond-> {:db (-> db (assoc-in [:messages :detail-status] :success)
                      (assoc-in [:messages :active] result))}
       last-message
       (assoc :api {:promise (api/mark-conversation-read
                              (get-in result [:conversation :id])
                              (:active-character-id db) (:id last-message))
                    :success [:messages/read-success]
                    :failure [:messages/read-failure]})))))

(rf/reg-event-db
 :messages/read-success
 (fn [db [_ result]]
   (let [conversation-id (:conversation_id result)]
     (update-in db [:messages :items]
                #(mapv (fn [value]
                         (if (= conversation-id (:id value))
                           (assoc value :unread_count 0)
                           value)) %)))))
(rf/reg-event-db :messages/read-failure (fn [db _] db))
(rf/reg-event-db :messages/set-text (fn [db [_ value]] (assoc-in db [:messages :text] value)))

(rf/reg-event-fx
 :messages/send
 (fn [{:keys [db]} _]
   (let [text (get-in db [:messages :text])
         conversation-id (get-in db [:messages :active :conversation :id])]
     (if (or (empty? text) (> (grapheme/length text) 180) (nil? conversation-id))
       {:db (assoc-in db [:messages :error]
                      {:code "MESSAGE_INVALID" :message (tr db :messages.invalid)})}
       {:db (-> db (assoc-in [:messages :detail-status] :sending)
                (assoc-in [:messages :error] nil))
        :api {:promise (api/send-message conversation-id (:active-character-id db) text)
              :success [:messages/send-success]
              :failure [:messages/action-failure]}}))))

(rf/reg-event-db
 :messages/send-success
 (fn [db [_ message]]
   (let [conversation-id (:conversation_id message)]
     (-> db
         (update-in [:messages :active :messages :items] (fnil conj []) message)
         (update-in [:messages :items]
                    #(mapv (fn [value]
                             (if (= conversation-id (:id value))
                               (assoc value :preview (:text message)
                                      :last_message_at (:created_at message))
                               value)) %))
         (assoc-in [:messages :text] "")
         (assoc-in [:messages :detail-status] :success)
         (assoc :announcement (tr db :messages.sent))))))

(rf/reg-event-fx
 :messages/delete
 (fn [{:keys [db]} [_ message-id]]
    {:api {:promise (api/delete-message message-id (:active-character-id db))
          :success [:messages/delete-success message-id]
          :failure [:messages/action-failure]}}))

(rf/reg-event-fx
 :messages/delete-success
 (fn [{:keys [db]} [_ message-id _]]
   {:db (-> (message-state/remove-visible-message db message-id)
            (assoc :announcement (tr db :messages.deleted)))
    :dispatch [:messages/refresh]}))

;; Media upload ---------------------------------------------------------------

(defn- media-path [purpose]
  (if (= purpose "avatar") [:media :avatar] [:media (keyword purpose)]))

(defn- update-media-item [db purpose local-id f]
  (if (= purpose "avatar")
    (update-in db [:media :avatar] #(when % (f %)))
    (update-in db (media-path purpose)
               #(mapv (fn [item] (if (= local-id (:local-id item)) (f item) item)) %))))

(defn- media-item [db purpose local-id]
  (if (= purpose "avatar")
    (get-in db [:media :avatar])
    (some #(when (= local-id (:local-id %)) %)
          (get-in db (media-path purpose)))))

(rf/reg-event-fx
 :media/select
 (fn [{:keys [db]} [_ purpose files requested-actor]]
   (let [actor (or requested-actor (:active-character-id db))
         actor-public (owned-public-by-id db actor)
         current (if (= purpose "avatar") [] (get-in db (media-path purpose)))
         maximum (if (= purpose "avatar") 1 (- 4 (count current)))
         candidates (->> files (take maximum) vec)
         valid? #(and (contains? #{"image/jpeg" "image/png" "image/webp"} (.-type %))
                      (<= (.-size %) (* 10 1024 1024)))
         selected (->> candidates (filter valid?)
                       vec)
         items (if actor
                 (mapv (fn [file]
                         {:local-id (str (random-uuid)) :name (.-name file)
                          :preview (.createObjectURL js/URL file)
                          :status :uploading :progress 0 :file file
                          :actor actor :language (:output_language actor-public)})
                       selected)
                 [])
         old-avatar (when (= purpose "avatar") (get-in db [:media :avatar]))
         next-db (if (= purpose "avatar")
                   (assoc-in db [:media :avatar] (first items))
                   (update-in db (media-path purpose) into items))]
     (cond
       (nil? actor) {:db (assoc db :announcement (tr db :auth.continue_google))}
       (and (seq candidates) (empty? items))
       {:db (assoc db :announcement (tr db :media.invalid_selection))}
       :else
       (do
         (when-let [url (:preview old-avatar)] (.revokeObjectURL js/URL url))
         {:db (assoc next-db :announcement
                     (media/selection-announcement
                      candidates valid? (tr db :media.invalid_selection)))
          :fx (vec
               (concat
                (when (:id old-avatar)
                  [[:api {:promise (api/delete-media (:id old-avatar) actor)
                          :success [:media/removed]
                          :failure [:media/remove-failure]}]])
                (map (fn [item]
                       [:media/upload {:file (:file item) :actor (:actor item) :purpose purpose
                                       :language (:language item)
                                       :local-id (:local-id item)}])
                     items)))})))))

(rf/reg-event-db
 :media/progress
 (fn [db [_ purpose local-id progress]]
   (update-media-item db purpose local-id #(assoc % :progress progress))))

(rf/reg-event-fx
 :media/accepted
 (fn [{:keys [db]} [_ purpose local-id result]]
   {:db (update-media-item db purpose local-id
                           #(-> % (dissoc :file) (merge result) (assoc :status :processing :progress 100)))
    :dispatch-later {:ms 800 :dispatch [:media/poll purpose local-id (:id result) 0]}}))

(rf/reg-event-fx
 :media/poll
 (fn [{:keys [db]} [_ purpose local-id media-id attempt]]
   (let [actor (:actor (media-item db purpose local-id))]
    (if (> attempt 90)
     {:dispatch [:media/failure purpose local-id {:code "MEDIA_TIMEOUT"}]}
     {:api {:promise (api/media-status media-id actor)
            :success [:media/status purpose local-id attempt]
            :failure [:media/failure purpose local-id]}}))))

(rf/reg-event-fx
 :media/status
 (fn [{:keys [db]} [_ purpose local-id attempt result]]
   (let [staged (media-item db purpose local-id)]
     (cond
       (nil? staged)
       {:db db}

       (= "ready" (:status result))
       (let [actor (:actor staged)
             ready-db (update-media-item
                       db purpose local-id
                       #(-> % (merge result {:status :ready}) (dissoc :preview)))]
         (cond-> {:db ready-db :media/revoke staged}
           (= purpose "avatar")
           (assoc :api {:promise (api/update-character
                                  actor
                                  {:actor_character_id actor
                                   :avatar_media_id (:id result)})
                        :success [:media/avatar-applied]
                        :failure [:media/failure purpose local-id]})))

       (contains? #{"rejected" "blocked" "failed"} (:status result))
       {:dispatch [:media/failure purpose local-id {:code (:error_code result)}]}

       :else
       {:db (update-media-item db purpose local-id #(merge % result {:status :processing}))
        :dispatch-later {:ms (min 3000 (+ 800 (* attempt 100)))
                         :dispatch [:media/poll purpose local-id (:id result) (inc attempt)]}}))))

(rf/reg-event-db
 :media/failure
 (fn [db [_ purpose local-id error]]
   (update-media-item db purpose local-id
                      #(assoc % :status :failed :error (i18n/error-text (:locale db) error)))))

(rf/reg-event-fx
 :media/remove
 (fn [{:keys [db]} [_ purpose local-id]]
   (let [item (media-item db purpose local-id)
         actor (or (:actor item) (:active-character-id db))
         remove-db (if (= purpose "avatar")
                     (assoc-in db [:media :avatar] nil)
                     (update-in db (media-path purpose)
                                #(vec (remove (fn [value] (= local-id (:local-id value))) %))))]
     (when-let [url (:preview item)] (.revokeObjectURL js/URL url))
     (if (:id item)
       {:db remove-db
        :api {:promise (api/delete-media (:id item) actor)
              :success [:media/removed]
              :failure [:media/remove-failure]}}
       {:db remove-db}))))

(rf/reg-event-db :media/removed (fn [db _] db))
(rf/reg-event-db :media/remove-failure
                 (fn [db [_ error]] (assoc db :announcement (i18n/error-text (:locale db) error))))

(rf/reg-event-fx
 :media/avatar-applied
 (fn [{:keys [db]} [_ owned]]
   (let [public (:public_character owned)
         id (:id public)
         staged (get-in db [:media :avatar])]
     {:db (cond-> (-> db
                      (assoc-in [:media :avatar] nil)
                      (update-in [:me :owned_characters]
                                 #(mapv (fn [value]
                                          (if (= id (get-in value [:public_character :id]))
                                            owned value)) %)))
            (= id (get-in db [:profile :value :id]))
            (update-in [:profile :value] merge public))
      :media/revoke staged})))

(rf/reg-event-fx
 :media/avatar-remove
 (fn [{:keys [db]} [_ requested-actor]]
   (let [actor (or requested-actor (:active-character-id db))]
    {:api {:promise (api/update-character
                    actor
                    {:actor_character_id actor :avatar_media_id nil})
          :success [:media/avatar-removed]
          :failure [:media/remove-failure]}})))

(rf/reg-event-fx
 :media/avatar-removed
 (fn [{:keys [db]} [_ owned]]
   {:db (assoc-in db [:media :avatar] nil)
    :dispatch [:media/avatar-applied owned]}))

;; Anonymous browser state ----------------------------------------------------

(rf/reg-event-fx
 :guest/start
 (fn [{:keys [db]} [_ return-route]]
   (let [persona (get-in db [:anonymous :persona])
         return-route (if (or (nil? return-route) (= :persona return-route))
                        :feed return-route)]
     {:db (-> db
              (assoc :route :persona)
              (assoc-in [:anonymous :draft] persona)
              (assoc-in [:anonymous :draft-seed] (get-in db [:anonymous :seed]))
              (assoc-in [:anonymous :return-route] return-route)
              (assoc-in [:anonymous :message] ""))
      :history/push (get route-paths :persona)
      :anonymous-sigil persona})))

(rf/reg-event-fx
 :anonymous/set
 (fn [{:keys [db]} [_ path value]]
   (let [next (assoc-in db (into [:anonymous :draft] path) value)]
     {:db next
      :anonymous-sigil (get-in next [:anonymous :draft])})))

(rf/reg-event-fx
 :anonymous/toggle-choice
 (fn [{:keys [db]} [_ path id minimum maximum]]
   (let [full-path (into [:anonymous :draft] path)
         next (update-in db full-path choice/toggle-ref id minimum maximum)]
     {:db next
      :anonymous-sigil (get-in next [:anonymous :draft])})))

(rf/reg-event-fx
 :anonymous/randomize
 (fn [{:keys [db]} _]
   (let [persona (randomizer/random-guest
                  (or (get-in db [:anonymous :draft :output_language]) (:locale db)))
         next (-> db
                  (assoc-in [:anonymous :draft] persona)
                  (assoc-in [:anonymous :token] nil)
                  (assoc-in [:anonymous :message] (tr db :anonymous.rerolled))
                  (assoc :announcement (tr db :anonymous.rerolled)))]
     {:db next
      :anonymous-sigil persona})))

(rf/reg-event-db
 :anonymous/sigil-ready
 (fn [db [_ draft seed]]
   (cond-> db
     (= draft (get-in db [:anonymous :draft])) (assoc-in [:anonymous :draft-seed] seed)
     (= draft (get-in db [:anonymous :persona])) (assoc-in [:anonymous :seed] seed))))

(rf/reg-event-fx
 :anonymous/save
 (fn [{:keys [db]} _]
   (let [draft (get-in db [:anonymous :draft])
         return-route (get-in db [:anonymous :return-route] :feed)]
     (if-not (guest-persona-ready? draft)
       {:db (assoc-in db [:anonymous :message] (tr db :anonymous.invalid))}
       (let [next (-> db
                      (assoc :route return-route)
                      (assoc-in [:anonymous :configured] true)
                      (assoc-in [:anonymous :persona] draft)
                      (assoc-in [:anonymous :seed] (get-in db [:anonymous :draft-seed]))
                      (assoc-in [:anonymous :message] "")
                      (assoc :announcement (tr db :anonymous.saved)))]
         {:db next
          :storage/anonymous (:anonymous next)
          :history/push (get route-paths :feed)})))))

(rf/reg-event-fx
 :anonymous/cancel
 (fn [{:keys [db]} _]
   (let [return-route (get-in db [:anonymous :return-route] :feed)
         next (-> db
                  (assoc :route return-route)
                  (assoc-in [:anonymous :draft] (get-in db [:anonymous :persona]))
                  (assoc-in [:anonymous :draft-seed] (get-in db [:anonymous :seed]))
                  (assoc-in [:anonymous :message] ""))]
     {:db next :history/push (get route-paths :feed)})))

(rf/reg-event-db :anonymous/token
                 (fn [db [_ token]] (assoc-in db [:anonymous :token] token)))

(rf/reg-event-fx
 :anonymous/session-success
 (fn [{:keys [db]} [_ continue-event result]]
   (let [next (-> db (assoc-in [:anonymous :session] result)
                  (assoc-in [:anonymous :status] :ready))]
     {:db next :storage/anonymous (:anonymous next) :dispatch [continue-event]})))

(rf/reg-event-fx
 :anonymous/delete
 (fn [{:keys [db]} [_ comment-id]]
   (let [session (get-in db [:anonymous :session])]
     {:api {:promise (api/delete-anonymous-comment
                      comment-id (:anonymous_session_id session) (:deletion_token session))
            :success [:anonymous/delete-success comment-id]
            :failure [:comment/failure]}})))

(rf/reg-event-fx
 :anonymous/delete-success
 (fn [{:keys [db]} [_ comment-id result]]
   (let [post-id (get-in db [:thread :post :id])
         remove-or-tombstone
         (fn [value]
           (when value
             (if (= comment-id (:id value))
               (when (:tombstone result) (assoc value :status "deleted" :text nil)) value)))
         next (-> db
                  (update-in [:thread :items]
                             #(->> %
                                   (keep (fn [thread]
                                           (when-let [root (remove-or-tombstone (:root thread))]
                                             (assoc thread :root root
                                                    :replies (->> (:replies thread)
                                                                  (keep remove-or-tombstone) vec)))))
                                   vec))
                  (update-in [:anonymous :owned-comments] disj comment-id)
                  (post-state/adjust-comment-count post-id -1)
                  (assoc :announcement (tr db :comment.deleted_success)))]
     {:db next :storage/anonymous (:anonymous next)})))

(rf/reg-sub :db (fn [db _] db))
