(ns slopbook.client.auth
  (:require ["@supabase/supabase-js" :refer [createClient]]
            [clojure.string :as str]
            [goog.object :as gobj]))

(defonce client (atom nil))
(defonce subscription (atom nil))
(defonce session-memory (atom {}))

(def callback-error-copy
  {"access_denied" {:code "AUTH_CANCELLED"}
   "server_error" {:code "AUTH_PROVIDER_ERROR"}
   "temporarily_unavailable" {:code "AUTH_PROVIDER_ERROR"}})

(defn- coded-error [code]
  (doto (js/Error. code)
    (aset "code" code)))

(defn callback-error [search]
  (let [params (js/URLSearchParams. (or search ""))
        error (.get params "error")]
    (when error
      (get callback-error-copy error
           {:code "AUTH_CALLBACK_INVALID"}))))

(defn clear-auth-query! []
  (let [url (js/URL. (.-href js/location))]
    (doseq [key ["code" "error" "error_code" "error_description"]]
      (.delete (.-searchParams url) key))
    (.replaceState js/history nil "" (str (.-pathname url) (.-search url) (.-hash url)))))

(defn code-verifier-key? [key]
  (str/ends-with? key "-code-verifier"))

(defn auth-storage
  ([] (auth-storage js/sessionStorage session-memory))
  ([verifier-storage memory]
   #js {:getItem (fn [key]
                   (if (code-verifier-key? key)
                     (.getItem verifier-storage key)
                     (get @memory key)))
        :setItem (fn [key value]
                   (if (code-verifier-key? key)
                     (.setItem verifier-storage key value)
                     (swap! memory assoc key value)))
        :removeItem (fn [key]
                      (if (code-verifier-key? key)
                        (.removeItem verifier-storage key)
                        (swap! memory dissoc key)))}))

(defn session-effects [token me-effect]
  (cond-> [[:api/token token]]
    me-effect (conj [:api me-effect])))

(defn- auth-client [config]
  (createClient
   (get-in config [:auth :supabase_url])
   (get-in config [:auth :publishable_key])
   #js {:auth #js {:flowType "pkce"
                   :autoRefreshToken true
                   :persistSession true
                   :detectSessionInUrl false
                   :storage (auth-storage)}}))

(defn- token-from-session [session]
  (when session (gobj/get session "access_token")))

(defn- result-session [result]
  (some-> (gobj/get result "data") (gobj/get "session")))

(defn- auth-api [instance]
  (gobj/get instance "auth"))

(defn initialize! [config dispatch!]
  (if-not (get-in config [:auth :enabled])
    (dispatch! [:auth/session nil "DISABLED"])
    (if-let [error (callback-error (.-search js/location))]
      (do
        (clear-auth-query!)
        (dispatch! [:auth/callback-failure error]))
      (let [instance (auth-client config)
            ^js auth (auth-api instance)
            params (js/URLSearchParams. (.-search js/location))
            code (.get params "code")]
        (reset! client instance)
        (when-let [old-subscription @subscription]
          (.unsubscribe old-subscription))
        (let [result (.onAuthStateChange
                      auth
                      (fn [event session]
                        (when (contains? #{"SIGNED_IN" "TOKEN_REFRESHED" "SIGNED_OUT" "INITIAL_SESSION"}
                                         event)
                          (when (or (= event "SIGNED_IN") (= event "INITIAL_SESSION"))
                            (clear-auth-query!))
                          (dispatch! [:auth/session (token-from-session session) event]))))
              next-subscription (.. result -data -subscription)]
          (reset! subscription next-subscription))
        (if code
          (-> (.exchangeCodeForSession auth code)
              (.then (fn [result]
                       (if (.-error result)
                         (dispatch! [:auth/callback-failure
                                     {:code "AUTH_CALLBACK_INVALID"}])
                         (dispatch! [:auth/session
                                     (token-from-session (result-session result))
                                     "SIGNED_IN"]))
                       (clear-auth-query!)))
              (.catch (fn [_]
                        (clear-auth-query!)
                        (dispatch! [:auth/callback-failure
                                    {:code "AUTH_CALLBACK_INVALID"}]))))
          (-> (.getSession auth)
              (.then (fn [result]
                       (dispatch! [:auth/session
                                   (token-from-session (result-session result))
                                   "INITIAL_SESSION"])))))))))

(defn sign-in! [redirect-url]
  (if-let [instance @client]
    (let [^js auth (auth-api instance)]
      (-> (.signInWithOAuth
           auth
           #js {:provider "google"
                :options #js {:redirectTo redirect-url
                              :skipBrowserRedirect false}})
        (.then (fn [result]
                 (when-let [error (.-error result)]
                   (throw (coded-error "AUTH_START_FAILED")))
                 result))))
    (js/Promise.reject (coded-error "AUTH_CONFIG_UNAVAILABLE"))))

(defn sign-out! []
  (if-let [instance @client]
    (let [^js auth (auth-api instance)]
      (.signOut auth #js {:scope "local"}))
    (js/Promise.resolve nil)))
