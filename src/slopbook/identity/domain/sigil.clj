(ns slopbook.identity.domain.sigil
  (:require [clojure.string :as str])
  (:import [java.lang Long]
           [java.util SplittableRandom]))

(def palettes
  [{:background "#152138" :colors ["#F5EFE4" "#9EC6FF" "#4E8CFF" "#C8D8F4" "#7AB7E8"]}
   {:background "#2C1723" :colors ["#FFE9D6" "#FFAD9F" "#E26878" "#F6C6B9" "#C792B2"]}
   {:background "#16271F" :colors ["#F3EEDC" "#A6D5B4" "#5FA782" "#D5E6C7" "#8FC8A2"]}
   {:background "#2A2414" :colors ["#FFF2C2" "#F6D96C" "#C99E3B" "#F4E2A2" "#D7BB69"]}
   {:background "#211A32" :colors ["#F2E9FF" "#C3A7FF" "#816AE8" "#D5C7F1" "#9D8DE0"]}
   {:background "#102A2D" :colors ["#FFF0D8" "#8ED8D1" "#36A6A0" "#F2B88D" "#B8E6DF"]}])

(defn valid-seed? [seed]
  (boolean (re-matches #"[0-9a-f]{64}" (or seed ""))))

(defn- seed-long [seed]
  (Long/parseUnsignedLong (subs seed 0 16) 16))

(defn- decimal [value]
  (String/format java.util.Locale/ROOT
                 "%.2f"
                 (object-array [(double value)])))

(defn- circle-subpath [x y radius]
  (str "M" (decimal (- x radius)) " " (decimal y)
       "a" (decimal radius) " " (decimal radius) " 0 1 0 " (decimal (* 2 radius)) " 0"
       "a" (decimal radius) " " (decimal radius) " 0 1 0 " (decimal (* -2 radius)) " 0"))

(defn- point [^SplittableRandom random void-radius ellipse-ratio tilt]
  (let [angle (* 2.0 Math/PI (.nextDouble random))
        outer-bias (Math/pow (.nextDouble random) 0.62)
        radius (+ void-radius (* (- 118.0 void-radius) outer-bias))
        wobble (+ 1.0 (* 0.11 (- (.nextDouble random) 0.5)))
        raw-x (* radius wobble (Math/cos angle))
        raw-y (* radius ellipse-ratio (Math/sin angle))
        cos-tilt (Math/cos tilt)
        sin-tilt (Math/sin tilt)
        x (+ 128.0 (- (* raw-x cos-tilt) (* raw-y sin-tilt)))
        y (+ 128.0 (+ (* raw-x sin-tilt) (* raw-y cos-tilt)))
        distance (/ radius 118.0)
        size (+ 1.1 (* 1.55 distance) (* 0.75 (.nextDouble random)))]
    {:x x :y y :size size :distance distance}))

(defn render-model [seed]
  (when-not (valid-seed? seed)
    (throw (ex-info "invalid sigil seed" {:seed-valid false})))
  (let [random (SplittableRandom. (seed-long seed))
        palette (nth palettes (mod (bit-and (seed-long seed) 0x7fffffff) (count palettes)))
        void-radius (+ 43.0 (* 17.0 (.nextDouble random)))
        ellipse-ratio (+ 0.78 (* 0.38 (.nextDouble random)))
        tilt (* Math/PI 2.0 (.nextDouble random))
        particle-count (+ 260 (.nextInt random 120))
        buckets (reduce (fn [result _]
                          (let [{:keys [distance] :as particle}
                                (point random void-radius ellipse-ratio tilt)
                                bucket (min 4 (int (* 5.0 distance)))]
                            (update result bucket conj particle)))
                        (vec (repeat 5 []))
                        (range particle-count))]
    {:background (:background palette)
     :colors (:colors palette)
     :buckets buckets}))

(defn svg [seed]
  (let [{:keys [background colors buckets]} (render-model seed)
        paths (map-indexed
               (fn [index particles]
                 (let [path (apply str (map #(circle-subpath (:x %) (:y %) (:size %)) particles))
                       color (nth colors index)
                       opacity (+ 0.60 (* index 0.08))]
                   (str "<path fill=\"" color "\" fill-opacity=\"" (decimal opacity)
                        "\" d=\"" path "\"/>")))
               buckets)]
    (str "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 256 256\" role=\"img\" aria-label=\"Persona Sigil\">"
         "<rect width=\"256\" height=\"256\" rx=\"128\" fill=\"" background "\"/>"
         (str/join paths)
         "<circle cx=\"128\" cy=\"128\" r=\"2.6\" fill=\"" (first colors) "\"/>"
         "</svg>")))
