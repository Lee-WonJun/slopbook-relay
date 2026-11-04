(ns slopbook.qa-account-controls-contract-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(deftest owned-profiles-expose-sign-out-and-confirmed-account-deletion
  (let [app-source (slurp (io/file "src/slopbook/client/app.cljs"))
        api-source (slurp (io/file "src/slopbook/client/api.cljs"))
        http-source (slurp (io/file "src/slopbook/identity/adapters/in/http.clj"))]
    (testing "development and production sessions both expose log out"
      (is (str/includes? app-source
                         "(contains? #{:authenticated :development} status)")))
    (testing "owned profiles include account settings and a two-step delete action"
      (is (str/includes? app-source "(defn account-settings"))
      (is (str/includes? app-source "[:account/delete-open]"))
      (is (str/includes? app-source "[:account/delete-confirm]")))
    (testing "the client and server share DELETE /me with explicit confirmation"
      (is (str/includes? api-source
                         "(request \"DELETE\" \"/api/v1/me\" {:confirm true})"))
      (is (str/includes? http-source ":delete (fn [request]")))))

(deftest owned-personas-expose-edit-and-confirmed-deletion
  (let [app-source (slurp (io/file "src/slopbook/client/app.cljs"))
        api-source (slurp (io/file "src/slopbook/client/api.cljs"))
        state-source (slurp (io/file "src/slopbook/client/state.cljs"))
        i18n-source (slurp (io/file "src/slopbook/client/i18n.cljs"))]
    (testing "an owned profile exposes persona edit and a two-step delete action"
      (is (str/includes? app-source "(defn persona-settings"))
      (is (str/includes? app-source "[:persona/edit-start"))
      (is (str/includes? app-source "[:persona/delete-open"))
      (is (str/includes? app-source "[:persona/delete-confirm]")))
    (testing "the editor has a save path separate from first-persona creation"
      (is (str/includes? state-source ":persona/save"))
      (is (str/includes? state-source ":persona/save-success"))
      (is (str/includes? state-source "persona-edit-handle-for-location")))
    (testing "the client calls the existing owned-persona delete endpoint"
      (is (str/includes? api-source "(defn delete-character"))
      (is (str/includes? api-source "(request \"DELETE\"")))
    (testing "Korean product copy calls the entity a persona"
      (is (not (str/includes? i18n-source "캐릭터"))))))
