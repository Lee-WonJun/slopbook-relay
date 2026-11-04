(ns slopbook.client.auth-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [slopbook.client.api :as api]
            [slopbook.client.auth :as auth]))

(deftest callback-errors-use-localizable-codes
  (is (= {:code "AUTH_CANCELLED"}
         (auth/callback-error "?error=access_denied&error_description=secret-provider-detail")))
  (is (= "AUTH_CALLBACK_INVALID"
         (:code (auth/callback-error "?error=unknown&error_description=do-not-render")))))

(deftest pkce-verifier-survives-redirect-without-persisting-session
  (let [verifier-values (atom {})
        session-values (atom {})
        verifier-storage #js {:getItem #(get @verifier-values %)
                              :setItem #(swap! verifier-values assoc %1 %2)
                              :removeItem #(swap! verifier-values dissoc %)}
        storage (auth/auth-storage verifier-storage session-values)
        verifier-key "sb-example-code-verifier"
        session-key "sb-example-auth-token"]
    (.setItem storage verifier-key "synthetic-verifier")
    (.setItem storage session-key "synthetic-session")
    (is (= "synthetic-verifier" (get @verifier-values verifier-key)))
    (is (not (contains? @verifier-values session-key)))
    (is (= "synthetic-session" (get @session-values session-key)))
    (is (= "synthetic-verifier" (.getItem storage verifier-key)))
    (is (= "synthetic-session" (.getItem storage session-key)))
    (.removeItem storage verifier-key)
    (.removeItem storage session-key)
    (is (empty? @verifier-values))
    (is (empty? @session-values))))

(deftest authenticated-bootstrap-sets-token-before-loading-owner-projection
  (let [me-effect {:request :synthetic-request-thunk
                   :success [:me/success]
                   :failure [:me/failure]}]
    (is (= [[:api/token "synthetic-token"] [:api me-effect]]
           (auth/session-effects "synthetic-token" me-effect)))
    (is (= [[:api/token nil]]
           (auth/session-effects nil nil)))))

(deftest get-requests-bypass-browser-cache
  (let [get-options (api/request-options "GET" {"accept" "application/json"} nil)
        post-options (api/request-options "POST" {"accept" "application/json"}
                                          {:input_text "synthetic"})]
    (is (= "no-store" (:cache get-options)))
    (is (not (contains? post-options :cache)))
    (is (string? (:body post-options)))
    (is (= "/api/v1/feed?mode=latest&_cb=123"
           (api/request-path "GET" "/api/v1/feed?mode=latest" 123)))
    (is (= "/api/v1/config?_cb=123"
           (api/request-path "GET" "/api/v1/config" 123)))
    (is (= "/api/v1/posts"
           (api/request-path "POST" "/api/v1/posts" 123)))))

(deftest request-auth-never-silently-downgrades-present-token
  (let [bearer (api/request-headers "GET" "synthetic-token" true)
        development (api/request-headers "GET" nil true)
        anonymous (api/request-headers "GET" nil false)]
    (is (= "Bearer synthetic-token" (get bearer "authorization")))
    (is (not (contains? bearer "x-slopbook-dev-account")))
    (is (= "demo-account" (get development "x-slopbook-dev-account")))
    (is (not (contains? anonymous "authorization")))))

(deftest profile-path-encodes-public-route-and-opaque-cursor
  (is (= "/api/v1/characters/rotor_thinks"
         (api/profile-path "rotor_thinks" nil)))
  (is (= "/api/v1/characters/rotor%20thinks?cursor=a%2Bb%2Fc%3D"
         (api/profile-path "rotor thinks" "a+b/c="))))
