(ns slopbook.identity.adapters.out.sigil-raster-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopbook.identity.adapters.out.sigil-raster :as raster]
            [slopbook.identity.domain.persona :as persona])
  (:import [java.io ByteArrayInputStream]
           [javax.imageio ImageIO]))

(def seed
  (persona/sigil-seed
   "Raster Test"
   {:schema_version 1 :catalog_version 1
    :what_are_you [{:id "helicopter"}]
    :archetypes [{:id "archivist"}]
    :traits [{:id "warm"} {:id "curious"} {:id "meticulous"}]}))

(deftest deterministic-raster-is-bounded-and-cacheable
  (raster/clear-cache!)
  (let [png (raster/png-bytes seed 80)
        image (ImageIO/read (ByteArrayInputStream. png))
        first-value (raster/variant seed 80)
        second-value (raster/variant seed 80)]
    (is (= 80 (.getWidth image)))
    (is (= 80 (.getHeight image)))
    (is (identical? first-value second-value))
    (is (contains? #{"image/webp" "image/png"} (:content-type first-value)))
    (is (pos? (alength ^bytes (:bytes first-value))))
    (is (thrown? clojure.lang.ExceptionInfo (raster/variant seed 81)))))
