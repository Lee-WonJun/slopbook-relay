(ns slopbook.identity.ports.characters)

(def required-operations
  #{:handle-exists? :insert-if-below-limit! :update! :delete! :list-owned
    :find-public-by-handle :find-internal})

(defn assert-adapter! [adapter]
  (let [missing (remove #(fn? (get adapter %)) required-operations)]
    (when (seq missing)
      (throw (ex-info "Identity character adapter is incomplete" {:missing (vec missing)})))
    adapter))

(defn handle-exists? [adapter handle] ((:handle-exists? adapter) handle))
(defn insert-if-below-limit! [adapter account-id limit value]
  ((:insert-if-below-limit! adapter) account-id limit value))
(defn update! [adapter value] ((:update! adapter) value))
(defn delete! [adapter character-id now] ((:delete! adapter) character-id now))
(defn list-owned [adapter account-id] ((:list-owned adapter) account-id))
(defn find-public-by-handle [adapter handle] ((:find-public-by-handle adapter) handle))
(defn find-internal [adapter character-id] ((:find-internal adapter) character-id))
