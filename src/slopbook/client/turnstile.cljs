(ns slopbook.client.turnstile
  (:require [reagent.core :as r]))

(defonce loader (atom nil))

(defn- load-script! []
  (or @loader
      (let [promise
            (js/Promise.
             (fn [resolve reject]
               (if (some? (.-turnstile js/window))
                 (resolve (.-turnstile js/window))
                 (let [script (.createElement js/document "script")]
                   (set! (.-src script)
                         "https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit")
                   (set! (.-async script) true)
                   (set! (.-defer script) true)
                   (set! (.-onload script) #(resolve (.-turnstile js/window)))
                   (set! (.-onerror script) #(reject (js/Error. "Turnstile unavailable")))
                   (.appendChild (.-head js/document) script)))))]
        (reset! loader promise)
        promise)))

(defn widget [{:keys [site-key action locale on-token on-expired]}]
  (let [node (atom nil)
        widget-id (atom nil)]
    (r/create-class
     {:display-name "turnstile-widget"
      :component-did-mount
      (fn [_]
        (-> (load-script!)
            (.then (fn [turnstile]
                     (when @node
                       (reset! widget-id
                               (.render turnstile @node
                                        (clj->js {:sitekey site-key :action action
                                                  :language locale :theme "auto"
                                                  :callback on-token
                                                  :expired-callback on-expired
                                                  :error-callback on-expired}))))))
            (.catch (fn [_] (on-expired)))))
      :component-will-unmount
      (fn [_]
        (when (and @widget-id (some? (.-turnstile js/window)))
          (.remove (.-turnstile js/window) @widget-id)))
      :reagent-render
      (fn [_]
        [:div.turnstile-host {:ref #(reset! node %)}])})))
