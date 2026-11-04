(ns slopbook.trust.ports.reports)

(defn insert! [store report] ((:insert! store) report))
