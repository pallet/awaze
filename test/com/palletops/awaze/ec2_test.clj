(ns com.palletops.awaze.ec2-test
  (:require
   [clojure.test :refer :all]
   [com.palletops.awaze.ec2 :refer [describe-instances-map]]))

(deftest simple-test
  (is (map? (describe-instances-map {:access-key "a" :secret-key "b"})))
  (is (= 2 (count (-> #'describe-instances-map meta :arglists)))))
