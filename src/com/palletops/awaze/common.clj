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


(defn- camel->dashed-symbol
  [s]
  (symbol (camel->dashed s)))

(defn dotted-last
  "Return the last part of a dotted string"
  [s]
  (last (split s #"\.")))

(defn camel->keyword
  [s]
  (keyword (camel->dashed s)))

(defn aws-ns-kw [^Class class]
  (assert (.startsWith (.getName class) "com.amazonaws."))
  (let [segs (split (.getName class) #"\.")
        tl (nth segs 2)]
    (cond
     (= "services" tl) (keyword (nth segs 3))
     (re-matches #"[A-Z].*" tl) :root
     :else (keyword tl))))

(defn type->symbol
  "Return a symbol based on a dashed version of the type's class name."
  [^Class class]
  (symbol (camel->dashed (dotted-last (.getName class)))))

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
    ;; (binding [*out* *err*]
    ;;   (println "to-data MAP" (pr-str (type obj)) (pr-str obj)))
    ;; (flush)
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
    ;; (binding [*out* *err*]
    ;;   (println "to-data OBJECT" (pr-str (type obj)) (pr-str obj)))
    ;; (flush)
    (if (aws-package? (class obj))
      (let [b (bean obj)
            ;; filter recursive keys we do not need
            ;; originalRequest is in DescribeSecurityGroupsRequest, etc
            k (filter #{:originalRequest :originalRequestObject :readLimitInfo}
                      (keys b))]
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
  [service {:keys [access-key secret-key endpoint region] :as credentials}]
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
    (when region
      (->> region
           Regions/fromName
           Region/getRegion
           (.setRegion client)))
    client))

(defmulti aws
  "Defines a multi-method for dispatching data to AWS requests."
  (fn [request] [(::api request) (:fn request)]))

;;; # Value Coercions

;;; ## Clojure -> type conversions
(def ^:private date-format (atom "yyyy-MM-dd"))

(def ^:private coercion-syms
  (atom
   {String `str
    Integer `int
    Integer/TYPE `int
    Long `long
    Long/TYPE `long
    Boolean `boolean
    Boolean/TYPE `boolean
    Double `double
    Double/TYPE `double
    Float `float
    Float/TYPE `float
    BigDecimal `bigdec
    BigInteger `bigint
    Date `to-date
    java.io.File `file
    java.io.InputStream `input-stream
    java.nio.ByteBuffer `identity}))

(defn add-coercions
  "Add mappings of functions to coerce to types.  The function val is used to
  convert values to the key type."
  [coercions]
  (swap! coercions merge coercions))

(defn enum?
  "Predicate to test if type is a predicate"
  [^Class type]
  (= java.lang.Enum (.getSuperclass type)))

(defn abstract? [^Class class]
  (and
   (not (.isEnum class))
   (empty? (seq (.getConstructors class)))))

(defmulti coerce-value-form
  "Return a form that coerces the supplied `value` to `type`."
  (fn [type value] (class type)))

(defmethod coerce-value-form Class
  [^Class type value]
  (cond
   (enum? type) `(~(symbol (name (aws-ns-kw type))
                           (name (type->symbol type)))
                  ~value)
   (.isArray type) (let [arg (gensym "arg")]
                     `(into-array
                       ~(.getComponentType type)
                       (map (fn [~arg]
                              ~(coerce-value-form (.getComponentType type) arg))
                            ~value)))
   (aws-package? type) (if (abstract? type)
                         value
                         `(~(symbol (name (aws-ns-kw type))
                                    (name (type->symbol type)))
                           ~value))

   :else (let [f (get @coercion-syms type)]
           (when-not f
             (println
              "Couldn't find coercion function for"
              (pr-str type) "of type" (class type) (.getName type)))
           (if f
             `(~f ~value)
             value))))
