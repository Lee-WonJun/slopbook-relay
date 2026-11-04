(ns slopbook.platform.client-ip-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopbook.platform.client-ip :as client-ip]
            [slopbook.platform.rate-limit :as rate-limit]))

(deftest forwarded-addresses-are-used-only-behind-an-explicit-trust-boundary
  (let [request {:headers {"cf-connecting-ip" "203.0.113.8"
                           "x-forwarded-for" "198.51.100.2, 10.0.0.2"}
                 :remote-addr "10.0.0.9"}]
    (is (= "10.0.0.9" (client-ip/remote-ip {:trust-forwarded-ip? false} request)))
    (is (= "203.0.113.8" (client-ip/remote-ip {:trust-forwarded-ip? true} request)))
    (is (= "198.51.100.2"
           (client-ip/remote-ip
            {:trust-forwarded-ip? true}
            (assoc-in request [:headers "cf-connecting-ip"] "forged-host.example"))))
    (is (= "unknown"
           (client-ip/remote-ip {:trust-forwarded-ip? true}
                                {:headers {"cf-connecting-ip" "not-an-ip"}})))
    (is (client-ip/valid-address? "2001:db8::1"))
    (is (not (client-ip/valid-address? "127.0.0.1.example")))))

(deftest limiter-hashes-identifiers-enforces-the-window-and-sweeps-stale-keys
  (let [{:keys [state operations] :as limiter} (rate-limit/limiter)
        raw "PRIVATE_IP_OR_ACCOUNT_CANARY"]
    (is (rate-limit/allow! limiter :search raw 2 60000))
    (is (rate-limit/allow! limiter :search raw 2 60000))
    (is (not (rate-limit/allow! limiter :search raw 2 60000)))
    (is (not (.contains (pr-str @state) raw)))
    (swap! state assoc [:stale "digest"]
           {:timestamps [(- (System/currentTimeMillis) 10000)] :window-ms 1})
    (reset! operations 127)
    (is (rate-limit/allow! limiter :other "another-key" 1 60000))
    (is (not (contains? @state [:stale "digest"])))))
