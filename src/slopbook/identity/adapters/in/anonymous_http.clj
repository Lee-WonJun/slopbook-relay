(ns slopbook.identity.adapters.in.anonymous-http
  (:require [slopbook.identity.application.anonymous-sessions :as sessions]
            [slopbook.platform.client-ip :as client-ip]
            [slopbook.platform.http :as http]))

(defn routes [identity config]
  [["/api/v1/anonymous-sessions"
    {:post (fn [request]
             (http/json-response
              201 (sessions/create! identity (client-ip/remote-ip config request))))}]])
