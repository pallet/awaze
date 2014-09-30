(ns com.palletops.awaze.s3-test
  (:require
   [clojure.test :refer :all]
   [com.palletops.awaze.s3 :refer :all]))

(deftest simple-test
  (is (map? (list-buckets-map {:access-key "a" :secret-key "b"})))
  (is (map? (set-bucket-cross-origin-configuration-map
             {:access-key "a" :secret-key "b"}
             "bucket"
             {:rules [{:allowed-methods ["GET"]
                       :allowed-headers ["Accepts"]
                       :allowed-origins ["some-host"]}]})))
  (is (instance?
       com.amazonaws.services.s3.model.CORSRule
       (corsrule
        {:allowed-methods ["GET"]
         :allowed-headers ["Accepts"]
         :allowed-origins ["some-host"]})))
  (is (=
       [com.amazonaws.services.s3.model.CORSRule$AllowedMethods/GET]
       (.getAllowedMethods
        (corsrule
         {:allowed-methods ["GET"]
          :allowed-headers ["Accepts"]
          :allowed-origins ["some-host"]})))))
