(ns slopbook.identity.adapters.out.turnstile-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopbook.identity.adapters.out.turnstile :as turnstile]
            [slopbook.identity.ports.human-verification :as verification-port]))

(def config
  {:turnstile-expected-action "anonymous_comment"
   :turnstile-expected-hostnames #{"app.slopbook.org"}})

(deftest remote-result-requires-success-action-hostname-and-http-success
  (let [valid {:success true :action "anonymous_comment" :hostname "app.slopbook.org"}]
    (is (turnstile/valid-result? config 200 valid))
    (is (not (turnstile/valid-result? config 500 valid)))
    (is (not (turnstile/valid-result? config 200 (assoc valid :success false))))
    (is (not (turnstile/valid-result? config 200 (assoc valid :action "login"))))
    (is (not (turnstile/valid-result? config 200 (assoc valid :hostname "evil.example"))))
    (is (turnstile/valid-result? (assoc config :turnstile-expected-hostnames #{})
                                 204 (dissoc valid :hostname)))))

(deftest development-pass-is-confined-to-development-with-remote-verification-disabled
  (let [development (turnstile/adapter {:development? true :turnstile-enabled? false})
        production (turnstile/adapter {:development? false :turnstile-enabled? false})
        code (fn [f]
               (try (f) nil
                    (catch clojure.lang.ExceptionInfo exception (:code (ex-data exception)))))]
    (is (true? (verification-port/verify! development {:token "development-pass"})))
    (is (= "TURNSTILE_REQUIRED"
           (code #(verification-port/verify! development {:token "wrong"}))))
    (is (= "TURNSTILE_REQUIRED"
           (code #(verification-port/verify! production {:token "development-pass"}))))))
