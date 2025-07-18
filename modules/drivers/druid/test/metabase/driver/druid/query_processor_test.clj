(ns ^:mb/driver-tests metabase.driver.druid.query-processor-test
  "Some tests to make sure the Druid Query Processor is generating sane Druid queries when compiling MBQL."
  (:require
   [clojure.test :refer :all]
   [clojure.tools.macro :as tools.macro]
   [java-time.api :as t]
   [medley.core :as m]
   [metabase.driver :as driver]
   [metabase.driver.common.table-rows-sample :as table-rows-sample]
   [metabase.driver.druid.query-processor :as druid.qp]
   [metabase.query-processor :as qp]
   [metabase.query-processor.compile :as qp.compile]
   [metabase.test :as mt]
   [metabase.timeseries-query-processor-test.util :as tqpt]
   [metabase.util.date-2 :as u.date]
   [metabase.util.json :as json]
   [toucan2.core :as t2]))

(defn- str->absolute-dt [s]
  [:absolute-datetime (u.date/parse s "UTC") :default])

(deftest ^:parallel filter-intervals-test
  (let [dt-field                 [:field 1 {:temporal-unit :default}]
        filter-clause->intervals (comp @#'druid.qp/compile-intervals @#'druid.qp/filter-clause->intervals)]
    (testing :=
      (is (= ["2015-10-04T00:00:00Z/2015-10-04T00:00:00.001Z"]
             (filter-clause->intervals [:= dt-field (str->absolute-dt "2015-10-04T00:00:00Z")]))
          ":= filters should get converted to intervals like `v/v+1`")
      (is (= nil
             (filter-clause->intervals [:= [:field 1 nil] "toucan"]))
          "Non-temporal filter clauses should return `nil` intervals"))
    (testing :<
      (is (= ["-5000/2015-10-11T00:00:00Z"]
             (filter-clause->intervals [:<  dt-field (str->absolute-dt "2015-10-11T00:00:00Z")]))
          ":<, :<=, :>, and :>= should return an interval with -5000 or 5000 as min or max"))
    (testing :between
      (is (= ["2015-10-04T00:00:00Z/2015-10-20T00:00:00.001Z"]
             (filter-clause->intervals
              [:between dt-field (str->absolute-dt "2015-10-04T00:00:00Z") (str->absolute-dt "2015-10-20T00:00:00Z")]))))
    (testing :and
      (is (= ["2015-10-04T00:00:00Z/2015-10-11T00:00:00Z"]
             (filter-clause->intervals
              [:and
               [:>= dt-field (str->absolute-dt "2015-10-04T00:00:00Z")]
               [:<  dt-field (str->absolute-dt "2015-10-11T00:00:00Z")]]))
          "The Druid QP should be able to combine compound `:and` filter clauses into a single datetime interval.")
      (is (= ["2015-10-06T00:00:00Z/2015-10-20T00:00:00.001Z"]
             (filter-clause->intervals
              [:and
               [:between dt-field (str->absolute-dt "2015-10-04T00:00:00Z") (str->absolute-dt "2015-10-20T00:00:00Z")]
               [:between dt-field (str->absolute-dt "2015-10-06T00:00:00Z") (str->absolute-dt "2015-10-21T00:00:00Z")]]))
          "When two filters have overlapping intervals it should generate a single logically equivalent interval")
      (is (= nil
             (filter-clause->intervals
              [:and [:= [:field 1 nil] "toucan"] [:= [:field 2 nil] "threecan"]]))
          ":and clause should ignore non-temporal filters")
      (is (= ["2015-10-04T00:00:00Z/2015-10-04T00:00:00.001Z"]
             (filter-clause->intervals
              [:and
               [:= [:field 1 nil] "toucan"] [:= dt-field (str->absolute-dt "2015-10-04T00:00:00Z")]]))
          ":and clause with no temporal filters should be compiled to `nil` interval")
      (is (= ["2015-10-04T00:00:00Z/2015-10-04T00:00:00.001Z"]
             (filter-clause->intervals
              [:and
               [:= dt-field (str->absolute-dt "2015-10-04T00:00:00Z")]
               [:or
                [:>= dt-field (str->absolute-dt "2015-10-03T00:00:00Z")]
                [:<  dt-field (str->absolute-dt "2015-10-11T00:00:00Z")]]]))
          ":and clause should ignore nested `:or` filters, since they can't be combined into a single filter"))
    (testing :or
      (is (= ["2015-10-04T00:00:00Z/5000" "-5000/2015-10-11T00:00:00Z"]
             (filter-clause->intervals
              [:or
               [:>= dt-field (str->absolute-dt "2015-10-04T00:00:00Z")]
               [:<  dt-field (str->absolute-dt "2015-10-11T00:00:00Z")]]))
          ":or filters should be combined into multiple intervals")
      (is (= ["2015-10-04T00:00:00Z/5000"]
             (filter-clause->intervals
              [:or
               [:>= dt-field (str->absolute-dt "2015-10-04T00:00:00Z")]
               [:= [:field 1 nil] "toucan"]]))
          ":or clauses should ignore non-temporal filters")
      (is (= nil
             (filter-clause->intervals
              [:or
               [:= [:field 1 nil] "toucan"]
               [:= [:field 2 nil] "threecan"]]))
          ":or filters with no temporal filters should return nil"))))

(defn- do-query->native [query]
  (driver/with-driver :druid
    (tqpt/with-flattened-dbdef
      (binding [druid.qp/*random-query-id* (constantly "<Query ID>")]
        (qp.compile/compile query)))))

(defmacro ^:private query->native [query]
  `(do-query->native
    (mt/mbql-query ~'checkins
      ~query)))

(deftest ^:parallel compile-topN-test
  (mt/test-driver :druid
    (tqpt/with-flattened-dbdef
      (is (= {:projections [:venue_price :__count_0 :expression]
              :query       {:queryType        :topN
                            :threshold        1000
                            :granularity      :all
                            :dataSource       "checkins"
                            :dimension        "venue_price"
                            :context          {:queryId "<Query ID>"}
                            :postAggregations [{:type   :arithmetic
                                                :name   "expression"
                                                :fn     :*
                                                :fields [{:type :fieldAccess, :fieldName "__count_0"}
                                                         {:type :constant, :name "10", :value 10}]}]
                            :intervals        ["1900-01-01/2100-01-01"]
                            :metric           {:type :alphaNumeric}
                            :aggregations
                            [{:type       :filtered
                              :filter     {:type  :not
                                           :field {:type :selector, :dimension "id", :value nil}}
                              :aggregator {:type :count, :name "__count_0"}}]}
              :query-type  ::druid.qp/topN
              :mbql?       true}
             (query->native
              #_:clj-kondo/ignore
              {:aggregation [[:* [:count $id] 10]]
               :breakout    [$venue_price]}))))))

(deftest ^:parallel compile-topN-with-order-by-test
  (mt/test-driver :druid
    (tqpt/with-flattened-dbdef
      (is (= {:projections [:venue_category_name :__count_0]
              :query       {:queryType    :topN
                            :threshold    1000
                            :granularity  :all
                            :dataSource   "checkins"
                            :dimension    "venue_category_name"
                            :context      {:queryId "<Query ID>"}
                            :intervals    ["1900-01-01/2100-01-01"]
                            :metric       "__count_0"
                            :aggregations [{:type       :cardinality
                                            :name       "__count_0"
                                            :fieldNames ["venue_name"]
                                            :byRow      true
                                            :round      true}]}
              :query-type  ::druid.qp/topN
              :mbql?       true}
             (query->native
              #_:clj-kondo/ignore
              {:aggregation [[:aggregation-options [:distinct $checkins.venue_name] {:name "__count_0"}]]
               :breakout    [$venue_category_name]
               :order-by    [[:desc [:aggregation 0]] [:asc $checkins.venue_category_name]]}))))))

(deftest ^:parallel compile-groupBy-test
  (mt/test-driver :druid
    (tqpt/with-flattened-dbdef
      (is (= {:projections [:venue_category_name :user_name :__count_0]
              :query       {:queryType    :groupBy
                            :granularity  :all
                            :dataSource   "checkins"
                            :dimensions   ["venue_category_name", "user_name"]
                            :context      {:queryId "<Query ID>"}
                            :intervals    ["1900-01-01/2100-01-01"]
                            :aggregations [{:type       :cardinality
                                            :name       "__count_0"
                                            :fieldNames ["venue_name"]
                                            :byRow      true
                                            :round      true}]
                            :limitSpec    {:type    :default
                                           :columns [{:dimension "__count_0", :direction :descending}
                                                     {:dimension "venue_category_name", :direction :ascending}
                                                     {:dimension "user_name", :direction :ascending}]}}
              :query-type  ::druid.qp/groupBy
              :mbql?       true}
             (query->native
              #_:clj-kondo/ignore
              {:aggregation [[:aggregation-options [:distinct $checkins.venue_name] {:name "__count_0"}]]
               :breakout    [$venue_category_name $user_name]
               :order-by    [[:desc [:aggregation 0]] [:asc $checkins.venue_category_name]]}))))))

(deftest ^:parallel compile-groupBy-with-limit-test
  (mt/test-driver :druid
    (tqpt/with-flattened-dbdef
      (is (= {:projections [:venue_category_name :user_name :__count_0]
              :query       {:queryType    :groupBy
                            :granularity  :all
                            :dataSource   "checkins"
                            :dimensions   ["venue_category_name", "user_name"]
                            :context      {:queryId "<Query ID>"}
                            :intervals    ["1900-01-01/2100-01-01"]
                            :aggregations [{:type       :cardinality
                                            :name       "__count_0"
                                            :fieldNames ["venue_name"]
                                            :byRow      true
                                            :round      true}]
                            :limitSpec    {:type    :default
                                           :columns [{:dimension "__count_0", :direction :descending}
                                                     {:dimension "venue_category_name", :direction :ascending}
                                                     {:dimension "user_name", :direction :ascending}]
                                           :limit   5}}
              :query-type  ::druid.qp/groupBy
              :mbql?       true}
             (query->native
              {:aggregation [[:aggregation-options [:distinct $checkins.venue_name] {:name "__count_0"}]]
               :breakout    [$venue_category_name $user_name]
               :order-by    [[:desc [:aggregation 0]] [:asc $checkins.venue_category_name]]
               :limit       5}))))))

(deftest ^:parallel finalizing-field-access-test
  (mt/test-driver :druid
    (tqpt/with-flattened-dbdef
      (testing "`distinct` when used in post aggregations should have type `:finalizingFieldAccess`"
        (is (= {:projections [:__distinct_0 :expression]
                :query       {:queryType        :timeseries
                              :granularity      :all
                              :dataSource       "checkins"
                              :context          {:queryId "<Query ID>"}
                              :intervals        ["1900-01-01/2100-01-01"]
                              :aggregations     [{:type       :cardinality
                                                  :name       "__distinct_0"
                                                  :fieldNames ["venue_name"]
                                                  :byRow      true
                                                  :round      true}]
                              :postAggregations [{:type :arithmetic,
                                                  :name "expression",
                                                  :fn   :+,
                                                  :fields
                                                  [{:type :constant, :name "1", :value 1}
                                                   {:type :finalizingFieldAccess, :fieldName "__distinct_0"}]}]}
                :query-type  ::druid.qp/total
                :mbql?       true}
               (query->native
                {:aggregation [[:+ 1 [:aggregation-options [:distinct $checkins.venue_name] {:name "__distinct_0"}]]]})))))))

(defn- table-rows-sample []
  (->> (table-rows-sample/table-rows-sample (t2/select-one :model/Table :id (mt/id :checkins))
                                            [(t2/select-one :model/Field :id (mt/id :checkins :id))
                                             (t2/select-one :model/Field :id (mt/id :checkins :venue_name))
                                             (t2/select-one :model/Field :id (mt/id :checkins :timestamp))]
                                            (constantly conj))
       (sort-by first)
       (take 5)))

(deftest table-rows-sample-test
  (mt/test-driver :druid
    (tqpt/with-flattened-dbdef
      (testing "Druid driver doesn't need to convert results to the expected timezone for us. QP middleware can handle that."
        (let [expected [[1 "The Misfit Restaurant + Bar" (t/instant "2014-04-07T00:00:00Z")]
                        [2 "Bludso's BBQ"                (t/instant "2014-09-18T00:00:00Z")]
                        [3 "Philippe the Original"       (t/instant "2014-09-15T00:00:00Z")]
                        [4 "Wurstküche"                  (t/instant "2014-03-11T00:00:00Z")]
                        [5 "Hotel Biron"                 (t/instant "2013-05-05T00:00:00Z")]]]
          (testing "UTC timezone"
            (is (= expected
                   (table-rows-sample))))
          (mt/with-temporary-setting-values [report-timezone "America/Los_Angeles"]
            (is (= expected
                   (table-rows-sample))))
          (mt/with-system-timezone-id! "America/Chicago"
            (is (= expected
                   (table-rows-sample)))))))))

(def ^:private native-query-1
  (json/encode
   {:queryType   :scan
    :dataSource  :checkins
    :intervals   ["1900-01-01/2100-01-01"]
    :granularity :all
    :limit       2
    :columns     [:id
                  :user_name
                  :venue_price
                  :venue_name
                  :count]}))

(defn- process-native-query [query]
  (driver/with-driver :druid
    (tqpt/with-flattened-dbdef
      (-> (qp/process-query {:native   {:query query}
                             :type     :native
                             :database (mt/id)})
          (m/dissoc-in [:data :results_metadata])))))

(deftest ^:parallel native-query-test
  (mt/test-driver :druid
    (is (partial=
         {:row_count 2
          :status    :completed
          :data      {:rows             [[931 "Simcha Yan" 1 "Kinaree Thai Bistro"       1]
                                         [285 "Kfir Caj"   2 "Ruen Pair Thai Restaurant" 1]]
                      :cols             [{:name         "id"
                                          :source       :native
                                          :display_name "id"
                                          :field_ref    [:field "id" {:base-type :type/Integer}]
                                          :base_type    :type/Integer
                                          :effective_type :type/Integer}
                                         {:name         "user_name"
                                          :source       :native
                                          :display_name "user_name"
                                          :base_type    :type/Text
                                          :effective_type :type/Text
                                          :field_ref    [:field "user_name" {:base-type :type/Text}]}
                                         {:name         "venue_price"
                                          :source       :native
                                          :display_name "venue_price"
                                          :base_type    :type/Integer
                                          :effective_type :type/Integer
                                          :field_ref    [:field "venue_price" {:base-type :type/Integer}]}
                                         {:name         "venue_name"
                                          :source       :native
                                          :display_name "venue_name"
                                          :base_type    :type/Text
                                          :effective_type :type/Text
                                          :field_ref    [:field "venue_name" {:base-type :type/Text}]}
                                         {:name         "count"
                                          :source       :native
                                          :display_name "count"
                                          :base_type    :type/Integer
                                          :effective_type :type/Integer
                                          :field_ref    [:field "count" {:base-type :type/Integer}]}]
                      :native_form      {:query native-query-1}
                      :results_timezone "UTC"}}
         (-> (process-native-query native-query-1)
             (m/dissoc-in [:data :insights]))))))

(def ^:private native-query-2
  (json/encode
   {:intervals    ["1900-01-01/2100-01-01"]
    :granularity  {:type     :period
                   :period   :P1M
                   :timeZone :UTC}
    :queryType    :timeseries
    :dataSource   :checkins
    :aggregations [{:type :count
                    :name :count}]}))

(deftest ^:parallel native-query-test-2
  (testing "make sure we can run a native :timeseries query. This was throwing an Exception -- see #3409"
    (mt/test-driver :druid
      (is (= :completed
             (:status (process-native-query native-query-2)))))))

(defmacro ^:private druid-query {:style/indent 0} [& body]
  `(tqpt/with-flattened-dbdef
     (qp/process-query
      (mt/mbql-query ~'checkins
        ~@body))))

(defmacro ^:private druid-query-returning-rows {:style/indent 0} [& body]
  `(mt/rows (druid-query ~@body)))

(deftest ^:parallel start-of-week-test
  (mt/test-driver :druid
    (testing "Count the number of events in the given week."
      (is (= [["2015-10-04" 9]]
             (druid-query-returning-rows
               {:filter      [:between !day.timestamp "2015-10-04" "2015-10-10"]
                :aggregation [[:count $id]]
                :breakout    [!week.timestamp]}))))))

(deftest ^:parallel sum-aggregation-test
  (mt/test-driver :druid
    (testing "sum, *"
      (is (= [["1" 110688.0]
              ["2" 616708.0]
              ["3" 179661.0]
              ["4"  86284.0]]
             (druid-query-returning-rows
               {:aggregation [[:sum [:* $id $venue_price]]]
                :breakout    [$venue_price]}))))))

(deftest ^:parallel min-aggregation-test
  (mt/test-driver :druid
    (testing "min, +"
      (is (= [["1"  4.0]
              ["2"  3.0]
              ["3"  8.0]
              ["4" 12.0]]
             (druid-query-returning-rows
               {:aggregation [[:min [:+ $id $venue_price]]]
                :breakout    [$venue_price]}))))))

(deftest ^:parallel max-aggregation-test
  (mt/test-driver :druid
    (testing "max, /"
      (is (= [["1" 1000.0]
              ["2"  499.5]
              ["3"  332.0]
              ["4"  248.25]]
             (druid-query-returning-rows
               {:aggregation [[:max [:/ $id $venue_price]]]
                :breakout    [$venue_price]}))))))

(deftest ^:parallel avg-aggregation-test
  (mt/test-driver :druid
    (testing "avg, -"
      (is (= [["1" 500.85067873303166]
              ["2" 1002.7772357723577]
              ["3" 1562.2695652173913]
              ["4" 1760.8979591836735]]
             (druid-query-returning-rows
               {:aggregation [[:avg [:* $id $venue_price]]]
                :breakout    [$venue_price]}))))))

(deftest ^:parallel share-aggregation-test
  (mt/test-driver :druid
    (testing "share"
      (is (= [[0.951]]
             (druid-query-returning-rows
               {:aggregation [[:share [:< $venue_price 4]]]}))))))

(deftest ^:parallel count-where-aggregation-test
  (mt/test-driver :druid
    (testing "count-where"
      (is (= [[951]]
             (druid-query-returning-rows
               {:aggregation [[:count-where [:< $venue_price 4]]]}))))))

(deftest ^:parallel sum-where-aggregation-test
  (mt/test-driver :druid
    (testing "sum-where"
      (is (= [[1796.0]]
             (druid-query-returning-rows
               {:aggregation [[:sum-where $venue_price [:< $venue_price 4]]]}))))))

(deftest ^:parallel count-aggregation-test
  (mt/test-driver :druid
    (testing "aggregation w/o field"
      (is (= [["1" 222.0]
              ["2" 616.0]
              ["3" 116.0]
              ["4"  50.0]]
             (druid-query-returning-rows
               {:aggregation [[:+ 1 [:count]]]
                :breakout    [$venue_price]}))))))

(deftest ^:parallel expression-aggregations-test
  (mt/test-driver :druid
    (testing "post-aggregation math w/ 2 args: count + sum"
      (is (= [["1"  442.0]
              ["2" 1845.0]
              ["3"  460.0]
              ["4"  245.0]]
             (druid-query-returning-rows
               {:aggregation [[:+ [:count $id] [:sum $venue_price]]]
                :breakout    [$venue_price]}))))

    (testing "post-aggregation math w/ 3 args: count + sum + count"
      (is (= [["1"  663.0]
              ["2" 2460.0]
              ["3"  575.0]
              ["4"  294.0]]
             (druid-query-returning-rows
               {:aggregation [[:+
                               [:count $id]
                               [:sum $venue_price]
                               [:count $venue_price]]]
                :breakout    [$venue_price]}))))

    (testing "post-aggregation math w/ a constant: count * 10"
      (is (= [["1" 2210.0]
              ["2" 6150.0]
              ["3" 1150.0]
              ["4"  490.0]]
             (druid-query-returning-rows
               {:aggregation [[:* [:count $id] 10]]
                :breakout    [$venue_price]}))))

    (testing "nested post-aggregation math: count + (count * sum)"
      (is (= [["1"  49062.0]
              ["2" 757065.0]
              ["3"  39790.0]
              ["4"  9653.0]]
             (druid-query-returning-rows
               {:aggregation [[:+
                               [:count $id]
                               [:* [:count $id] [:sum $venue_price]]]]
                :breakout    [$venue_price]}))))

    (testing "post-aggregation math w/ avg: count + avg"
      (is (= [["1"  721.8506787330316]
              ["2" 1116.388617886179]
              ["3"  635.7565217391304]
              ["4"  489.2244897959184]]
             (druid-query-returning-rows
               {:aggregation [[:+ [:count $id] [:avg $id]]]
                :breakout    [$venue_price]}))))

    (testing "aggregation with math inside the aggregation :scream_cat:"
      (is (= [["1"  442.0]
              ["2" 1845.0]
              ["3"  460.0]
              ["4"  245.0]]
             (druid-query-returning-rows
               {:aggregation [[:sum [:+ $venue_price 1]]]
                :breakout    [$venue_price]}))))

    (testing "post aggregation math + math inside aggregations: max(venue_price) + min(venue_price - id)"
      (is (= [["1" -998.0]
              ["2" -995.0]
              ["3" -990.0]
              ["4" -985.0]]
             (druid-query-returning-rows
               {:aggregation [[:+
                               [:max $venue_price]
                               [:min [:- $venue_price $id]]]]
                :breakout    [$venue_price]}))))))

(deftest ^:parallel named-top-level-aggregation-test
  (mt/test-driver :druid
    (testing "check that we can name an expression aggregation w/ aggregation at top-level"
      (is (= [["1"  442.0]
              ["2" 1845.0]
              ["3"  460.0]
              ["4"  245.0]]
             (mt/rows
              (druid-query
                {:aggregation [[:aggregation-options [:sum [:+ $venue_price 1]] {:name "New Price"}]]
                 :breakout    [$venue_price]})))))))

(deftest ^:parallel named-expression-aggregations-test
  (mt/test-driver :druid
    (testing "check that we can name an expression aggregation w/ expression at top-level"
      (is (= {:rows    [["1"  180.0]
                        ["2" 1189.0]
                        ["3"  304.0]
                        ["4"  155.0]]
              :columns ["venue_price" "Sum-41"]}
             (mt/rows+column-names
              (druid-query
                {:aggregation [[:aggregation-options [:- [:sum $venue_price] 41] {:name "Sum-41"}]]
                 :breakout    [$venue_price]})))))))

(deftest ^:parallel distinct-count-of-two-dimensions-test
  (mt/test-driver :druid
    (is (= {:rows    [[979]]
            :columns ["count"]}
           (mt/rows+column-names
            (druid-query
              {:aggregation [[:distinct [:+ $id $checkins.venue_price]]]}))))))

(deftest ^:parallel order-by-aggregation-test
  (mt/test-driver :druid
    (doseq [[direction expected-rows] {:desc [["Bar" "Felipinho Asklepios"      8]
                                              ["Bar" "Spiros Teofil"            8]
                                              ["Japanese" "Felipinho Asklepios" 7]
                                              ["Japanese" "Frans Hevel"         7]
                                              ["Mexican" "Shad Ferdynand"       7]]
                                       :asc  [["American" "Rüstem Hebel"    1]
                                              ["Artisan"  "Broen Olujimi"   1]
                                              ["Artisan"  "Conchúr Tihomir" 1]
                                              ["Artisan"  "Dwight Gresham"  1]
                                              ["Artisan"  "Plato Yeshua"    1]]}]
      (testing direction
        (is (= expected-rows
               (druid-query-returning-rows
                 {:aggregation [[:aggregation-options [:distinct $checkins.venue_name] {:name "__count_0"}]]
                  :breakout    [$venue_category_name $user_name]
                  :order-by    [[direction [:aggregation 0]] [:asc $checkins.venue_category_name]]
                  :limit       5})))))))

(deftest ^:parallel hll-count-test
  (mt/test-driver :druid
    (testing "Do we generate the correct count clause for HLL fields?"
      (is (= [["Bar"      "Szymon Theutrich"    13]
              ["Mexican"  "Dwight Gresham"      12]
              ["American" "Spiros Teofil"       10]
              ["Bar"      "Felipinho Asklepios" 10]
              ["Bar"      "Kaneonuskatew Eiran" 10]]
             (druid-query-returning-rows
               {:aggregation [[:aggregation-options [:count $checkins.user_name] {:name "unique_users"}]]
                :breakout   [$venue_category_name $user_name]
                :order-by   [[:desc [:aggregation 0]] [:asc $checkins.venue_category_name]]
                :limit      5}))))))

(deftest ^:parallel numeric-filter-test
  (mt/test-driver :druid
    (tqpt/with-flattened-dbdef
      (letfn [(compiled [query]
                (-> (qp.compile/compile query) :query (select-keys [:filter :queryType])))]
        (doseq [[message field] {"Make sure we can filter by numeric columns (#10935)" :venue_price
                                 "We should be able to filter by Metrics (#11823)"     :count}
                :let            [field-clause [:field (mt/id :checkins field) nil]
                                 field-name   (name field)]]
          (testing message
            (testing "scan query"
              (let [query (mt/mbql-query checkins
                            {:fields   [$id $venue_price $venue_name]
                             :filter   [:= field-clause 1]
                             :order-by [[:desc $id]]
                             :limit    5})]
                (is (= {:filter    {:type :selector, :dimension field-name, :value 1}
                        :queryType :scan}
                       (compiled query)))
                (is (= [931 1 "Kinaree Thai Bistro"]
                       (mt/first-row (qp/process-query query))))))

            (testing "topN query"
              (let [query (mt/mbql-query checkins
                            {:aggregation [[:count]]
                             :breakout    [$venue_price]
                             :filter      [:= field-clause 1]})]
                (is (= {:filter    {:type :selector, :dimension field-name, :value 1}
                        :queryType :topN}
                       (compiled query)))
                (is (= ["1" 221]
                       (mt/first-row (qp/process-query query))))))

            (testing "groupBy query"
              (let [query (mt/mbql-query checkins
                            {:aggregation [[:aggregation-options [:distinct $checkins.venue_name] {:name "__count_0"}]]
                             :breakout    [$venue_category_name $user_name]
                             :order-by    [[:desc [:aggregation 0]] [:asc $checkins.venue_category_name]]
                             :filter      [:= field-clause 1]})]
                (is (= {:filter    {:type :selector, :dimension field-name, :value 1}
                        :queryType :groupBy}
                       (compiled query)))
                (is (= (case field
                         :count       ["Bar" "Felipinho Asklepios" 8]
                         :venue_price ["Mexican" "Conchúr Tihomir" 4])
                       (mt/first-row (qp/process-query query))))))

            (testing "timeseries query"
              (let [query (mt/mbql-query checkins
                            {:aggregation [[:count]]
                             :filter      [:= field-clause 1]})]
                (is (= {:queryType :timeseries
                        :filter    {:type :selector, :dimension field-name, :value 1}}
                       (compiled query)))
                (is (= (case field
                         :count       [1000]
                         :venue_price [221])
                       (mt/first-row (qp/process-query query))))))))))))

(deftest ^:parallel parse-filter-test
  (mt/test-driver :druid
    (testing "parse-filter should generate the correct filter clauses"
      (tqpt/with-flattened-dbdef
        (mt/with-metadata-provider (mt/id)
          (tools.macro/macrolet [(parse-filter [filter-clause]
                                   `(#'druid.qp/parse-filter (mt/$ids ~'checkins ~filter-clause)))]
            (testing "normal non-compound filters should work as expected"
              (is (= {:type :selector, :dimension "venue_price", :value 2}
                     (parse-filter [:= $venue_price [:value 2 {:base_type :type/Integer}]]))))
            (testing "temporal filters should get stripped out"
              (is (= nil
                     (parse-filter [:>= !default.timestamp [:absolute-datetime #t "2015-09-01T00:00Z[UTC]" :default]])))
              (is (= {:type :selector, :dimension "venue_category_name", :value "Mexican"}
                     (parse-filter
                      [:and
                       [:= $venue_category_name [:value "Mexican" {:base_type :type/Text}]]

                       [:< !default.timestamp [:absolute-datetime #t "2015-10-01T00:00Z[UTC]" :default]]]))))))))))

(deftest ^:parallel multiple-filters-test
  (mt/test-driver :druid
    (testing "Should be able to filter by both a temporal and a non-temporal filter (#15903)"
      (tqpt/with-flattened-dbdef
        (is (= [4]
               (mt/first-row
                (mt/run-mbql-query checkins
                  {:aggregation [[:count]]
                   :filter      [:and
                                 [:= $venue_category_name "Mexican"]
                                 [:= !month.timestamp "2015-09"]]}))))))))

(deftest ^:parallel open-ended-temporal-filter-test
  (mt/test-driver :druid
    (testing "Should be able to filter by an open-ended absolute temporal moment (#15902)"
      (tqpt/with-flattened-dbdef
        (is (= [58]
               (mt/first-row
                (mt/run-mbql-query checkins
                  {:aggregation [[:count]]
                   :filter      [:> $timestamp "2015-10-01T00:00:00Z"]}))))))))
