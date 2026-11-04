(ns slopbook.client.ime
  (:require [reagent.core :as r]))

(defn value-or-empty [value]
  (or value ""))

(defn initial-state [value]
  {:draft (value-or-empty value)
   :composing? false
   :pending nil})

(defn begin-composition [state]
  (assoc state :composing? true))

(defn change-value [state value]
  (let [value (value-or-empty value)]
    (cond-> (assoc state :draft value)
      (not (:composing? state)) (assoc :pending value))))

(defn end-composition [state value]
  (let [value (value-or-empty value)]
    (assoc state :draft value :composing? false :pending value)))

(defn reconcile [state external]
  (let [external (value-or-empty external)]
    (cond
      (:composing? state) state
      (= external (:pending state)) (assoc state :draft external :pending nil)
      (some? (:pending state)) state
      (= external (:draft state)) state
      :else (assoc state :draft external))))

(defn field
  "A controlled input with an IME-local composition buffer. `tag` may include
  Hiccup id/classes (for example `:input#query.search-input`)."
  [tag props external on-value]
  (r/with-let [state (r/atom (initial-state external))]
    (swap! state reconcile external)
    [tag
     (assoc props
            :value (:draft @state)
            :on-composition-start
            (fn [_]
              (swap! state begin-composition))
            :on-change
            (fn [event]
              (let [value (.. event -target -value)
                    composing? (:composing? @state)]
                (swap! state change-value value)
                (when-not composing?
                  (on-value value))))
            :on-composition-end
            (fn [event]
              (let [value (.. event -target -value)]
                (swap! state end-composition value)
                (on-value value))))]))
