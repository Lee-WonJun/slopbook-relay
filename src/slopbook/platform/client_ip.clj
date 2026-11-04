(ns slopbook.platform.client-ip
  (:require [clojure.string :as str])
  (:import [java.net InetAddress]))

(defn- candidate [value]
  (some-> value (str/split #",") first str/trim not-empty))

(defn valid-address? [value]
  (let [value (candidate value)]
    (boolean
     (and value
          (<= (count value) 64)
          (re-matches #"[0-9A-Fa-f:.]+" value)
          (try
            (InetAddress/getByName value)
            true
            (catch Exception _ false))))))

(defn remote-ip [config request]
  (let [forwarded (when (:trust-forwarded-ip? config)
                    (some #(when (valid-address? %) (candidate %))
                          [(get-in request [:headers "cf-connecting-ip"])
                           (get-in request [:headers "x-forwarded-for"])]))
        direct (candidate (:remote-addr request))]
    (cond
      (valid-address? forwarded) forwarded
      (valid-address? direct) direct
      :else "unknown")))
