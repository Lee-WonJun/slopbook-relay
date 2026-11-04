(ns slopbook.client.ime-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [slopbook.client.ime :as ime]))

(deftest korean-composition-is-never-replaced-by-stale-external-state
  (let [started (ime/begin-composition (ime/initial-state ""))
        consonant (ime/change-value started "ㄱ")
        syllable (ime/change-value consonant "가")
        phrase (ime/change-value syllable "간단한")]
    (is (= "ㄱ" (:draft (ime/reconcile consonant ""))))
    (is (= "가" (:draft (ime/reconcile syllable ""))))
    (is (= "간단한" (:draft (ime/reconcile phrase ""))))
    (is (nil? (:pending phrase)))
    (let [ended (ime/end-composition phrase "간단한")]
      (is (= "간단한" (:draft (ime/reconcile ended ""))))
      (is (= "간단한" (:pending ended)))
      (is (= {:draft "간단한" :composing? false :pending nil}
             (ime/reconcile ended "간단한"))))))

(deftest ordinary-input-waits-for-the-dispatched-value-before-reconciling
  (let [typed (ime/change-value (ime/initial-state "") "hello")]
    (is (= "hello" (:draft (ime/reconcile typed ""))))
    (is (= "hello" (:draft (ime/reconcile typed "hello"))))
    (is (nil? (:pending (ime/reconcile typed "hello"))))))
