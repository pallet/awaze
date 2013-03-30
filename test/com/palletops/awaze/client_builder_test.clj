(ns com.palletops.awaze.client-builder-test
  (:require
   [clojure.test :refer :all]
   [com.palletops.awaze.client-builder :refer :all]))

(deftest required-constructor-args-test
  (is (required-constructor-args
       com.amazonaws.services.s3.model.DeleteObjectsRequest)))

(deftest bean-instance-test
  (is (bean-instance com.amazonaws.services.s3.model.DeleteObjectsRequest)))
