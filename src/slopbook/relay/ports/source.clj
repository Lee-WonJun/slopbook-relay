(ns slopbook.relay.ports.source)

(defn feed [adapter sort-order] ((:feed adapter) sort-order))
(defn post [adapter id] ((:post adapter) id))
(defn thread [adapter id] ((:thread adapter) id))
