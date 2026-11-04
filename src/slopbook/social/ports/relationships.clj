(ns slopbook.social.ports.relationships)

(defn find-by-pair [store actor-id target-id]
  ((:find-by-pair store) actor-id target-id))

(defn save! [store relationship]
  ((:save! store) relationship))

(defn transact! [transaction operation]
  ((:transact! transaction) operation))

(defn publish! [events event]
  ((:publish! events) event))
