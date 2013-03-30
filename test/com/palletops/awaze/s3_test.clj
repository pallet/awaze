(ns com.palletops.awaze.s3-test
  (:require
   [clojure.test :refer :all]
   [com.palletops.awaze.s3 :refer :all]))

(deftest simple-test
  (is (map? (list-buckets-map {:access-key "a" :secret-key "b"}))))
