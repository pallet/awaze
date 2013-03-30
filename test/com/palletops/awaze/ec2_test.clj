(ns com.palletops.awaze.ec2-test
  (:require
   [clojure.test :refer :all]
   [com.palletops.awaze.ec2 :refer :all]))

(deftest simple-test
  (is (map? (describe-instances-map {:access-key "a" :secret-key "b"}))))
