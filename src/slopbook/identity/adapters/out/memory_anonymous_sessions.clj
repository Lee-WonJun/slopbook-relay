(ns slopbook.identity.adapters.out.memory-anonymous-sessions
  (:require [slopbook.identity.ports.anonymous-sessions :as session-port]))

(defn adapter [state now]
  (session-port/assert-adapter!
   {:insert! (fn [value]
               (swap! state assoc-in [:anonymous-sessions (:id value)] value)
               value)
    :find! (fn [id] (get-in @state [:anonymous-sessions id]))
    :touch! (fn [id]
              (when (= "active" (get-in @state [:anonymous-sessions id :status]))
                (swap! state assoc-in [:anonymous-sessions id :last_used_at] (now))
                true))}))
