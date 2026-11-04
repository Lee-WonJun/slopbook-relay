(ns slopbook.platform.config-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopbook.platform.config :as config]))

(def production-env
  {"SLOPBOOK_ENV" "production"
   "SLOPBOOK_STORE" "postgres"
   "SLOPBOOK_DEV_AUTH" "false"
   "DATABASE_URL" "jdbc:postgresql://db/slopbook"
   "SUPABASE_URL" "https://auth.example.test"
   "SUPABASE_PUBLISHABLE_KEY" "synthetic-publishable-key"
   "SUPABASE_JWKS_URL" "https://auth.example.test/.well-known/jwks.json"
   "SUPABASE_ISSUER" "https://auth.example.test/auth/v1"
   "SUPABASE_SERVICE_ROLE_KEY" "synthetic-service-role-key"
   "OPENAI_API_KEY" "synthetic-openai-key"
   "POLICY_CONTACT_EMAIL" "privacy@example.test"
   "SLOPBOOK_CURSOR_SECRET" "synthetic-production-cursor-secret"})

(defn load-with [environment]
  (with-redefs-fn {#'slopbook.platform.config/env #(get environment %)}
    config/load-config))

(deftest runtime-uses-only-the-approved-openai-model-and-server-key
  (let [runtime (load-with {"OPENAI_API_KEY" "synthetic-openai-key"})
        public (pr-str (config/public-client-config runtime))]
    (is (= ["gpt-6-luna"] (:openai-models runtime)))
    (is (= "standard" (:openai-data-policy runtime)))
    (is (:ai-discovery? runtime))
    (is (not (.contains public "synthetic-openai-key")))
    (is (nil? (:openrouter-api-key runtime))))
  (is (thrown? clojure.lang.ExceptionInfo
               (load-with {"SLOPBOOK_AI_MODELS" "gpt-6-astra"})))
  (is (thrown? clojure.lang.ExceptionInfo
               (load-with {"SLOPBOOK_OPENAI_DATA_POLICY" "allow-anything"})))
  (is (nil? (:openai-api-key (load-with {"OPENROUTER_API_KEY" "legacy-key"}))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"openai-api-key is required"
                        (load-with (dissoc production-env "OPENAI_API_KEY")))))

(deftest local-ai-fallback-environment-switch-is-not-supported
  (is (nil? (:ai-allow-local?
             (load-with (assoc production-env "SLOPBOOK_AI_ALLOW_LOCAL" "true"))))))

(deftest meta-edition-selects-muse-without-an-openai-key
  (let [environment (-> production-env (dissoc "OPENAI_API_KEY")
                        (assoc "SLOPBOOK_AI_PROVIDER" "meta" "META_MODEL_API_KEY" "synthetic-meta-key"))
        runtime (load-with environment)
        public (config/public-client-config runtime)]
    (is (= "meta" (:ai-provider runtime)))
    (is (= "meta" (get-in public [:ai :provider])))
    (is (nil? (:openai-api-key runtime)))
    (is (not (.contains (pr-str public) "synthetic-meta-key")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"meta-api-key is required"
                          (load-with (dissoc environment "META_MODEL_API_KEY"))))))

(deftest production-requires-a-real-policy-contact
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"policy-contact-email is required in production"
       (load-with (dissoc production-env "POLICY_CONTACT_EMAIL"))))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"POLICY_CONTACT_EMAIL must be a valid email address"
       (load-with (assoc production-env "POLICY_CONTACT_EMAIL" "not-an-email"))))
  (is (= "privacy@example.test"
         (get-in (config/public-client-config (load-with production-env))
                 [:policy :contact_email]))))

(deftest turnstile-is-an-optional-fail-closed-production-capability
  (testing "production boots without Turnstile and advertises anonymous comments as unavailable"
    (let [runtime (load-with production-env)]
      (is (false? (:turnstile-enabled? runtime)))
      (is (false? (get-in (config/public-client-config runtime)
                          [:features :anonymous_comments])))))

  (testing "a partial Turnstile configuration is rejected"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"must be configured together"
         (load-with (assoc production-env "TURNSTILE_SITE_KEY" "synthetic-site-key"))))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"must be configured together"
         (load-with (assoc production-env "TURNSTILE_SECRET_KEY" "synthetic-secret-key")))))

  (testing "enabled production Turnstile requires a hostname allowlist"
    (let [turnstile-env (assoc production-env
                               "TURNSTILE_SITE_KEY" "synthetic-site-key"
                               "TURNSTILE_SECRET_KEY" "synthetic-secret-key")]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"TURNSTILE_EXPECTED_HOSTNAMES is required"
           (load-with turnstile-env)))
      (let [runtime (load-with (assoc turnstile-env
                                      "TURNSTILE_EXPECTED_HOSTNAMES"
                                      "app.slopbook.org"))]
        (is (true? (:turnstile-enabled? runtime)))
        (is (true? (get-in (config/public-client-config runtime)
                           [:features :anonymous_comments])))))))
