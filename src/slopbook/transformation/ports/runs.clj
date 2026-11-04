(ns slopbook.transformation.ports.runs)

(defn assert-adapter! [adapter]
  (when-not (fn? (:insert! adapter))
    (throw (ex-info "Transformation run adapter is incomplete" {:missing [:insert!]})))
  adapter)

(defn insert! [adapter metadata]
  ((:insert! adapter) metadata))
