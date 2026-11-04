(ns slopbook.identity.adapters.out.sigil-raster
  (:require [slopbook.identity.domain.sigil :as sigil])
  (:import [java.awt AlphaComposite Color RenderingHints]
           [java.awt.geom Ellipse2D$Double]
           [java.awt.image BufferedImage]
           [java.io ByteArrayOutputStream]
           [java.lang ProcessBuilder$Redirect]
           [java.nio.file Files]
           [java.util Collections LinkedHashMap]
           [java.util.concurrent TimeUnit]
           [javax.imageio ImageIO]))

(def allowed-sizes #{80 96 224})

(defn- lru-map [capacity]
  (Collections/synchronizedMap
   (proxy [LinkedHashMap] [256 0.75 true]
     (removeEldestEntry [_]
       (> (.size this) capacity)))))

(defonce variant-cache (lru-map 512))

(defn clear-cache! []
  (.clear variant-cache))

(defn png-bytes [seed size]
  (when-not (contains? allowed-sizes size)
    (throw (ex-info "unsupported sigil raster size" {:size size})))
  (let [{:keys [background colors buckets]} (sigil/render-model seed)
        image (BufferedImage. size size BufferedImage/TYPE_INT_ARGB)
        graphics (.createGraphics image)
        scale (/ (double size) 256.0)]
    (try
      (.setRenderingHint graphics RenderingHints/KEY_ANTIALIASING
                         RenderingHints/VALUE_ANTIALIAS_ON)
      (.setComposite graphics AlphaComposite/Src)
      (.setColor graphics (Color/decode background))
      (.fillOval graphics 0 0 size size)
      (doseq [[index particles] (map-indexed vector buckets)]
        (.setComposite graphics (AlphaComposite/getInstance
                                 AlphaComposite/SRC_OVER
                                 (float (+ 0.60 (* index 0.08)))))
        (.setColor graphics (Color/decode (nth colors index)))
        (doseq [{:keys [x y size]} particles]
          (let [radius (* scale size)]
            (.fill graphics
                   (Ellipse2D$Double. (- (* scale x) radius)
                                      (- (* scale y) radius)
                                      (* 2 radius) (* 2 radius))))))
      (.setComposite graphics AlphaComposite/SrcOver)
      (.setColor graphics (Color/decode (first colors)))
      (let [radius (* scale 2.6)]
        (.fill graphics
               (Ellipse2D$Double. (- (/ size 2.0) radius)
                                  (- (/ size 2.0) radius)
                                  (* 2 radius) (* 2 radius))))
      (let [output (ByteArrayOutputStream.)]
        (ImageIO/write image "png" output)
        (.toByteArray output))
      (finally
        (.dispose graphics)))))

(defn- webp-bytes [png]
  (let [input (Files/createTempFile "slopbook-sigil-" ".png"
                                    (make-array java.nio.file.attribute.FileAttribute 0))
        output (Files/createTempFile "slopbook-sigil-" ".webp"
                                     (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (Files/write input png (make-array java.nio.file.OpenOption 0))
      (let [process (-> (ProcessBuilder.
                         ^java.util.List
                         ["cwebp" "-quiet" "-metadata" "none" "-preset" "icon"
                          "-q" "84" "-o" (str output) "--" (str input)])
                        (.redirectOutput ProcessBuilder$Redirect/DISCARD)
                        (.redirectError ProcessBuilder$Redirect/DISCARD)
                        .start)
            completed? (.waitFor process 8 TimeUnit/SECONDS)]
        (when-not completed? (.destroyForcibly process))
        (when (and completed? (zero? (.exitValue process)))
          (Files/readAllBytes output)))
      (catch Exception _ nil)
      (finally
        (Files/deleteIfExists input)
        (Files/deleteIfExists output)))))

(defn variant [seed size]
  (when-not (and (sigil/valid-seed? seed) (contains? allowed-sizes size))
    (throw (ex-info "invalid sigil variant" {:seed-valid (sigil/valid-seed? seed)
                                               :size size})))
  (let [key [seed size]]
    (or (.get variant-cache key)
        (locking variant-cache
          (or (.get variant-cache key)
              (let [png (png-bytes seed size)
                    webp (webp-bytes png)
                    value (if webp
                            {:bytes webp :content-type "image/webp"}
                            {:bytes png :content-type "image/png"})]
                (.put variant-cache key value)
                value))))))
