(ns slopbook.identity.ports.accounts)

(def required-operations #{:find-or-create! :find-by-id :request-deletion!})

(defn assert-adapter! [adapter]
  (let [missing (remove #(fn? (get adapter %)) required-operations)]
    (when (seq missing)
      (throw (ex-info "Identity account adapter is incomplete" {:missing (vec missing)})))
    adapter))

(defn find-or-create! [adapter auth-subject]
  ((:find-or-create! adapter) auth-subject))

(defn find-by-id [adapter account-id]
  ((:find-by-id adapter) account-id))

(defn request-deletion! [adapter account-id]
  ((:request-deletion! adapter) account-id))
