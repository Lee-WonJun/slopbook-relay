(ns slopbook.media.adapters.out.image-processor
  (:require [clojure.java.io :as io]
            [slopbook.media.domain.asset :as asset]
            [slopbook.media.ports.processor :as processor-port]
            [slopbook.platform.errors :as errors])
  (:import [java.awt RenderingHints]
           [java.awt.image BufferedImage]
           [java.io ByteArrayOutputStream File]
           [java.lang ProcessBuilder$Redirect]
           [java.nio ByteBuffer ByteOrder]
           [java.nio.file Files Path]
           [java.nio.file.attribute PosixFilePermissions]
           [java.util.concurrent TimeUnit]
           [javax.imageio ImageIO]))

(def jpeg-magic [0xff 0xd8 0xff])
(def png-magic [0x89 0x50 0x4e 0x47 0x0d 0x0a 0x1a 0x0a])

(defn- unsigned [value] (bit-and 0xff value))
(defn- unsigned-short [value] (bit-and 0xffff value))
(defn- bytes-match? [^bytes data offset expected]
  (and (<= (+ offset (count expected)) (alength data))
       (every? true? (map-indexed #(= %2 (unsigned (aget data (+ offset %1)))) expected))))

(defn- u32-be [^bytes data offset]
  (long (bit-or (bit-shift-left (unsigned (aget data offset)) 24)
                (bit-shift-left (unsigned (aget data (inc offset))) 16)
                (bit-shift-left (unsigned (aget data (+ offset 2))) 8)
                (unsigned (aget data (+ offset 3))))))

(defn- u32-le [^bytes data offset]
  (long (bit-or (unsigned (aget data offset))
                (bit-shift-left (unsigned (aget data (inc offset))) 8)
                (bit-shift-left (unsigned (aget data (+ offset 2))) 16)
                (bit-shift-left (unsigned (aget data (+ offset 3))) 24))))

(defn- u24-le [^bytes data offset]
  (bit-or (unsigned (aget data offset))
          (bit-shift-left (unsigned (aget data (inc offset))) 8)
          (bit-shift-left (unsigned (aget data (+ offset 2))) 16)))

(defn- ascii [^bytes data offset length]
  (String. data offset length java.nio.charset.StandardCharsets/US_ASCII))

(defn- png-descriptor [^bytes data]
  (when-not (and (bytes-match? data 0 png-magic) (>= (alength data) 33)
                 (= "IHDR" (ascii data 12 4)))
    (errors/fail! 400 "MEDIA_TYPE_UNSUPPORTED" :validation))
  (let [width (u32-be data 16)
        height (u32-be data 20)]
    (loop [offset 8 animated? false ended? false]
      (if ended?
        (when-not (= offset (alength data))
          (errors/fail! 400 "MEDIA_DECODE_FAILED" :validation))
        (do
          (when (> (+ offset 12) (alength data))
            (errors/fail! 400 "MEDIA_DECODE_FAILED" :validation))
          (let [length (u32-be data offset)
                type (ascii data (+ offset 4) 4)
                next (+ offset 12 length)]
            (when (> next (alength data))
              (errors/fail! 400 "MEDIA_DECODE_FAILED" :validation))
            (when (= "acTL" type)
              (errors/fail! 400 "MEDIA_ANIMATED_UNSUPPORTED" :validation))
            (recur next animated? (= "IEND" type))))))
    {:verified-type "png" :width width :height height :orientation 1}))

(defn- webp-dimensions [^bytes data offset length type]
  (case type
    "VP8X" (when (>= length 10)
             (when (pos? (bit-and 0x02 (unsigned (aget data offset))))
               (errors/fail! 400 "MEDIA_ANIMATED_UNSUPPORTED" :validation))
             [(inc (u24-le data (+ offset 4))) (inc (u24-le data (+ offset 7)))])
    "VP8 " (when (and (>= length 10) (bytes-match? data (+ offset 3) [0x9d 0x01 0x2a]))
             [(bit-and 0x3fff (bit-or (unsigned (aget data (+ offset 6)))
                                     (bit-shift-left (unsigned (aget data (+ offset 7))) 8)))
              (bit-and 0x3fff (bit-or (unsigned (aget data (+ offset 8)))
                                     (bit-shift-left (unsigned (aget data (+ offset 9))) 8)))])
    "VP8L" (when (and (>= length 5) (= 0x2f (unsigned (aget data offset))))
             (let [bits (u32-le data (inc offset))]
               [(inc (bit-and bits 0x3fff))
                (inc (bit-and (bit-shift-right bits 14) 0x3fff))]))
    nil))

(defn- webp-descriptor [^bytes data]
  (when-not (and (>= (alength data) 20)
                 (= "RIFF" (ascii data 0 4)) (= "WEBP" (ascii data 8 4))
                 (= (alength data) (+ 8 (u32-le data 4))))
    (errors/fail! 400 "MEDIA_TYPE_UNSUPPORTED" :validation))
  (loop [offset 12 dimensions nil]
    (if (= offset (alength data))
      (if dimensions
        {:verified-type "webp" :width (first dimensions) :height (second dimensions)
         :orientation 1}
        (errors/fail! 400 "MEDIA_DECODE_FAILED" :validation))
      (do
        (when (> (+ offset 8) (alength data))
          (errors/fail! 400 "MEDIA_DECODE_FAILED" :validation))
        (let [type (ascii data offset 4)
              length (u32-le data (+ offset 4))
              data-offset (+ offset 8)
              next (+ data-offset length (mod length 2))]
          (when (or (> next (alength data)) (contains? #{"ANIM" "ANMF"} type))
            (errors/fail! 400 (if (contains? #{"ANIM" "ANMF"} type)
                                "MEDIA_ANIMATED_UNSUPPORTED" "MEDIA_DECODE_FAILED") :validation))
          (recur next (or dimensions (webp-dimensions data data-offset length type))))))))

(defn- exif-orientation [^bytes data]
  (try
    (let [offset (if (bytes-match? data 0 [0x45 0x78 0x69 0x66 0 0]) 6 0)
          order-token (ascii data offset 2)
          order (case order-token "II" ByteOrder/LITTLE_ENDIAN "MM" ByteOrder/BIG_ENDIAN nil)]
      (when order
        (let [buffer (doto (ByteBuffer/wrap data) (.order order))
              ifd (+ offset (.getInt buffer (+ offset 4)))
              count (unsigned-short (.getShort buffer ifd))]
          (some (fn [index]
                  (let [entry (+ ifd 2 (* index 12))]
                    (when (and (<= (+ entry 12) (alength data))
                               (= 0x0112 (unsigned-short (.getShort buffer entry))))
                      (let [value (unsigned-short (.getShort buffer (+ entry 8)))]
                        (when (<= 1 value 8) value)))))
                (range count)))))
    (catch Exception _ nil)))

(defn- jpeg-orientation [^bytes data]
  (loop [offset 2]
    (if (> (+ offset 4) (alength data))
      1
      (let [marker (unsigned (aget data (inc offset)))
            length (bit-or (bit-shift-left (unsigned (aget data (+ offset 2))) 8)
                           (unsigned (aget data (+ offset 3))))
            next (+ offset 2 length)]
        (if (or (< length 2) (> next (alength data)))
          1
          (if (and (= marker 0xe1) (bytes-match? data (+ offset 4) [0x45 0x78 0x69 0x66 0 0]))
            (or (exif-orientation
                 (java.util.Arrays/copyOfRange data (+ offset 4) next)) 1)
            (if (or (= marker 0xda) (= marker 0xd9))
              1
              (recur next))))))))

(defn- jpeg-descriptor [^bytes data file]
  (when-not (and (bytes-match? data 0 jpeg-magic)
                 (>= (alength data) 4)
                 (= 0xff (unsigned (aget data (- (alength data) 2))))
                 (= 0xd9 (unsigned (aget data (dec (alength data))))))
    (errors/fail! 400 "MEDIA_TYPE_UNSUPPORTED" :validation))
  (try
    (with-open [stream (ImageIO/createImageInputStream ^File file)]
      (let [readers (ImageIO/getImageReaders stream)]
        (when-not (.hasNext readers)
          (errors/fail! 400 "MEDIA_DECODE_FAILED" :validation))
        (let [reader (.next readers)]
          (try
            (.setInput reader stream true true)
            {:verified-type "jpeg" :width (.getWidth reader 0) :height (.getHeight reader 0)
             :orientation (jpeg-orientation data)}
            (finally (.dispose reader))))))
    (catch clojure.lang.ExceptionInfo exception (throw exception))
    (catch Exception _ (errors/fail! 400 "MEDIA_DECODE_FAILED" :validation))))

(defn- assert-dimensions! [{:keys [width height] :as descriptor}]
  (when-not (and (pos? width) (pos? height)
                 (<= width asset/max-edge) (<= height asset/max-edge)
                 (<= (* (long width) (long height)) asset/max-pixels))
    (errors/fail! 400 "MEDIA_DIMENSIONS_EXCEEDED" :validation))
  descriptor)

(defn- descriptor [upload]
  (let [file ^File (:tempfile upload)
        size (:size upload)]
    (when-not (and file (.isFile file) (<= 1 size asset/max-file-bytes))
      (errors/fail! (if (> (or size 0) asset/max-file-bytes) 413 400)
                    (if (> (or size 0) asset/max-file-bytes) "MEDIA_TOO_LARGE" "MEDIA_DECODE_FAILED")
                    :validation))
    (let [data (Files/readAllBytes (.toPath file))
          value (cond
                  (bytes-match? data 0 jpeg-magic) (jpeg-descriptor data file)
                  (bytes-match? data 0 png-magic) (png-descriptor data)
                  (and (>= (alength data) 12) (= "RIFF" (ascii data 0 4))) (webp-descriptor data)
                  :else (errors/fail! 400 "MEDIA_TYPE_UNSUPPORTED" :validation))
          declared (:content-type upload)
          expected ({"jpeg" "image/jpeg" "png" "image/png" "webp" "image/webp"}
                    (:verified-type value))]
      (when-not (= expected declared)
        (errors/fail! 400 "MEDIA_TYPE_UNSUPPORTED" :validation))
      (assert-dimensions! value))))

(defn- secure-temp [suffix]
  (let [path (Files/createTempFile "slopbook-media-" suffix (make-array java.nio.file.attribute.FileAttribute 0))]
    (try (Files/setPosixFilePermissions path (PosixFilePermissions/fromString "rw-------"))
         (catch UnsupportedOperationException _))
    (.toFile path)))

(defn- run-tool! [args timeout-seconds]
  (let [process (-> (ProcessBuilder. ^java.util.List args)
                    (.redirectOutput ProcessBuilder$Redirect/DISCARD)
                    (.redirectError ProcessBuilder$Redirect/DISCARD)
                    .start)]
    (when-not (.waitFor process timeout-seconds TimeUnit/SECONDS)
      (.destroyForcibly process)
      (errors/fail! 503 "MEDIA_PROCESSING_FAILED" :internal))
    (when-not (zero? (.exitValue process))
      (errors/fail! 400 "MEDIA_DECODE_FAILED" :validation))))

(defn- target-dimensions [width height edge]
  (if (<= (max width height) edge)
    [width height]
    (if (>= width height)
      [edge (max 1 (int (Math/round (* height (/ (double edge) width)))))]
      [(max 1 (int (Math/round (* width (/ (double edge) height))))) edge])))

(defn- rotate [^BufferedImage source orientation]
  (let [swap? (contains? #{5 6 7 8} orientation)
        width (.getWidth source) height (.getHeight source)
        target (BufferedImage. (if swap? height width) (if swap? width height)
                               (if (.hasAlpha (.getColorModel source))
                                 BufferedImage/TYPE_INT_ARGB BufferedImage/TYPE_INT_RGB))
        graphics (.createGraphics target)]
    (try
      (case orientation
        2 (do (.translate graphics width 0) (.scale graphics -1 1))
        3 (do (.translate graphics width height) (.rotate graphics Math/PI))
        4 (do (.translate graphics 0 height) (.scale graphics 1 -1))
        5 (do (.rotate graphics (/ Math/PI 2)) (.scale graphics 1 -1))
        6 (do (.translate graphics height 0) (.rotate graphics (/ Math/PI 2)))
        7 (do (.translate graphics height width) (.scale graphics -1 1)
              (.rotate graphics (/ (* 3 Math/PI) 2)))
        8 (do (.translate graphics 0 width) (.rotate graphics (- (/ Math/PI 2))))
        nil)
      (.drawImage graphics source 0 0 nil)
      target
      (finally (.dispose graphics)))))

(defn- resize [^BufferedImage source width height]
  (if (and (= width (.getWidth source)) (= height (.getHeight source)))
    source
    (let [target (BufferedImage. width height
                                 (if (.hasAlpha (.getColorModel source))
                                   BufferedImage/TYPE_INT_ARGB BufferedImage/TYPE_INT_RGB))
          graphics (.createGraphics target)]
      (try
        (.setRenderingHint graphics RenderingHints/KEY_INTERPOLATION
                           RenderingHints/VALUE_INTERPOLATION_BICUBIC)
        (.setRenderingHint graphics RenderingHints/KEY_RENDERING RenderingHints/VALUE_RENDER_QUALITY)
        (.drawImage graphics source 0 0 width height nil)
        target
        (finally (.dispose graphics))))))

(defn- center-square [^BufferedImage source]
  (let [size (min (.getWidth source) (.getHeight source))
        x (quot (- (.getWidth source) size) 2)
        y (quot (- (.getHeight source) size) 2)
        target (BufferedImage. size size
                               (if (.hasAlpha (.getColorModel source))
                                 BufferedImage/TYPE_INT_ARGB BufferedImage/TYPE_INT_RGB))
        graphics (.createGraphics target)]
    (try
      (.drawImage graphics source 0 0 size size x y (+ x size) (+ y size) nil)
      target
      (finally (.dispose graphics)))))

(defn- encode-webp [^BufferedImage image quality placeholder?]
  (let [png (secure-temp ".png")
        webp (secure-temp ".webp")
        alpha? (.hasAlpha (.getColorModel image))]
    (try
      (when-not (ImageIO/write image "png" png)
        (errors/fail! 503 "MEDIA_PROCESSING_FAILED" :internal))
      (run-tool!
       (vec (concat ["cwebp" "-quiet" "-metadata" "none" "-mt" "-low_memory"]
                    (if alpha? ["-lossless" "-q" "100"]
                        ["-preset" "photo" "-q" (str quality)])
                    ["-o" (.getAbsolutePath webp) "--" (.getAbsolutePath png)]))
       (if placeholder? 10 20))
      (Files/readAllBytes (.toPath webp))
      (finally
        (Files/deleteIfExists (.toPath png))
        (Files/deleteIfExists (.toPath webp))))))

(defn- average-color [^BufferedImage image]
  (let [step (max 1 (quot (max (.getWidth image) (.getHeight image)) 64))
        values (for [y (range 0 (.getHeight image) step)
                     x (range 0 (.getWidth image) step)] (.getRGB image x y))
        total (max 1 (count values))
        channel (fn [shift] (quot (reduce + (map #(bit-and 0xff (bit-shift-right % shift)) values)) total))]
    (format "#%02X%02X%02X" (channel 16) (channel 8) (channel 0))))

(defn- normalized [upload {:keys [width height orientation verified-type purpose]}]
  (let [master-webp (secure-temp ".webp")
        master-png (secure-temp ".png")
        avatar? (= "avatar" purpose)
        master-edge (if avatar? 512 1440)
        [master-width master-height] (target-dimensions width height master-edge)]
    (try
      (run-tool! ["cwebp" "-quiet" "-metadata" "none" "-lossless" "-q" "100"
                  "-resize" (str master-width) (str master-height)
                  "-o" (.getAbsolutePath master-webp) "--"
                  (.getAbsolutePath ^File (:tempfile upload))] 25)
      (run-tool! ["dwebp" "-quiet" (.getAbsolutePath master-webp)
                  "-o" (.getAbsolutePath master-png)] 15)
      (let [decoded (or (ImageIO/read master-png)
                        (errors/fail! 400 "MEDIA_DECODE_FAILED" :validation))
            oriented (rotate decoded orientation)
            image (if avatar? (center-square oriented) oriented)
            oriented-width (.getWidth image)
            oriented-height (.getHeight image)
            dimensions (->> (if avatar? [128 256 512] [480 960 1440])
                            (map #(target-dimensions (.getWidth image) (.getHeight image) %))
                            distinct vec)
            variants (mapv (fn [[variant-width variant-height]]
                             {:width variant-width :height variant-height
                              :bytes (encode-webp (resize image variant-width variant-height) 82 false)})
                           dimensions)
            [placeholder-width placeholder-height]
            (target-dimensions (.getWidth image) (.getHeight image) 24)]
        {:verified-type verified-type
         :width oriented-width :height oriented-height
         :variants variants
         :placeholder {:width placeholder-width :height placeholder-height
                       :bytes (encode-webp (resize image placeholder-width placeholder-height) 45 true)}
         :average-color (average-color image)})
      (finally
        (Files/deleteIfExists (.toPath master-webp))
        (Files/deleteIfExists (.toPath master-png))))))

(defn adapter []
  (processor-port/assert-adapter!
   {:validate! descriptor
    :normalize! normalized}))
