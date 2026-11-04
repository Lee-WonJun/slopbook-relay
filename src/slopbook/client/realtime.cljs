(ns slopbook.client.realtime
  (:require [slopbook.client.api :as api]))

(defonce socket (atom nil))
(defonce generation (atom 0))
(defonce retry-timer (atom nil))

(defn stop! []
  (swap! generation inc)
  (when @retry-timer (js/clearTimeout @retry-timer) (reset! retry-timer nil))
  (when-let [ws @socket]
    (set! (.-onclose ws) nil)
    (.close ws))
  (reset! socket nil))

(defn- websocket-url [ticket]
  (str (if (= "https:" (.-protocol js/location)) "wss://" "ws://")
       (.-host js/location) "/ws?ticket=" (js/encodeURIComponent ticket)))

(defn start! [actor-character-id dispatch]
  (stop!)
  (when actor-character-id
    (let [run (swap! generation inc)
          connect
          (fn connect [attempt]
            (when (= run @generation)
              (-> (api/websocket-ticket actor-character-id)
                  (.then
                   (fn [result]
                     (when (= run @generation)
                       (let [ws (js/WebSocket. (websocket-url (:ticket result)))]
                         (reset! socket ws)
                         (set! (.-onopen ws) #(dispatch [:realtime/connected]))
                         (set! (.-onmessage ws)
                               (fn [event]
                                 (try
                                   (dispatch [:realtime/hint
                                              (js->clj (.parse js/JSON (.-data event))
                                                       :keywordize-keys true)])
                                   (catch :default _ nil))))
                         (set! (.-onerror ws) #(.close ws))
                         (set! (.-onclose ws)
                               (fn [_]
                                 (when (= run @generation)
                                   (dispatch [:realtime/disconnected])
                                   (let [delay (min 30000 (* 1000 (js/Math.pow 2 attempt)))]
                                     (reset! retry-timer
                                             (js/setTimeout #(connect (inc attempt)) delay))))))))))
                  (.catch
                   (fn [_]
                     (when (= run @generation)
                       (dispatch [:realtime/disconnected])
                       (reset! retry-timer
                               (js/setTimeout #(connect (inc attempt))
                                              (min 30000 (* 1000 (js/Math.pow 2 attempt)))))))))))]
      (connect 0))))
