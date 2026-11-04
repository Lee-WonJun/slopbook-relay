(ns slopbook.media.application.assets
  (:require [slopbook.identity.application.characters :as characters]
            [slopbook.media.domain.asset :as asset]
            [slopbook.media.ports.assets :as asset-port]
            [slopbook.media.ports.processor :as processor-port]
            [slopbook.media.ports.storage :as storage-port]
            [slopbook.platform.errors :as errors])
  (:import [java.nio.file Files]))

(defn- delete-upload! [upload]
  (when-let [file (:tempfile upload)]
    (try (Files/deleteIfExists (.toPath ^java.io.File file)) (catch Exception _))))

(defn- failure-state [exception]
  (let [{:keys [code details]} (ex-data exception)]
    {:status (cond
               (contains? #{"MEDIA_TYPE_UNSUPPORTED" "MEDIA_DECODE_FAILED"
                            "MEDIA_DIMENSIONS_EXCEEDED" "MEDIA_ANIMATED_UNSUPPORTED"
                            "MEDIA_TOO_LARGE"} code) "rejected"
               :else "failed")
     :error-code (or code "MEDIA_PROCESSING_FAILED")
     :safety-reason (:safety_reason details)}))

(defn- generic-alt [language purpose]
  (let [avatar? (= "avatar" purpose)]
    (get-in {"en" {:avatar "User-provided profile image" :media "User-uploaded image"}
             "ko" {:avatar "사용자가 등록한 프로필 이미지" :media "사용자가 첨부한 이미지"}
             "zh-Hans" {:avatar "用户上传的头像" :media "用户上传的图片"}
             "ja" {:avatar "ユーザーが登録したプロフィール画像" :media "ユーザーが添付した画像"}}
            [language (if avatar? :avatar :media)])))

(defn- upload-variant! [storage media-id descriptor placeholder?]
  (let [key (str "media/" media-id "/"
                 (if placeholder? 24 (:width descriptor)) ".webp")
        stored (storage-port/put! storage {:key key :bytes (:bytes descriptor)})]
    (merge (select-keys descriptor [:width :height]) stored
           (when placeholder? {:placeholder true}))))

(defn- delete-storage! [storage keys]
  (try
    (storage-port/delete! storage keys)
    true
    (catch Exception _ false)))

(defn- process! [{:keys [store processor storage]} value upload descriptor]
  (let [uploaded (atom [])]
    (try
      (asset-port/mark-processing! store (:id value))
      (let [normalized (processor-port/normalize! processor upload
                                                  (assoc descriptor :purpose (:purpose value)))
            sources (mapv (fn [variant]
                            (let [stored (upload-variant! storage (:id value) variant false)]
                              (swap! uploaded conj (:key stored))
                              stored))
                          (:variants normalized))
            placeholder (upload-variant! storage (:id value) (:placeholder normalized) true)
            _ (swap! uploaded conj (:key placeholder))
            result (asset-port/mark-ready!
                    store {:id (:id value) :verified-type (:verified-type normalized)
                           :width (:width normalized) :height (:height normalized)
                           :variants (conj sources placeholder)
                           :average-color (:average-color normalized)
                           :alt-text (generic-alt (:output_language value) (:purpose value))
                           :alt-language (:output_language value)})]
        (when-not result
          (errors/fail! 503 "MEDIA_PROCESSING_FAILED" :internal))
        result)
      (catch Exception exception
        (let [keys @uploaded
              purged? (or (empty? keys) (delete-storage! storage keys))]
          (asset-port/mark-failed!
           store (cond-> (assoc (failure-state exception) :id (:id value))
                   (and (seq keys) (not purged?)) (assoc :purge-keys keys))))
        nil)
      (finally (delete-upload! upload)))))

(defn upload! [{:keys [store processor executor-submit new-id now identity] :as media}
               account-id multipart]
  (let [actor-id (get multipart "actor_character_id")
        purpose (get multipart "purpose")
        output-language (get multipart "output_language")
        upload (get multipart "file")]
    (when-not (and (map? upload)
                   (asset/valid-request? {:purpose purpose :output-language output-language
                                          :size (:size upload)}))
      (delete-upload! upload)
      (errors/fail! 400 "MEDIA_UPLOAD_INVALID" :validation))
    (try
      (let [actor (characters/require-owned-internal identity account-id actor-id)
            descriptor (processor-port/validate! processor upload)
            value {:id (new-id) :owner_character_id (:id actor) :purpose purpose
                   :output_language output-language :status "validating" :created_at (now)}
            public (asset-port/insert-upload! store value)]
        (when-not (executor-submit #(process! media value upload descriptor))
          (asset-port/mark-failed! store {:id (:id value) :status "failed"
                                          :error-code "MEDIA_BUSY"})
          (delete-upload! upload)
          (errors/fail! 429 "MEDIA_BUSY" :rate-limit))
        public)
      (catch Exception exception
        (delete-upload! upload)
        (throw exception)))))

(defn find! [{:keys [store identity] :as media} account-id actor-id media-id]
  (let [actor (characters/require-owned-internal identity account-id actor-id)]
    (or (asset-port/find-owned store {:id media-id :owner-character-id (:id actor)})
        (errors/fail! 404 "MEDIA_NOT_FOUND" :not-found))))

(declare purge-keys!)

(defn delete! [{:keys [store storage identity]} account-id media-id body]
  (errors/assert-allowed-keys! body #{:actor_character_id})
  (let [actor (characters/require-owned-internal identity account-id (:actor_character_id body))
        result (or (asset-port/delete-unattached!
                    store {:id media-id :owner-character-id (:id actor)})
                   (errors/fail! 404 "MEDIA_NOT_FOUND" :not-found))]
    (purge-keys! {:store store :storage storage} [result])
    true))

(defn purge-keys! [{:keys [store storage]} values]
  (let [items (cond
                (empty? values) []
                (map? (first values)) values
                :else [{:id nil :keys (vec values)}])]
    (doseq [{:keys [id keys]} items]
      (when (seq keys)
        (if (delete-storage! storage keys)
          (when id (asset-port/mark-purged! store id))
          (when id (asset-port/mark-purge-failed! store id "MEDIA_STORAGE_FAILED"))))))
  true)

(defn sweep! [{:keys [store] :as media}]
  (asset-port/expire-unattached! store)
  (purge-keys! media (asset-port/pending-purges store 100))
  true)

(defn start-sweeper! [media]
  (let [running? (atom true)
        worker (future
                 (while @running?
                   (try (sweep! media) (catch Exception _))
                   (when @running?
                     (try (Thread/sleep 60000)
                          (catch InterruptedException _)))))]
    {:running? running? :worker worker}))

(defn stop-sweeper! [{:keys [running? worker]}]
  (when running? (reset! running? false))
  (when worker (future-cancel worker))
  true)

(defn ready-assets!
  [{:keys [store]} actor-id purpose media-ids]
  (let [ids (vec (or media-ids []))]
    (when (or (> (count ids) (if (= "avatar" purpose) 1 4))
              (not= (count ids) (count (distinct ids))))
      (errors/fail! 400 "MEDIA_LIMIT_EXCEEDED" :validation))
    (if (empty? ids)
      []
      (let [rows (asset-port/ready-owned
                  store {:ids ids :owner-character-id actor-id :purpose purpose})
            by-id (into {} (map (juxt :id identity) rows))]
        (when-not (= (count ids) (count rows))
          (errors/fail! 409 "MEDIA_NOT_READY" :conflict))
        (mapv by-id ids)))))
