(ns com.palletops.awaze.beans.regions-test
  (:require
   [clojure.test :refer :all]
   [com.palletops.awaze.beans.regions :refer :all]))

(deftest regions-test
  (is (= (com.amazonaws.regions.Region/getRegion
          com.amazonaws.regions.Regions/US_EAST_1)
         (region "us-east-1"))))
