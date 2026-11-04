(ns slopbook.client-accessibility-contract-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [slopbook.publishing.application.greetings :as greetings]
            [slopbook.shared.domain.greeting :as greeting]
            [slopbook.transformation.application.rewrites :as rewrites]))

(defn- event-block [source event next-event]
  (let [start-marker (str "(rf/reg-event-fx\n " event)
        end-marker (str "(rf/reg-event-fx\n " next-event)
        start (.indexOf source start-marker)
        end (.indexOf source end-marker (inc start))]
    (is (<= 0 start) (str "missing event: " event))
    (is (< start end) (str "missing event boundary after: " event))
    (subs source start end)))

;; Regression: ISSUE-001 — skip-link navigation did not transfer focus to main
;; Found by /gstack-qa on 2026-07-16
;; Report: docs/evidence/runtime/production-skip-link-before.png
(deftest skip-link-target-is-programmatically-focusable
  (let [source (slurp (io/file "src/slopbook/client/app.cljs"))]
    (is (re-find #"(?s)\[:main\.main-column\s+\{:id\s+\"main-content\"\s+:tab-index\s+-1"
                 source))))

;; Regression: ISSUE-002 — visible form fields had neither id nor name
;; Found by /gstack-qa on 2026-07-16
;; Report: docs/evidence/RUNTIME-E2E.md
(deftest interactive-form-fields-have-stable-browser-identities
  (let [source (slurp (io/file "src/slopbook/client/app.cljs"))]
    (doseq [field-name ["post-intent"
                        "persona-display-name"
                        "persona-handle"
                        "persona-output-language"
                        "persona-bio"
                        "persona-pronouns"
                        "persona-home-base"
                        "persona-motto"
                        "persona-setting"
                        "persona-sentence-shape"
                        "persona-formality"
                        "persona-catchphrase"
                        "persona-avoid-style"
                        "persona-custom-rule"
                        "profile-avatar"
                        "anonymous-display-name"
                        "anonymous-bio"
                        "anonymous-catchphrase"
                        "anonymous-output-language"
                        "anonymous-sentence-shape"
                        "anonymous-formality"
                        "mobile-character-select"
                        "comment-intent"
                        "search-query"
                        "message-intent"]]
      (is (re-find (re-pattern (str ":name\\s+\\\"" field-name "\\\"")) source)
          (str "missing form field identity: " field-name)))
    (doseq [generated-field-name ["persona-humor-style" "persona-emoji-level"
                                  "persona-social-mode" "persona-reply-posture"]]
      (is (re-find (re-pattern (str "\\\"" generated-field-name "\\\"")) source)
          (str "missing generated form field identity: " generated-field-name)))
    (is (re-find #":name\s+\(str purpose \"-media\"\)" source)
        "missing media input identity")))

(deftest bounded-editor-controls-expose-current-and-maximum-counts
  (let [source (slurp (io/file "src/slopbook/client/app.cljs"))]
    (is (re-find #"choice-legend" source))
    (is (re-find #"counted-field-label" source))
    (is (re-find #"\(str current \" / \" maximum\)" source))
    (is (re-find #"\[limit-count db \(count items\) maximum :selection\]" source))))

(deftest language-picker-is-direct-discoverable-and-keyboard-dismissible
  (let [app-source (slurp (io/file "src/slopbook/client/app.cljs"))
        state-source (slurp (io/file "src/slopbook/client/state.cljs"))]
    (is (re-find #":aria-haspopup\s+\"dialog\"" app-source))
    (is (re-find #":aria-expanded" app-source))
    (is (re-find #"language-picker-dialog" app-source))
    (is (re-find #"\(= \"Escape\" \(\.-key event\)\)" app-source))
    (is (re-find #"\.focus node" app-source))
    (is (re-find #":locale/select" state-source))
    (is (not (re-find #":locale/cycle" state-source)))))

(deftest persona-greeting-preview-is-user-initiated
  (let [source (slurp (io/file "src/slopbook/client/state.cljs"))
        start (event-block source ":persona/start" ":persona/randomize")
        randomize (event-block source ":persona/randomize" ":persona/set")
        set-value (event-block source ":persona/set" ":persona/toggle-choice")
        toggle (event-block source ":persona/toggle-choice" ":persona/greeting-preview")
        preview (event-block source ":persona/greeting-preview"
                             ":persona/greeting-preview-success")]
    (is (not (.contains start "api/preview-character-greeting")))
    (is (not (.contains randomize "api/preview-character-greeting")))
    (is (.contains set-value ":status :stale"))
    (is (.contains toggle ":status :stale"))
    (is (.contains set-value "(assoc-in [:persona :message] \"\")"))
    (is (.contains toggle "(assoc-in [:persona :message] \"\")"))
    (is (.contains preview "api/preview-character-greeting"))))

(deftest greeting-placeholder-and-server-intent-share-one-contract
  (is (= "Hi, I'm Rotor. I joined Slopbook. Nice to meet you."
         (greeting/intent "en" "Rotor")))
  (is (= "안녕하세요, 로터입니다. Slopbook에 가입했습니다. 잘 부탁드립니다."
         (greeting/intent "ko" "로터")))
  (is (= "你好，我是转子。我加入了 Slopbook，请多关照。"
         (greeting/intent "zh-Hans" "转子")))
  (is (= "こんにちは、ローターです。Slopbookに参加しました。よろしくお願いします。"
         (greeting/intent "ja" "ローター"))))

(deftest greeting-preview-retries-one-provider-error
  (let [attempts (atom 0)
        request {:operation "rewrite.post"}
        provider-error (ex-info "provider"
                                {:slopbook/error true
                                 :status 503
                                 :code "AI_PROVIDER_ERROR"})]
    (with-redefs [rewrites/rewrite! (fn [_ _]
                                      (if (= 1 (swap! attempts inc))
                                        (throw provider-error)
                                        {:status "completed" :text "Hello"}))]
      (is (= "Hello" (:text (#'greetings/rewrite-preview! {} request))))
      (is (= 2 @attempts)))
    (reset! attempts 0)
    (with-redefs [rewrites/rewrite! (fn [_ _]
                                      (swap! attempts inc)
                                      (throw provider-error))]
      (let [exception (try
                        (#'greetings/rewrite-preview! {} request)
                        nil
                        (catch clojure.lang.ExceptionInfo value value))]
        (is (= "AI_PROVIDER_ERROR" (:code (ex-data exception))))
        (is (= 2 @attempts))))
    (reset! attempts 0)
    (with-redefs [rewrites/rewrite! (fn [_ _]
                                      (swap! attempts inc)
                                      (throw (ex-info "credits"
                                                      {:slopbook/error true
                                                       :status 402
                                                       :code "AI_CREDITS_EXHAUSTED"})))]
      (let [exception (try
                        (#'greetings/rewrite-preview! {} request)
                        nil
                        (catch clojure.lang.ExceptionInfo value value))]
        (is (= "AI_CREDITS_EXHAUSTED" (:code (ex-data exception))))
        (is (= 1 @attempts))))))
