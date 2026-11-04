(ns slopbook.platform.errors)

(def safe-messages
  {:invalid-request "요청 형식을 확인해 주세요."
   :validation "입력값을 확인해 주세요."
   :unauthorized "로그인이 필요합니다."
   :auth-unavailable "로그인 확인이 지연되고 있어요. 잠시 후 다시 시도해 주세요."
   :forbidden "이 작업을 수행할 권한이 없습니다."
   :not-found "요청한 항목을 찾을 수 없습니다."
   :conflict "이미 사용 중이거나 현재 상태와 충돌합니다."
   :rate-limit "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."
   :ai-unavailable "캐릭터가 지금은 글을 다듬을 수 없어요. 원문은 이 탭에만 남아 있어요."
   :ai-invalid "캐릭터의 응답을 확인하지 못했어요. 잠시 후 다시 시도해 주세요."
   :internal "잠시 문제가 생겼어요. 다시 시도해 주세요."})

(defn fail!
  ([status code kind]
   (fail! status code kind nil nil))
  ([status code kind details]
   (fail! status code kind details nil))
  ([status code kind details headers]
   (throw (ex-info (name kind)
                   (cond-> {:slopbook/error true
                            :status status
                            :code code
                            :safe-message (get safe-messages kind (:internal safe-messages))}
                     details (assoc :details details)
                     (seq headers) (assoc :headers headers))))))

(defn assert-allowed-keys! [value allowed]
  (let [unknown (seq (remove allowed (keys value)))]
    (when unknown
      (fail! 400 "SCHEMA_UNKNOWN_FIELD" :invalid-request
             {:fields (mapv name unknown)}))))
