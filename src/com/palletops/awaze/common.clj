(ns com.palletops.awaze.common
  "Common components used across clients"
  (:require
   [clojure.java.io :refer [file input-stream]]
   [clojure.string :refer [join lower-case upper-case split] :as string]
   [clojure.pprint :refer [pprint]])
  (:import
   [java.text SimpleDateFormat ParsePosition]
   java.util.Date
   org.joda.time.DateTime
   org.joda.time.base.AbstractInstant
   com.amazonaws.AmazonWebServiceClient
   [com.amazonaws.auth BasicAWSCredentials]
   [com.amazonaws.regions Region Regions]))

(defn camel->dashed
  "Convert a camel cased string to a hyphenated lowercase string. Any `$` is
  treated as a separator and is replaced by a hyphen."
  [s]
  (string/replace
   (->> (split s #"(?<=[a-z])(?=[A-Z$])")
        (map lower-case)
        (join \-))
   "$" ""))

(defn camel->keyword
  [s]
  (keyword (camel->dashed s)))

(defn aws-package?
  [^Class class]
  (re-find #"com\.amazonaws\." (.getName class)))

;;; ## Clojure -> type conversions
(def ^:private date-format (atom "yyyy-MM-dd"))

(defprotocol DateConvertable
  "Protocol for converting to java.util.Date."
  (to-date [x] "Convert the argument to a java.util.Date"))

(extend-protocol DateConvertable
  java.util.Date
  (to-date [x] x)
  AbstractInstant
  (to-date [x] (.toDate x))
  Integer
  (to-date [x] (java.util.Date. (long x)))
  Long
  (to-date [x] (java.util.Date. (long x)))
  String
  (to-date [x] (.. (SimpleDateFormat. @date-format)
                   (parse (str x) (ParsePosition. 0)))))

;;; ## clojure <--> java
(defprotocol ToData
  "Convert Java types to Clojure data. All return values from AWS service calls
  are converted to data."
  (to-data [obj]))

(extend-protocol ToData
  nil
  (to-data [obj]
    nil)
  java.util.Map
  (to-data [obj]
    (if-not (empty? obj)
      (zipmap
       (map #(camel->keyword (name %)) (keys obj))
       (map to-data (vals obj)))))
  java.util.Collection
  (to-data [obj]
    (map to-data obj))
  java.util.Date
  (to-data [obj]
    (DateTime. (.getTime obj)))
  Object
  (to-data [obj]
    (if (aws-package? (class obj))
      (let [b (bean obj)
            ;; filter recursive keys we do not need
            ;; originalRequest is in DescribeSecurityGroupsRequest, etc
            k (filter #{:originalRequest} (keys b))]
        (to-data (apply dissoc b :class k)))
      obj)))

;;; ## Client Factory

;;; This allows the construction of an AWS client based on a keyword
;;; identifying the service, and a map of credentials.

;;; This is the multimethod that should be implemented for each service.
(defmulti aws-client-factory
  "Multimethod to create a service client"
  (fn [service credentials] service))

;;; A wrapper around aws-client-factory that does the common parts of
;;; creating a client service object.
(defn verify-credentials
  [{:keys [access-key secret-key] :as credentials}]
  (assert access-key
          "You must pass a credentials map with an :access-key key.")
  (assert secret-key
          "You must pass a credentials map with a :secret-key key.")
  true)

(defn aws-client
  "Return an aws client for the given aws `service` keyword, specifying
  `:access-key`, `:secret-key` and optionally `:endpoint` as `credentials`."
  [service {:keys [access-key secret-key endpoint] :as credentials}]
  {:pre [(verify-credentials credentials)]}
  (let [^AmazonWebServiceClient client
        (aws-client-factory
         service (BasicAWSCredentials. access-key secret-key))]
    (when endpoint
      (->> (-> (upper-case endpoint)
               (.replaceAll "-" "_"))
           Regions/valueOf
           Region/getRegion
           (.setRegion client)))
    client))

(defmulti aws
  "Defines a multi-method for dispatching data to AWS requests."
  (fn [request] [(::api request) (:fn request)]))
