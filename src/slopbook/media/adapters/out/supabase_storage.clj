(ns slopbook.media.adapters.out.supabase-storage
  (:require [clojure.string :as str]
            [slopbook.media.ports.storage :as storage-port]
            [slopbook.platform.errors :as errors]
            [slopbook.platform.json :as json])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.time Duration]))

(defn- request-builder [config path]
  (-> (HttpRequest/newBuilder
       (URI/create (str (str/replace (:supabase-url config) #"/$" "") path)))
      (.timeout (Duration/ofSeconds 12))
      (.header "authorization" (str "Bearer " (:supabase-service-role-key config)))
      (.header "apikey" (:supabase-service-role-key config))))

(defn adapter [config]
  (let [client (-> (HttpClient/newBuilder) (.connectTimeout (Duration/ofSeconds 4)) .build)
        bucket (:media-bucket config)
        public-base (str (str/replace (:supabase-url config) #"/$" "")
                         "/storage/v1/object/public/" bucket "/")]
    (storage-port/assert-adapter!
     {:put!
      (fn [{:keys [key bytes]}]
        (let [request (-> (request-builder config (str "/storage/v1/object/" bucket "/" key))
                          (.header "content-type" "image/webp")
                          ;; User media can be deleted. Keep versioned immutable object paths,
                          ;; but bound browser staleness after Storage/CDN invalidation.
                          (.header "cache-control" "300")
                          (.header "x-upsert" "false")
                          (.POST (HttpRequest$BodyPublishers/ofByteArray bytes))
                          .build)
              response (.send client request (HttpResponse$BodyHandlers/discarding))]
          (when-not (<= 200 (.statusCode response) 299)
            (errors/fail! 503 "MEDIA_STORAGE_FAILED" :internal))
          {:key key :url (str public-base key)}))
      :delete!
      (fn [keys]
        (when (seq keys)
          (let [request (-> (request-builder config (str "/storage/v1/object/" bucket))
                            (.header "content-type" "application/json")
                            (.method "DELETE"
                                     (HttpRequest$BodyPublishers/ofString
                                      (json/write-value {:prefixes (vec keys)})))
                            .build)
                response (.send client request (HttpResponse$BodyHandlers/discarding))]
            (when-not (<= 200 (.statusCode response) 299)
              (errors/fail! 503 "MEDIA_STORAGE_FAILED" :internal))))
        true)})))
