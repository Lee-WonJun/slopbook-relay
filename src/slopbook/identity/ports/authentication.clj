(ns slopbook.identity.ports.authentication)

(def required-operations #{:verify-token :bust-cache!})

(defn assert-adapter! [adapter]
  (let [missing (remove #(fn? (get adapter %)) required-operations)]
    (when (seq missing)
      (throw (ex-info "Identity authentication adapter is incomplete" {:missing (vec missing)})))
    adapter))

(defn verify-token [adapter token]
  ((:verify-token adapter) token))

(defn bust-cache! [adapter]
  ((:bust-cache! adapter)))
