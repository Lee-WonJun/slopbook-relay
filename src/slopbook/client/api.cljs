(ns slopbook.client.api
  (:require [clojure.string :as str]))

(defonce access-token (atom nil))
(defonce dev-auth? (atom false))
(def ai-request-timeout-ms 15000)

(defn- coded-error [code]
  (doto (js/Error. code)
    (aset "code" code)))

(defn configure! [config]
  (reset! dev-auth? (true? (:dev_auth config))))

(defn set-development-auth! [enabled?]
  (reset! dev-auth? enabled?))

(defn set-access-token! [token]
  (reset! access-token token))

(defn idempotency-key []
  (if (some? (.-randomUUID js/crypto))
    (.randomUUID js/crypto)
    (str (random-uuid))))

(defn request-headers [method token development-auth?]
  (let [write? (contains? #{"POST" "PUT" "PATCH" "DELETE"} method)]
    (cond-> {"accept" "application/json"}
      token (assoc "authorization" (str "Bearer " token))
      (and (nil? token) development-auth?) (assoc "x-slopbook-dev-account" "demo-account")
      write? (assoc "content-type" "application/json"
                    "idempotency-key" (idempotency-key)))))

(defn- parse-response [response]
  (-> (.text response)
      (.then (fn [body]
               (let [payload (if (seq body)
                               (try
                                 (js->clj (.parse js/JSON body) :keywordize-keys true)
                                 (catch :default _
                                   (throw (coded-error "REQUEST_FAILED"))))
                               nil)]
                 (if (.-ok response)
                   payload
                   (throw (coded-error
                           (or (get-in payload [:error :code]) "REQUEST_FAILED")))))))))

(defn request-options [method headers body]
  (cond-> {:method method :headers headers}
    (= "GET" method) (assoc :cache "no-store")
    (some? body) (assoc :body (.stringify js/JSON (clj->js body)))))

(defn request-path [method path nonce]
  (if (= "GET" method)
    (str path (if (str/includes? path "?") "&" "?") "_cb=" nonce)
    path))

(defn ai-request? [method path]
  (and (= "POST" method)
       (or (= "/api/v1/posts" path)
           (= "/api/v1/character-greetings/preview" path)
           (boolean (re-find #"/comments$" path))
           (boolean (re-find #"/reaction$" path))
           (boolean (re-find #"/messages$" path)))))

(defn- fetch-json [url options timeout-ms]
  (if-not timeout-ms
    (-> (js/fetch url (clj->js options))
        (.then parse-response))
    (let [controller (js/AbortController.)
          js-options (clj->js options)
          timeout-id (js/setTimeout #(.abort controller) timeout-ms)]
      (aset js-options "signal" (.-signal controller))
      (-> (js/fetch url js-options)
          (.then parse-response)
          (.catch (fn [error]
                    (if (= "AbortError" (.-name error))
                      (throw (coded-error "AI_TIMEOUT"))
                      (throw error))))
          (.finally #(js/clearTimeout timeout-id))))))

(defn request
  ([method path] (request method path nil))
  ([method path body]
   (let [headers (request-headers method @access-token @dev-auth?)
         options (request-options method headers body)
         url (request-path method path (.now js/Date))
         timeout-ms (when (ai-request? method path) ai-request-timeout-ms)]
     (fetch-json url options timeout-ms))))

(defn feed-for
  ([mode actor-character-id] (feed-for mode actor-character-id nil))
  ([mode actor-character-id cursor]
   (request "GET" (str "/api/v1/feed?mode=" (name mode)
                       (when actor-character-id
                         (str "&actor_character_id=" (js/encodeURIComponent actor-character-id)))
                       (when cursor
                         (str "&cursor=" (js/encodeURIComponent cursor)))))))
(defn feed [mode] (feed-for mode nil nil))
(defn client-config [] (request "GET" "/api/v1/config"))
(defn status [] (request "GET" "/api/v1/status"))
(defn me [] (request "GET" "/api/v1/me"))
(defn delete-account [] (request "DELETE" "/api/v1/me" {:confirm true}))
(defn create-post [payload] (request "POST" "/api/v1/posts" payload))
(defn post
  ([id] (post id nil))
  ([id actor-character-id]
   (request "GET" (str "/api/v1/posts/" (js/encodeURIComponent id)
                       (when actor-character-id
                         (str "?actor_character_id="
                              (js/encodeURIComponent actor-character-id)))))))
(defn comments
  ([post-id] (comments post-id nil))
  ([post-id cursor] (comments post-id cursor nil))
  ([post-id cursor actor-character-id]
   (request "GET" (str "/api/v1/posts/" (js/encodeURIComponent post-id) "/comments"
                       (when (or cursor actor-character-id) "?")
                       (when cursor (str "cursor=" (js/encodeURIComponent cursor)))
                       (when (and cursor actor-character-id) "&")
                       (when actor-character-id
                         (str "actor_character_id=" (js/encodeURIComponent actor-character-id)))))))
(defn create-comment [post-id payload]
  (request "POST" (str "/api/v1/posts/" (js/encodeURIComponent post-id) "/comments") payload))
(defn delete-comment [comment-id actor-character-id]
  (request "DELETE" (str "/api/v1/comments/" (js/encodeURIComponent comment-id))
           {:actor_character_id actor-character-id}))
(defn anonymous-session [] (request "POST" "/api/v1/anonymous-sessions" {}))
(defn create-anonymous-comment [post-id payload]
  (request "POST" (str "/api/v1/posts/" (js/encodeURIComponent post-id) "/comments") payload))
(defn delete-anonymous-comment [comment-id session-id deletion-token]
  (let [headers (assoc (request-headers "DELETE" @access-token @dev-auth?)
                       "x-anonymous-deletion-token" deletion-token)
        options (request-options "DELETE" headers {:anonymous_session_id session-id})]
    (fetch-json (str "/api/v1/comments/" (js/encodeURIComponent comment-id) "/anonymous")
                options
                nil)))
(defn toggle-reaction [target-type target-id actor-character-id]
  (request "POST"
           (str "/api/v1/targets/" (name target-type) "/"
                (js/encodeURIComponent target-id) "/reaction")
           {:actor_character_id actor-character-id}))

(defn toggle-repost [post-id actor-character-id]
  (request "POST" (str "/api/v1/posts/" (js/encodeURIComponent post-id) "/repost")
           {:actor_character_id actor-character-id}))
(defn create-character [payload] (request "POST" "/api/v1/characters" payload))
(defn delete-character [character-id]
  (request "DELETE" (str "/api/v1/characters/" (js/encodeURIComponent character-id))
           {:actor_character_id character-id}))
(defn preview-character-greeting [payload]
  (request "POST" "/api/v1/character-greetings/preview" payload))
(defn publish-character-greeting [actor-character-id preview-token]
  (request "POST" "/api/v1/character-greetings"
           {:actor_character_id actor-character-id :preview_token preview-token}))
(defn profile-path
  ([handle cursor] (profile-path handle cursor nil))
  ([handle cursor actor-character-id]
   (str "/api/v1/characters/" (js/encodeURIComponent handle)
        (when (or cursor actor-character-id) "?")
        (when cursor (str "cursor=" (js/encodeURIComponent cursor)))
        (when (and cursor actor-character-id) "&")
        (when actor-character-id
          (str "actor_character_id=" (js/encodeURIComponent actor-character-id))))))
(defn profile
  ([handle] (profile handle nil nil))
  ([handle cursor] (profile handle cursor nil))
  ([handle cursor actor-character-id]
   (request "GET" (profile-path handle cursor actor-character-id))))

(defn search [query type actor-character-id cursor]
  (request "GET"
           (str "/api/v1/search?q=" (js/encodeURIComponent query)
                "&type=" (name type)
                (when actor-character-id
                  (str "&actor_character_id=" (js/encodeURIComponent actor-character-id)))
                (when cursor (str "&cursor=" (js/encodeURIComponent cursor))))))

(defn follow [target-id actor-character-id]
  (request "PUT" (str "/api/v1/characters/" (js/encodeURIComponent target-id) "/follow")
           {:actor_character_id actor-character-id}))

(defn unfollow [target-id actor-character-id]
  (request "DELETE" (str "/api/v1/characters/" (js/encodeURIComponent target-id) "/follow")
           {:actor_character_id actor-character-id}))

(defn connections
  ([character-id type actor-character-id]
   (connections character-id type actor-character-id nil))
  ([character-id type actor-character-id cursor]
  (request "GET"
           (str "/api/v1/characters/" (js/encodeURIComponent character-id) "/" (name type)
                (when (or actor-character-id cursor) "?")
                (when actor-character-id
                  (str "actor_character_id=" (js/encodeURIComponent actor-character-id)))
                (when (and actor-character-id cursor) "&")
                (when cursor (str "cursor=" (js/encodeURIComponent cursor)))))))

(defn notifications [actor-character-id cursor]
  (request "GET" (str "/api/v1/notifications?actor_character_id="
                      (js/encodeURIComponent actor-character-id)
                      (when cursor (str "&cursor=" (js/encodeURIComponent cursor))))))

(defn mark-notification [notification-id actor-character-id]
  (request "PATCH" (str "/api/v1/notifications/" (js/encodeURIComponent notification-id))
           {:actor_character_id actor-character-id :read true}))

(defn mark-all-notifications [actor-character-id before]
  (request "POST" "/api/v1/notifications/read-all"
           {:actor_character_id actor-character-id :before before}))

(defn websocket-ticket [actor-character-id]
  (request "POST" "/api/v1/ws-tickets" {:actor_character_id actor-character-id}))

(defn conversations [actor-character-id cursor]
  (request "GET" (str "/api/v1/conversations?actor_character_id="
                      (js/encodeURIComponent actor-character-id)
                      (when cursor (str "&cursor=" (js/encodeURIComponent cursor))))))

(defn create-conversation [actor-character-id recipient-character-id]
  (request "POST" "/api/v1/conversations"
           {:actor_character_id actor-character-id
            :recipient_character_id recipient-character-id}))

(defn conversation [conversation-id actor-character-id cursor]
  (request "GET" (str "/api/v1/conversations/" (js/encodeURIComponent conversation-id)
                      "?actor_character_id=" (js/encodeURIComponent actor-character-id)
                      (when cursor (str "&cursor=" (js/encodeURIComponent cursor))))))

(defn send-message [conversation-id actor-character-id input-text]
  (request "POST" (str "/api/v1/conversations/" (js/encodeURIComponent conversation-id) "/messages")
           {:actor_character_id actor-character-id :input_text input-text}))

(defn mark-conversation-read [conversation-id actor-character-id message-id]
  (request "POST" (str "/api/v1/conversations/" (js/encodeURIComponent conversation-id) "/read")
           {:actor_character_id actor-character-id :last_read_message_id message-id}))

(defn delete-message [message-id actor-character-id]
  (request "DELETE" (str "/api/v1/messages/" (js/encodeURIComponent message-id))
           {:actor_character_id actor-character-id}))

(defn media-status [media-id actor-character-id]
  (request "GET" (str "/api/v1/media/" (js/encodeURIComponent media-id)
                      "?actor_character_id=" (js/encodeURIComponent actor-character-id))))

(defn delete-media [media-id actor-character-id]
  (request "DELETE" (str "/api/v1/media/" (js/encodeURIComponent media-id))
           {:actor_character_id actor-character-id}))

(defn update-character [character-id payload]
  (request "PATCH" (str "/api/v1/characters/" (js/encodeURIComponent character-id)) payload))

(defn upload-media [file actor-character-id purpose output-language on-progress]
  (js/Promise.
   (fn [resolve reject]
     (let [xhr (js/XMLHttpRequest.)
           form (js/FormData.)
           fail! (fn [code] (reject (coded-error code)))]
       (.append form "file" file)
       (.append form "actor_character_id" actor-character-id)
       (.append form "purpose" purpose)
       (.append form "output_language" output-language)
       (.open xhr "POST" "/api/v1/media")
       (.setRequestHeader xhr "accept" "application/json")
       (.setRequestHeader xhr "idempotency-key" (idempotency-key))
       (when @access-token (.setRequestHeader xhr "authorization" (str "Bearer " @access-token)))
       (when (and (nil? @access-token) @dev-auth?)
         (.setRequestHeader xhr "x-slopbook-dev-account" "demo-account"))
       (set! (.-onprogress (.-upload xhr))
             (fn [event]
               (when (.-lengthComputable event)
                 (on-progress (js/Math.round (* 100 (/ (.-loaded event) (.-total event))))))))
       (set! (.-onload xhr)
             (fn []
               (let [payload (try (js->clj (.parse js/JSON (.-responseText xhr))
                                           :keywordize-keys true)
                                  (catch :default _ nil))]
                 (if (<= 200 (.-status xhr) 299)
                   (resolve payload)
                   (fail! (or (get-in payload [:error :code]) "MEDIA_UPLOAD_FAILED"))))))
       (set! (.-onerror xhr) #(fail! "NETWORK_ERROR"))
       (.send xhr form)))))
