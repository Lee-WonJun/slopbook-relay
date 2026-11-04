(ns slopbook.trust.application.reports
  (:require [slopbook.trust.domain.moderation :as moderation]
            [slopbook.trust.ports.reports :as ports]))

(defn create! [{:keys [store new-id now]} reporter-account-id input]
  (ports/insert!
   store
   (moderation/report-value
    (assoc input :id (new-id) :reporter-account-id reporter-account-id :now (now)))))
