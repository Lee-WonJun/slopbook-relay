(ns slopbook.media.adapters.in.multipart
  (:require [clojure.java.io :as io]
            [ring.middleware.multipart-params :as multipart]
            [slopbook.media.domain.asset :as asset]
            [slopbook.platform.http :as http])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute PosixFilePermissions]))

(defn- secure-store [{:keys [content-type stream]}]
  (let [file (File/createTempFile "slopbook-upload-" ".bin")]
    (try (Files/setPosixFilePermissions (.toPath file) (PosixFilePermissions/fromString "rw-------"))
         (catch UnsupportedOperationException _))
    (io/copy stream file)
    {:content-type content-type :tempfile file :size (.length file)}))

(defn- too-large [request]
  (http/json-response 413 {:error {:code "MEDIA_TOO_LARGE"
                                   :message "The image upload exceeds the allowed size."
                                   :retryable false
                                   :request_id (:request-id request)}}))

(defn wrap [handler]
  (let [parse (multipart/wrap-multipart-params
               handler {:store secure-store
                        :max-file-size asset/max-file-bytes
                        :max-file-count 4
                        :error-handler too-large})]
    (fn [request]
      (if (and (= :post (:request-method request)) (= "/api/v1/media" (:uri request)))
        (let [length (some-> (get-in request [:headers "content-length"]) Long/parseLong)]
          (if (and length (> length asset/max-request-bytes))
            (too-large request)
            (parse request)))
        (handler request)))))
