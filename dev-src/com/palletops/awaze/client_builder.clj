(ns com.palletops.awaze.client-builder
  "Builds a data driven client based on the AWS SDK"
  (:require
   [clojure.java.io :refer [file input-stream]]
   [clojure.string :refer [join lower-case upper-case split] :as string]
   [clojure.tools.logging :refer [infof debugf]]
   [fipp.edn :refer [pprint]]
   [com.palletops.awaze.common
    :refer [aws aws-client aws-client-factory aws-ns-kw aws-package?
            camel->dashed camel->keyword
            coerce-value-form enum?
            to-data to-date type->symbol]])
  (:import
   [java.lang.reflect Constructor Method Modifier ParameterizedType]
   [java.text SimpleDateFormat ParsePosition]
   java.util.Date
   org.joda.time.DateTime
   org.joda.time.base.AbstractInstant
   com.amazonaws.AmazonWebServiceClient
   [com.amazonaws.auth BasicAWSCredentials]
   [com.amazonaws.regions Region Regions]))

(defn public-method?
  [^Method method]
  (Modifier/isPublic (.getModifiers method)))

(defmethod coerce-value-form ParameterizedType
  [^ParameterizedType type value]
  (let [raw (.getRawType type)
        [actual & others] (.getActualTypeArguments type)]
    (with-meta
      (cond
       (or (= raw java.util.List) ((set (ancestors raw)) java.util.List))
       (let [arg (gensym "arg")]
         `(map (fn [~arg] ~(coerce-value-form actual arg)) ~value))

       (or (= raw java.util.Map) ((set (ancestors raw)) java.util.Map))
       (let [arg (gensym "arg")]
         `(zipmap
           (map (fn [~arg] ~(coerce-value-form actual arg)) (keys ~value))
           (map
            (fn [~arg] ~(coerce-value-form (first others) arg))
            (vals ~value))))

       (or (= raw java.util.Collection)
           ((set (ancestors raw)) java.util.Collection))
       (let [arg (gensym "arg")]
         `(map (fn [~arg] ~(coerce-value-form actual arg)) ~value))

       ;; (or (= raw com.amazonaws.services.ec2.model.DryRunSupportedRequest)
       ;;     ((set (ancestors raw))
       ;;      com.amazonaws.services.ec2.model.DryRunSupportedRequest))
       ;; `(assert false
       ;;   "Don't know how to hnadle args with type DryRunSupportedRequest")`

       :else
       (assert
        nil
        (str "Don't know how to handle generic args with raw type "
             raw " "(class raw))))
      {:tag raw})))

;;; # Bean Factories
(defn- select-vararg-overload
  [setters]
  (or (first
       (remove
        #(.isArray ^Class (first (.getParameterTypes ^Method %)))
        setters))
      (first setters)))

(defn- filter-vararg-overloads
  "Filter out overloaded setters that just provide convenience
  functions to other setters."
  [setters]
  (->> (group-by #(.getName ^Method %) setters)
       (map second)
       (map select-vararg-overload)))

(defn- setters
  "Return a sequence of vectors with the hyphenated keywords and setter method
  for the specified `class`."
  [^Class class]
  (->> (.getDeclaredMethods class)
       (filterv public-method?)
       (filterv #(.startsWith (.getName ^Method %) "set"))
       (filterv #(= 1 (count (.getParameterTypes ^Method %))))
       filter-vararg-overloads
       (mapv #(vector (camel->keyword (subs (.getName ^Method %) 3)) %))))

(def primitive-default-value
  {Boolean/TYPE false
   Double/TYPE (double 0.0)
   Float/TYPE (float 0.0)
   Long/TYPE 0
   Integer/TYPE (int 0)})

(defn required-constructor-args
  "When a class has no default constructor, returns a vector with a constructor
  and a sequence of nils to be used as constructor arguments."
  [^Class class]
  (let [sortf (fn [^Constructor c] (count (.getParameterTypes c)))
        [^Constructor ctor & others] (->>
                                      (.getConstructors class)
                                      (sort-by sortf)
                                      (partition-by sortf)
                                      first)]
    (if-let [arglist (and ctor (.getParameterTypes ctor))]
      (if (seq others)
        ;; need to give the compiler type info to pick the correct overload
        [ctor
         (mapv
          #(let [arg (with-meta (gensym "arg")
                       {:tag (symbol (.getName ^Class %))})
                 val (if (aws-package? %)
                       `(~(type->symbol %) {})
                       (primitive-default-value %))]
             [arg val])
          arglist)]
        ;; single overload - just return nils or primitive values
        [ctor (->> arglist
                   (map primitive-default-value)
                   (map #(vector (gensym "arg") %)))]))))

(def static-constructors
  "A map from class name to static constructor functions, for classes that
  have no constructors."
  {"com.amazonaws.regions.Region"
   {:static "getRegion"
    :arg-bean com.amazonaws.regions.Regions}})

(defn has-from-value? [^Class class]
  (try (let [v (.getMethod class "fromValue" (into-array Class [String]))]
         (bit-and (.getModifiers v) Modifier/PUBLIC))
       (catch NoSuchMethodException _)))

(defn has-from-name? [^Class class]
  (try (let [v (.getMethod class "fromName" (into-array Class [String]))]
         (bit-and (.getModifiers v) Modifier/PUBLIC))
       (catch NoSuchMethodException _)))

(defn bean-factory
  "Return a form defining a factory for the specified bean `class`, based on a
  single map argument."
  [^Class class]
  (let [bean (gensym "bean")
        m (gensym "m")
        v (gensym "v")
        class-name (.getName class)]
    `(defn ~(with-meta (type->symbol class) {:tag class})
       [~m]
       ~(cond
         (.isInterface class)
         `(throw (ex-info "Trying to construct an interface"
                          {:type ~class-name
                           :value ~m}))

         (enum? class)
         (cond
          (has-from-value? class) `(. ~class ~'fromValue (name ~m))
          (has-from-name? class) `(. ~class ~'fromName (name ~m))
          :else `(Enum/valueOf ~class (name ~m)))

         (static-constructors class-name)
         (let [s (static-constructors class-name)]
           `(~(symbol class-name (:static s))
             ;; TODO, fix this in a better manner
             ~(coerce-value-form (:arg-bean s) m)))

         :else
         (let [[ctor args] (required-constructor-args class)]
           `(let [~@(apply concat args)
                  ~bean (new ~(symbol class-name)
                             ~@(map first args))]
              ~@(for [[kw ^Method method] (setters class)
                      :let [args (.getGenericParameterTypes method)]
                      :when (= 1 (count args))
                      :let [arg-type (first args)]]
                  `(let [~v (~kw ~m ::undef)]
                     (when-not (= ~v ::undef)
                       (. ~bean
                          ~(symbol (.getName method))
                          ~(coerce-value-form
                            arg-type v)))))
              ~bean))))))

(defn bean-instance
  "Return an instance of the specified bean.  If a value can't be constructed,
  return nil?."
  [^Class class]
  (cond
   (.isInterface class) nil
   (enum? class) nil
   :else
   (if-let [[^Constructor ctor args] (required-constructor-args class)]
     (if ctor
       (let [vargs (seq
                    (->>
                     args
                     (map second)
                     (map (fn [x] ; remove bean constructor expressions
                            (if (or (list? x) (instance? clojure.lang.Cons x))
                              nil
                              x)))))]
         (.newInstance
          ctor
          (into-array Object vargs)))))))

(defmulti setter-arg-type
  "Return a sequence of bean types used in an argument"
  (fn [type] (class type)))

(defmethod setter-arg-type Class
  [^Class type]
  [type])

(defmethod setter-arg-type ParameterizedType
  [^ParameterizedType type]
  (.getActualTypeArguments type))


(defmulti arg-type type)
(defmethod arg-type Class
  [^Class c]
  (if (.isArray c)
    (.getComponentType c)
    c))

(defmethod arg-type java.lang.reflect.TypeVariable
  [^java.lang.reflect.TypeVariable v]
  (arg-type (first (.getBounds v))))


(defn- aws-bean-types
  "For a sequence of `methods`, return a sequence of those arguments that are
  AWS bean types."
  [methods]
  {:pre [(every? #(instance? Method %) methods)]}
  (->> methods
       (mapcat (fn [^Method m]
                 (mapcat
                  (fn [x]
                    (let [cs (setter-arg-type x)]
                      (map arg-type cs)))
                  (.getGenericParameterTypes m))))
       (filter aws-package?)
       (distinct)))

(defn request-beans
  "Return a sequence of bean types, for all beans used (recursively) as
  arguments to the specified `methods`, a map from keyword to Method sequence."
  [methods]
  (loop [beans []
         new-beans (aws-bean-types (mapcat second methods))]
    (let [all-beans (distinct (concat new-beans beans))]
      (if (= beans all-beans)
        beans
        (recur all-beans
               (->> new-beans
                    (mapcat setters)
                    (map second)
                    aws-bean-types))))))

(defn request-beans-by-ns
  "Return a map with a sequence of beans for each
  top level namespace"
  [methods]
  (->> (request-beans methods)
       (group-by aws-ns-kw)))

(defn request-bean-factories
  "Return a map with a sequence of bean factory definitions for each
  top level namespace, for all beans used as arguments to the
  specified `methods`, a map from keyword to Method sequence."
  [beans]
  (infof "r-b-f %s" (pr-str beans))
  (flush)
  (mapv bean-factory beans))

;;; ## Client Factory
(defn client-factory
  "Return a for that implements the aws-client-factory for a given `client-kw`
  identifying the service, and the `client-class` symbol, identifying the class
  that implements the service client."
  [client-kw client-class]
  `(defmethod aws-client-factory ~client-kw
     [_# ^BasicAWSCredentials credentials#]
     (new ~client-class credentials#)))

;;; ## Service Client implementation
(def ^:private excluded
  "Methods to be ignored"
  #{:invoke
    :init
    :set-endpoint
    :get-cached-response-metadata
    :get-service-abbreviation
    :dry-run})

(defn- client-methods
  "Return a map of java.lang.reflect.Methods sequences for `class`, keyed on
   hyphenated keywords corresponding to the public method names."
  [^Class class]
  (reduce
   (fn [m ^Method method]
     (let [fname (camel->keyword (.getName method))]
       (if (or (contains? excluded fname)
               (not (public-method? method)))
         m
         (update-in m [fname] (fnil conj []) method))))
   {}
   (.getDeclaredMethods class)))

(defn convert-args
  [m types]
  (map #(do (coerce-value-form %1 `(nth ~m ~%2))) types (range)))

(defn arg-names
  "Generate argument names for the specified argument types"
  [types]
  (map
   (fn [^Class type n]
     (debugf "arg-names type %s %s" (pr-str type) (aws-package? type))
     (let [s (symbol
              (str (last (split (name (camel->keyword (.getName type))) #"\."))
                   "-" n))]
       (if (aws-package? type)
         (if-let [instance (and
                            (not= type
                                  com.amazonaws.metrics.RequestMetricCollector)
                            (bean-instance type))]
           (do
             (debugf "instance type %s" (pr-str type))
             `{:keys [~@(try
                          ;; Bean on BucketTaggingConfiguration
                          ;; throws in BucketTaggingConfiguration.getTagSet
                          (map
                           (comp symbol name)
                           (keys (dissoc (to-data instance)
                                         :request-credentials
                                         :request-client-options
                                         :request-metric-collector
                                         :dry-run-request)))
                          (catch java.lang.reflect.InvocationTargetException
                              _))]})
           (do
             (debugf "instance type %s no bean-instance" (pr-str type))
             s))
         s)))
   types (range)))

(def arg-name-seq (mapv (comp symbol str) "abcdefghijklmnopqurstuvwxyz"))

(defn no-matching-overloads [f args]
  `(throw
    (ex-info
     (str "No Matching overloads for function " ~(name f))
     {:args ~args})))

(defn client-fn-call
  "Return  a form  that instantiates  a  `client-class` instance  and calls  the
  `client-class` overloaded `methods` with the specified `args`."
  [client-kw client-class methods credentials args]
  (let [client (with-meta (gensym "client")
                 {:tag client-class})
        types (map #(.getGenericParameterTypes ^Method %) methods)
        arities (partition-by count (sort-by count types))
        n (count methods)
        fsym (symbol (.getName ^Method (first methods)))
        call-f (fn [tl] `(to-data (. ~client ~fsym ~@(convert-args args tl))))]
    `(let [~client (aws-client ~client-kw ~credentials)]
       ~(cond
         ;; Just one method, so no choice of overloads
         (= n 1) (call-f (first types))
         ;; Every function has distinct arities, so dispatch on arity
         (every? #(= 1 (count %)) arities)
         `(case (count ~args)
            ~@(mapcat #(vector (count %) (call-f %)) types))
         ;; Functions are overloaded on types for the same arity.  Dispatch
         ;; on arity first, and then handle type overloads.
         :else
         (let [arity-lists (group-by count types)]
           `(case (count ~args)
              ~@(mapcat
                 (fn [[arity types]]
                   (if (= 1 (count types))
                     ;; Single overload for this arity
                     (vector arity (call-f (first types)))
                     ;; Overloads on type
                     (letfn [(try-overload [[tl & types]]
                               ;; We use an atom to record successful type
                               ;; conversions, so we can separate exceptions
                               ;; from type conversion and those from
                               ;; execution.
                               `(let [a# (atom nil)]
                                  (try
                                    ;; Create a let, so we can tag symbols
                                    ;; with types.
                                    (let [~@(mapcat
                                             (fn [^Class type arg i]
                                               [(with-meta arg
                                                  {:tag
                                                   (symbol (.getName type))})
                                                `(coerce-value-form
                                                  ~type
                                                  (nth ~args ~i))])
                                             tl
                                             arg-name-seq
                                             (range))]
                                      (reset! a# true)
                                      (to-data
                                       (. ~client ~fsym
                                          ~@(take arity arg-name-seq))))
                                    (catch Exception e#
                                      (if @a#
                                        (throw e#)
                                        ~(if (seq types)
                                           (try-overload types)
                                           (no-matching-overloads
                                            fsym args)))))))]
                       [arity (try-overload types)])))
                 arity-lists)))))))

(defn client-method
  "Return a clojure function form for the given api methods (which should be
  overloads of the same method name), that expects a credential map and an
  argument map.  Calls the AWS client method using the `:args` key of it's
  second argument."
  [client-kw client-class methods]
  (let [m (gensym "m")
        credentials (gensym "credentials")
        args (gensym "args")]
    `([~m]
        (let [~credentials (:credentials ~m)
              ~args (:args ~m)]
          ~(client-fn-call client-kw client-class methods credentials args)))))

(defn client-map-fn
  "Return a clojure function form that takes the client method arguments
  and returns a map representing the api call, which can be passed to the
  client service's multi-method to actually execute."
  [client-kw client-class fn-kw methods]
  (let [client (with-meta (gensym "client") {:tag client-class})
        types (map #(.getParameterTypes ^Method %) methods)
        arg-names (map arg-names types)
        arities (partition-by count (sort-by count arg-names))
        f-sym (symbol (str (name fn-kw) "-map"))]
    `(defn ~f-sym
       {:arglists ~(list 'quote (mapv #(vec (conj % 'credentials)) arg-names))
        :doc ~(str "Generate map for "
                   (string/join ", " (map #(.getName ^Method %) methods)))}
       ~@(for [arg-lists arities
               :let [arity (count (first arg-lists))
                     args (take arity arg-name-seq)]]
           `([credentials# ~@args]
               {:credentials credentials#
                :fn ~fn-kw
                :args [~@args]
                :client ~client-kw})))))

(defn client-fn
  "Return a clojure function form for the given api methods (which should be
  overloads of the same method name), that expects a credential map and an
  argument list.  Calls the AWS client method using the arguments."
  [client-kw client-class fn-kw methods]
  (let [types (map #(.getParameterTypes ^Method %) methods)
        arg-names (map arg-names types)
        credentials (gensym "credentials")
        args (gensym "args")
        f-sym (symbol (name fn-kw))]
    `(defn ~f-sym
       {:arglists ~(list 'quote (mapv #(vec (conj % 'credentials)) arg-names))
        :doc ~(str "Call "
                   (string/join ", " (map #(.getName ^Method %) methods)))}
       [~credentials & ~args]
       ~(client-fn-call client-kw client-class methods credentials args))))

;;; # Top level macro
;;; Generate an implementation of a service client wrapper.
(defn add-aws-client
  "Adds methods for the specified AWS Client class. Each function takes an
  argument map.

  Each api is defined in a namespace in order to avoid class size limits."
  [client-kw client-class]
  (let [class (Class/forName (name client-class))
        sym (symbol (name client-kw))
        methods (client-methods class)
        ns-beans (request-beans-by-ns methods)
        beans (client-kw ns-beans)]
    (infof "bean ns %s" (pr-str (keys ns-beans)))
    (infof "beans %s" (pr-str beans))
    [`(do
        ~(client-factory client-kw client-class)
        ~@(request-bean-factories beans)
        (defmulti ~sym (fn ~sym [m#] (:fn m#)))
        ~@(for [[fn-kw methods] methods]
            `(do
               (defmethod ~(symbol (name client-kw)) ~fn-kw
                 ~@(client-method client-kw client-class methods))
               ~(client-map-fn client-kw client-class fn-kw methods)
               ~(client-fn client-kw client-class fn-kw methods))))
     (dissoc ns-beans client-kw)]))

(def apis
  {:ec2 'com.amazonaws.services.ec2.AmazonEC2Client
   :s3 'com.amazonaws.services.s3.AmazonS3Client
   :sts 'com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClient
   :iam 'com.amazonaws.services.identitymanagement.AmazonIdentityManagementClient
   :autoscaling 'com.amazonaws.services.autoscaling.AmazonAutoScalingClient
   :cloudformation 'com.amazonaws.services.cloudformation.AmazonCloudFormationClient
   :cloudfront 'com.amazonaws.services.cloudfront.AmazonCloudFrontClient
   :cloudsearch 'com.amazonaws.services.cloudsearch.AmazonCloudSearchClient
   :cloudwatch 'com.amazonaws.services.cloudwatch.AmazonCloudWatchClient
   :datapipeline 'com.amazonaws.services.datapipeline.DataPipelineClient
   :directconnect 'com.amazonaws.services.directconnect.AmazonDirectConnectClient
   ;; :dynamodb 'com.amazonaws.services.dynamodb.AmazonDynamoDBClient
   :elasticache 'com.amazonaws.services.elasticache.AmazonElastiCacheClient
   :elasticbeanstalk 'com.amazonaws.services.elasticbeanstalk.AWSElasticBeanstalkClient
   :elasticloadbalancing 'com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancingClient
   :elasticmapreduce 'com.amazonaws.services.elasticmapreduce.AmazonElasticMapReduceClient
   :glacier 'com.amazonaws.services.glacier.AmazonGlacierClient
   ;; :opsworks 'com.amazonaws.services.opsworks.AWSOpsWorksClient
   :rds 'com.amazonaws.services.rds.AmazonRDSClient
   :redshift 'com.amazonaws.services.redshift.AmazonRedshiftClient
   :route53 'com.amazonaws.services.route53.AmazonRoute53Client
   :securitytoken 'com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClient
   :simpledb 'com.amazonaws.services.simpledb.AmazonSimpleDBClient
   :simpleemail 'com.amazonaws.services.simpleemail.AmazonSimpleEmailServiceClient
   :sns 'com.amazonaws.services.sns.AmazonSNSClient
   :sqs 'com.amazonaws.services.sqs.AmazonSQSClient
   :storagegateway 'com.amazonaws.services.storagegateway.AWSStorageGatewayClient})

(def api-refer-exclude
  "Map of clojure symbols to exclude for each api namespace to avoid
  redefinition warnings."
  {:ec2 '[filter]
   :autoscaling '[filter]
   :rds '[filter]})

(defn api-ns-form
  "Return a `ns` form for the given api."
  [pp api ks & segs]
  (let [s (symbol (str "com.palletops.awaze."
                       (string/join "." segs)
                       (if (seq segs) ".")
                       (name api)))]
    (with-out-str
      (pp
       `(~'ns ~s
          ~@(if-let [excludes (api api-refer-exclude)]
              [`(:refer-clojure :exclude ~excludes)])
          (:require
           [com.palletops.awaze.common]
           [~s :as ~(symbol (name api))]
           ~@(map (fn [k]
                    [(symbol (str "com.palletops.awaze.beans." (name k)))
                     :as (symbol (name k))])
                  ks)))))))

(defn gen-api
  [target api class {:keys [pretty-print]}]
  (println "Generating" api "in" (str target))
  (let [pp (if pretty-print pprint identity)
        [form beans] (add-aws-client api class)]
    (binding [*print-meta* true]
      (spit target
            (str (api-ns-form pp api (keys beans))
                 \newline \newline
                 (with-out-str (pp form)))))
    beans))

(defn gen-apis
  [target {:keys [pretty-print]}]
  (reduce
   (fn [m [api class]]
     (merge-with
      concat
      m
      (gen-api
       (doto (file target "com" "palletops" "awaze"
                   (str (name api) ".clj"))
         (-> (.getParentFile) (.mkdirs)))
       api class {:pretty-print pretty-print})))
   {}
   apis))

(defn gen-bean-ns
  "Generate a bean namespace"
  [target kw beans {:keys [pretty-print]}]
  (infof "target %s count %s pp %s" target (count beans) (pr-str pretty-print))
  (infof "beans %s" (pr-str beans))
  (let [pp (if pretty-print pprint println)
        form (str (api-ns-form pp kw nil "beans")
                  \newline \newline
                  (with-out-str
                    (pp `(do ~@(request-bean-factories beans)))))]
    (binding [*print-meta* true]
      (spit target form))))

(defn gen-beans
  "Generate bean namespaces"
  [gen-src ns-beans {:keys [pretty-print]}]
  (infof "gen-beans %s" ns-beans)
  (doseq [[kw beans] ns-beans]
    (println "Generating bean namespace" kw "with" (count beans) "beans")
    (gen-bean-ns
     (doto (file gen-src "com" "palletops" "awaze" "beans"
                 (str (name kw) ".clj"))
       (-> (.getParentFile) (.mkdirs)))
     kw beans
     {:pretty-print pretty-print})))

(defn -main
  [& [pp]]
  (let [target-path "target"]
    (let [gen-src (file target-path "generated")]
      (println "Generating awaze source to" (str gen-src))
      (.mkdirs gen-src)
      (binding [*print-meta* true]
        (let [ns-beans (gen-apis gen-src {:pretty-print pp})
              ns-beans (reduce
                        (fn [m b]
                          (update-in m [(aws-ns-kw b)]
                                     #(concat [b] %)))
                        ns-beans
                        (map :arg-bean (vals static-constructors)))]
          (println "Generating bean namespaces for"
                   (count ns-beans) "namespaces")
          (gen-beans gen-src ns-beans {:pretty-print pp}))))))
