(ns slopbook.client.i18n-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [slopbook.client.i18n :as i18n]
            [slopbook.client.randomizer :as randomizer]))

(defn complete-four-locale-entry? [entry]
  (and (vector? entry)
       (= (count i18n/locales) (count entry))
       (every? #(and (string? %) (not (str/blank? %))) entry)))

(deftest every-interface-and-catalog-entry-has-four-nonblank-locales
  (doseq [[key entry] i18n/messages]
    (is (complete-four-locale-entry? entry) (str "incomplete message " key)))
  (doseq [[id entry] i18n/catalog-labels]
    (is (complete-four-locale-entry? entry) (str "incomplete catalog label " id))))

(deftest every-declared-api-error-is-localized-without-exposing-a-key
  (doseq [code i18n/api-error-codes
          locale i18n/locales]
    (let [key (keyword (str "error." code))
          rendered (i18n/error-text locale {:code code})]
      (is (contains? i18n/messages key) (str "missing error " code))
      (is (not (str/blank? rendered)))
      (is (not= code rendered))))
  (is (= (i18n/t "ko" :common.error)
         (i18n/error-text "ko" {:code "UNKNOWN_PRIVATE_PROVIDER_DETAIL"}))))

(deftest official-slogan-remains-identical-in-every-locale
  (doseq [locale i18n/locales]
    (is (= "Character. Beyond Anonymity." (i18n/t locale :brand.tagline)))))

(deftest every-randomizer-catalog-id-has-a-localized-label
  (doseq [id (concat randomizer/what-catalog randomizer/archetype-catalog
                     randomizer/trait-catalog randomizer/tone-catalog
                     randomizer/interest-catalog randomizer/value-catalog
                     randomizer/dislike-catalog randomizer/setting-catalog)]
    (is (contains? i18n/catalog-labels id) (str "missing persona label " id))))

(deftest localized-random-names-and-handles-stay-inside-contract
  (doseq [locale i18n/locales
          _ (range 30)]
    (let [draft (randomizer/random-draft nil locale)]
      (is (<= 1 (count (:display_name draft)) 40))
      (is (<= 3 (count (:handle draft)) 20))
      (is (re-matches #"[a-z0-9_]+" (:handle draft)))
      (is (= locale (:output_language draft))))))
