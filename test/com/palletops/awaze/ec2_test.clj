(ns com.palletops.awaze.ec2-test
  (:require
   [clojure.test :refer :all]
   [com.palletops.awaze.ec2 :refer [describe-instances-map]]))

(deftest simple-test
  (is (map? (describe-instances-map {:access-key "a" :secret-key "b"})))
  (is (= (set
          ['[credentials]
           '[credentials
             {:keys [next-token max-results instance-ids filters]}]])
         (-> #'describe-instances-map meta :arglists set))))
