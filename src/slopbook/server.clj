(ns slopbook.server
  (:gen-class)
  (:require [slopbook.system :as system]))

(defn -main [& _]
  (let [running (system/start!)]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. #(system/stop! running)))
    @(promise)))
