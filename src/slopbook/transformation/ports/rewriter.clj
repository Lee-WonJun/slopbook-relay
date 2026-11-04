(ns slopbook.transformation.ports.rewriter)

(defn assert-adapter! [adapter]
  (when-not (fn? (:rewrite! adapter))
    (throw (ex-info "Transformation rewriter adapter is incomplete" {:missing [:rewrite!]})))
  adapter)

(defn rewrite! [adapter envelope]
  ((:rewrite! adapter) envelope))

(defn available? [adapter]
  (boolean (and (:available? adapter) ((:available? adapter)))))

(defn capability-snapshot [adapter]
  (when-let [snapshot (:capability-snapshot adapter)]
    (snapshot)))

(defn refresh-capabilities! [adapter]
  (when-let [refresh (:refresh-capabilities! adapter)]
    (refresh)))

(defn rewrite-with-model! [adapter model envelope timeout-ms]
  (if-let [rewrite-model (:rewrite-with-model! adapter)]
    (rewrite-model model envelope timeout-ms)
    (rewrite! adapter envelope)))

(defn eligible-models [adapter]
  (if-let [models (:eligible-models adapter)]
    (models)
    []))

(defn eligible-models-for [adapter envelope]
  (if-let [models (:eligible-models-for adapter)]
    (models envelope)
    (eligible-models adapter)))

(defn credit-status [adapter]
  (if-let [status (:credit-status adapter)]
    (status)
    :unknown))
