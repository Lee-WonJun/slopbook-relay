(ns slopbook.platform.config
  (:require [clojure.string :as str]))

(defn- env [name]
  (some-> (System/getenv name) str/trim not-empty))

(defn- parse-int [value fallback]
  (try
    (if value (Integer/parseInt value) fallback)
    (catch NumberFormatException _ fallback)))

(defn- required! [config key environment]
  (when (and (= "production" environment) (nil? (get config key)))
    (throw (ex-info (str (name key) " is required in production") {:key key}))))

(defn- parse-bool [value fallback]
  (if (nil? value)
    fallback
    (contains? #{"1" "true" "yes" "on"} (str/lower-case value))))

(defn load-config []
  (let [environment (or (env "SLOPBOOK_ENV") "development")
        development? (= "development" environment)
        public-base-url (or (env "SLOPBOOK_PUBLIC_BASE_URL") "http://localhost:8080")
        service-role-key (env "SUPABASE_SERVICE_ROLE_KEY")
        turnstile-site (env "TURNSTILE_SITE_KEY")
        turnstile-secret (env "TURNSTILE_SECRET_KEY")
        runtime-config
        {:environment environment
         :development? development?
         :port (parse-int (env "SLOPBOOK_PORT") 8080)
         :public-base-url public-base-url
         :policy-contact-email (env "POLICY_CONTACT_EMAIL")
         :trust-forwarded-ip? (parse-bool (env "SLOPBOOK_TRUST_FORWARDED_IP") false)
         :dev-auth? (parse-bool (env "SLOPBOOK_DEV_AUTH") development?)
         :store (keyword (or (env "SLOPBOOK_STORE") "memory"))
         :database-url (env "DATABASE_URL")
         :db-schema (env "SLOPBOOK_DB_SCHEMA")
         :db-pool-max (parse-int (env "SLOPBOOK_DB_POOL_MAX") 10)
         :cursor-secret (or (env "SLOPBOOK_CURSOR_SECRET")
                            (when development? "slopbook-development-cursor-secret"))
         :supabase-url (env "SUPABASE_URL")
         :supabase-publishable-key (env "SUPABASE_PUBLISHABLE_KEY")
         :supabase-jwks-url (env "SUPABASE_JWKS_URL")
         :supabase-issuer (env "SUPABASE_ISSUER")
         :supabase-audience (or (env "SUPABASE_AUDIENCE") "authenticated")
         :supabase-service-role-key service-role-key
         :media-bucket (or (env "SLOPBOOK_MEDIA_BUCKET") "slopbook-media")
         :media-enabled? (boolean (or service-role-key development?))
         :turnstile-site-key turnstile-site
         :turnstile-secret-key turnstile-secret
         :turnstile-expected-hostnames (->> (str/split (or (env "TURNSTILE_EXPECTED_HOSTNAMES") "") #",")
                                              (map (comp str/lower-case str/trim))
                                              (remove str/blank?) set)
         :turnstile-expected-action (or (env "TURNSTILE_EXPECTED_ACTION") "anonymous_comment")
         :turnstile-enabled? (boolean (and turnstile-site turnstile-secret))
         :jwks-cache-seconds (parse-int (env "SLOPBOOK_JWKS_CACHE_SECONDS") 300)
         :auth-redirect-url (or (env "SLOPBOOK_AUTH_REDIRECT_URL")
                                (str (str/replace public-base-url #"/$" "") "/auth/callback"))
         :openai-api-key (env "OPENAI_API_KEY")
         :meta-api-key (env "META_MODEL_API_KEY")
         :ai-provider (or (env "SLOPBOOK_AI_PROVIDER") "openai")
         :openai-data-policy (or (env "SLOPBOOK_OPENAI_DATA_POLICY") "standard")
         :openai-zdr-verified? (parse-bool (env "SLOPBOOK_OPENAI_ZDR_VERIFIED") false)
         :openai-models (->> (str/split (or (env "SLOPBOOK_AI_MODELS") "gpt-6-luna")
                                            #",")
                                 (map str/trim)
                                 (remove str/blank?)
                                 vec)
         :ai-discovery? (parse-bool (env "SLOPBOOK_AI_DISCOVERY")
                                    (boolean (env "OPENAI_API_KEY")))}]
    (when-not (contains? #{"openai" "meta"} (:ai-provider runtime-config))
      (throw (ex-info "SLOPBOOK_AI_PROVIDER must be openai or meta" {})))
    (when-not (contains? #{"standard" "zdr"} (:openai-data-policy runtime-config))
      (throw (ex-info "SLOPBOOK_OPENAI_DATA_POLICY must be standard or zdr" {})))
    (when-not (= ["gpt-6-luna"] (:openai-models runtime-config))
      (throw (ex-info "SLOPBOOK_AI_MODELS must be gpt-6-luna" {})))
    (when (and (= "production" environment) (:dev-auth? runtime-config))
      (throw (ex-info "SLOPBOOK_DEV_AUTH cannot be enabled in production" {})))
    (when (= "production" environment)
      (when-not (= :postgres (:store runtime-config))
        (throw (ex-info "SLOPBOOK_STORE must be postgres in production" {})))
      (doseq [key [:database-url :supabase-url :supabase-publishable-key
                   :supabase-jwks-url :supabase-issuer
                   :cursor-secret :supabase-service-role-key :policy-contact-email]]
        (required! runtime-config key environment))
      (required! runtime-config (if (= "meta" (:ai-provider runtime-config)) :meta-api-key :openai-api-key)
                 environment))
    (when (and (:policy-contact-email runtime-config)
               (not (re-matches #"[^\s@]+@[^\s@]+\.[^\s@]+"
                                (:policy-contact-email runtime-config))))
      (throw (ex-info "POLICY_CONTACT_EMAIL must be a valid email address" {})))
    (when (not= (boolean (:turnstile-site-key runtime-config))
                (boolean (:turnstile-secret-key runtime-config)))
      (throw (ex-info "TURNSTILE_SITE_KEY and TURNSTILE_SECRET_KEY must be configured together" {})))
    (when (and (= "production" environment)
               (:turnstile-enabled? runtime-config)
               (empty? (:turnstile-expected-hostnames runtime-config)))
      (throw (ex-info "TURNSTILE_EXPECTED_HOSTNAMES is required in production" {})))
    (when-not (<= 1 (:db-pool-max runtime-config) 50)
      (throw (ex-info "SLOPBOOK_DB_POOL_MAX must be between 1 and 50" {})))
    (when (and (:db-schema runtime-config)
               (not (re-matches #"[a-z][a-z0-9_]{0,62}" (:db-schema runtime-config))))
      (throw (ex-info "SLOPBOOK_DB_SCHEMA must be a lowercase PostgreSQL identifier" {})))
    (when-not (<= 30 (:jwks-cache-seconds runtime-config) 3600)
      (throw (ex-info "SLOPBOOK_JWKS_CACHE_SECONDS must be between 30 and 3600" {})))
    (when-not (re-matches #"[a-z0-9][a-z0-9-]{1,62}" (:media-bucket runtime-config))
      (throw (ex-info "SLOPBOOK_MEDIA_BUCKET is invalid" {})))
    runtime-config))

(defn public-summary [config]
  (select-keys config [:environment :port :public-base-url :dev-auth? :store]))

(defn public-client-config [config]
  (let [auth-enabled? (and (:supabase-url config) (:supabase-publishable-key config))]
    {:environment (:environment config)
     :ai {:provider (or (:ai-provider config) "openai") :relay_provider "meta"}
     :policy {:contact_email (:policy-contact-email config)}
     :features {:media_uploads (boolean (:media-enabled? config))
                :anonymous_comments (boolean (or (:turnstile-enabled? config)
                                                 (:development? config)))}
     :turnstile {:site_key (:turnstile-site-key config)
                 :action (:turnstile-expected-action config)
                 :development (and (:development? config) (not (:turnstile-enabled? config)))}
     :dev_auth (:dev-auth? config)
     :auth {:enabled (boolean auth-enabled?)
            :supabase_url (when auth-enabled? (:supabase-url config))
            :publishable_key (when auth-enabled? (:supabase-publishable-key config))
            :redirect_url (when auth-enabled? (:auth-redirect-url config))}}))
