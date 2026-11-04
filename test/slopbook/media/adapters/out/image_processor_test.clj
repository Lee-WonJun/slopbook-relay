(ns slopbook.media.adapters.out.image-processor-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopbook.media.adapters.out.image-processor :as image-processor]
            [slopbook.media.domain.asset :as asset]
            [slopbook.media.ports.processor :as processor])
  (:import [java.awt Color]
           [java.awt.image BufferedImage]
           [java.io File]
           [java.nio.file Files]
           [javax.imageio ImageIO]))

(defn- fixture-file [format]
  (let [file (File/createTempFile "slopbook-image-test-" (str "." format))
        image (BufferedImage. 4 3 BufferedImage/TYPE_INT_RGB)
        graphics (.createGraphics image)]
    (try
      (.setColor graphics (Color. 17 34 51))
      (.fillRect graphics 0 0 4 3)
      (finally (.dispose graphics)))
    (when-not (ImageIO/write image format file)
      (throw (ex-info "ImageIO fixture encoder missing" {:format format})))
    file))

(defn- upload [^File file content-type]
  {:tempfile file :size (.length file) :content-type content-type})

(defn- failure-code [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo exception (:code (ex-data exception)))))

(defn- write-bytes! [^bytes bytes]
  (let [file (File/createTempFile "slopbook-image-bytes-" ".bin")]
    (Files/write (.toPath file) bytes (make-array java.nio.file.OpenOption 0))
    file))

(defn- png-with-trailing-bytes [^File source]
  (let [bytes (Files/readAllBytes (.toPath source))
        result (byte-array (+ (alength bytes) 4))]
    (System/arraycopy bytes 0 result 0 (alength bytes))
    (aset-byte result (alength bytes) (byte 0x50))
    (write-bytes! result)))

(defn- png-with-width [^File source width]
  (let [bytes (aclone (Files/readAllBytes (.toPath source)))]
    (doseq [[offset shift] [[16 24] [17 16] [18 8] [19 0]]]
      (aset-byte bytes offset (unchecked-byte (bit-and 0xff (bit-shift-right width shift)))))
    (write-bytes! bytes)))

(defn- animated-png [^File source]
  (let [bytes (Files/readAllBytes (.toPath source))
        iend-offset (- (alength bytes) 12)
        chunk (byte-array 20)
        type (.getBytes "acTL" java.nio.charset.StandardCharsets/US_ASCII)
        result (byte-array (+ (alength bytes) (alength chunk)))]
    ;; 8-byte acTL payload; CRC does not matter because animation is rejected
    ;; before the image reaches a decoder.
    (aset-byte chunk 3 (byte 8))
    (System/arraycopy type 0 chunk 4 4)
    (System/arraycopy bytes 0 result 0 iend-offset)
    (System/arraycopy chunk 0 result iend-offset (alength chunk))
    (System/arraycopy bytes iend-offset result (+ iend-offset (alength chunk)) 12)
    (write-bytes! result)))

(deftest supported-magic-and-declared-mime-must-agree
  (let [adapter (image-processor/adapter)
        png (fixture-file "png")
        jpeg (fixture-file "jpg")]
    (try
      (is (= {:verified-type "png" :width 4 :height 3 :orientation 1}
             (processor/validate! adapter (upload png "image/png"))))
      (is (= "jpeg" (:verified-type
                      (processor/validate! adapter (upload jpeg "image/jpeg")))))
      (is (= "MEDIA_TYPE_UNSUPPORTED"
             (failure-code #(processor/validate! adapter (upload png "image/jpeg")))))
      (is (= "MEDIA_TOO_LARGE"
             (failure-code #(processor/validate!
                             adapter (assoc (upload png "image/png")
                                            :size (inc asset/max-file-bytes))))))
      (finally (.delete png) (.delete jpeg)))))

(deftest polyglot-animation-and-dimension-bombs-are-rejected-before-processing
  (let [adapter (image-processor/adapter)
        source (fixture-file "png")
        trailing (png-with-trailing-bytes source)
        oversized (png-with-width source (inc asset/max-edge))
        animated (animated-png source)
        gif (write-bytes! (.getBytes "GIF89a-not-an-image"
                                     java.nio.charset.StandardCharsets/US_ASCII))]
    (try
      (testing "bytes after the terminal PNG chunk cannot smuggle another format"
        (is (= "MEDIA_DECODE_FAILED"
               (failure-code #(processor/validate! adapter (upload trailing "image/png"))))))
      (testing "header dimensions are capped before full decode"
        (is (= "MEDIA_DIMENSIONS_EXCEEDED"
               (failure-code #(processor/validate! adapter (upload oversized "image/png"))))))
      (testing "APNG and unsupported magic are explicit failures"
        (is (= "MEDIA_ANIMATED_UNSUPPORTED"
               (failure-code #(processor/validate! adapter (upload animated "image/png")))))
        (is (= "MEDIA_TYPE_UNSUPPORTED"
               (failure-code #(processor/validate! adapter (upload gif "image/jpeg"))))))
      (finally
        (doseq [file [source trailing oversized animated gif]] (.delete ^File file))))))
