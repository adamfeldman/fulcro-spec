(ns untangled-spec.reporters.browser
  (:import [goog Delay])
  (:require
    goog.object
    [om.dom :as dom]
    [goog.dom :as gdom]
    [om.next :as om :refer-macros [defui]]
    [cljs-uuid-utils.core :as uuid]

    [untangled-spec.reporters.impl.browser :as impl]))

(defui ResultLine
       Object
       (initLocalState [this] {:folded? true})
       (render [this]
               (let [{:keys [title value stack] type- :type} (om/props this)
                     {:keys [folded?]} (om/get-state this)]
                 (dom/tr nil
                         (dom/td #js {:className (str "test-result-title " (name type-))} title)
                         (dom/td #js {:className "test-result"}
                                 (dom/code nil
                                           (if stack
                                             (dom/a #js {:href "#"
                                                         :className "error"
                                                         :onClick #(om/update-state! this update :folded? not)}
                                                    (if folded? \u25BA \u25BC)
                                                    (str value))
                                             (str value))
                                           (when stack
                                             (dom/div #js {:className (if folded? "hidden" "stack-trace")}
                                                      stack))))))))

(def ui-result-line (om/factory ResultLine))

(defui TestResult
       Object
       (render [this]
               (let [{:keys [message extra actual expected stack diff]} (om/props this)]
                 (->> (dom/tbody nil
                                 (when message
                                   (ui-result-line {:type :normal
                                                    :title "ASSERTION: "
                                                    :value message}))
                                 (ui-result-line {:type :normal
                                                  :title "Actual"
                                                  :value actual
                                                  :stack stack})
                                 (ui-result-line {:type :normal
                                                  :title "Expected"
                                                  :value (or expected "")})
                                 (when extra
                                   (ui-result-line {:type :normal
                                                    :title "Message: "
                                                    :value extra}))
                                 (when diff
                                   (ui-result-line {:type :diff
                                                    :title "Updates "
                                                    :value (:mutations diff)}))
                                 (when diff
                                   (ui-result-line {:type :diff
                                                    :title "Removals "
                                                    :value (:removals diff)}))
                        )
                      (dom/table nil)
                      (dom/li nil)))))

(def ui-test-result (om/factory TestResult {:keyfn :id}))

(declare ui-test-item)

(defui TestItem
       Object
       (render [this]
               (let [test-item-data (om/props this)
                     filter (:report/filter test-item-data) ]
                 (dom/li #js {:className "test-item "}
                         (dom/div #js {:className (impl/filter-class test-item-data)}
                                  (dom/span #js {:className (impl/itemclass (:status test-item-data))}
                                            (:name test-item-data))
                                  (dom/ul #js {:className "test-list"}
                                          (mapv ui-test-result
                                                (:test-results test-item-data)))
                                  (dom/ul #js {:className "test-list"}
                                          (mapv (comp ui-test-item #(assoc % :report/filter filter))
                                                (:test-items test-item-data))))))))

(def ui-test-item (om/factory TestItem {:keyfn :id}))

(defui TestNamespace
       Object
       (initLocalState [this] {:folded? false})
       (render
         [this]
         (let [tests-by-namespace (om/props this)
               filter (:report/filter tests-by-namespace)
               {:keys [folded?]} (om/get-state this)]
           (dom/li #js {:className "test-item"}
                   (dom/div #js {:className "test-namespace"}
                            (dom/a #js {:href "#"
                                        :style #js {:textDecoration "none"} ;; TODO: refactor to css
                                        :onClick   #(om/update-state! this update :folded? not)}
                                   (dom/h2 #js {:className (impl/itemclass (:status tests-by-namespace))}
                                           (if folded? \u25BA \u25BC)
                                           " Testing " (:name tests-by-namespace)))
                            (dom/ul #js {:className (if folded? "hidden" "test-list")}
                                    (mapv (comp ui-test-item #(assoc % :report/filter filter))
                                          (:test-items tests-by-namespace))))))))

(def ui-test-namespace (om/factory TestNamespace {:keyfn :name}))

(defui FilterSelector
       Object
       (render [this]
               (let [{:keys [current-filter this-filter set-filter!]} (om/props this)]
                 (dom/a #js {:href "#"
                             :className (if (= current-filter this-filter)
                                          "selected" "")
                             :onClick (set-filter! this-filter)}
                        (str this-filter)))))

(def ui-filter-selector (om/factory FilterSelector {:keyfn :this-filter}))

(defui Filters
       Object
       (render [this]
               (let [{:current-filter :report/filter
                      :keys [set-filter!]} (om/props this)]
                 (dom/div #js {:name "filters" :className "filter-controls"}
                          (dom/label #js {:htmlFor "filters"} "Filter: ")
                          (mapv #(ui-filter-selector {:current-filter current-filter
                                                      :set-filter! set-filter!
                                                      :this-filter %})
                                [:all :manual :failed])))))

(def ui-filters (om/factory Filters ))

(defui TestCount
       Object
       (render [this]
               (let [{:keys [passed failed error namespaces]} (om/props this)
                     total (+ passed failed error)]
                 (if (< 0 (+ failed error))
                   (impl/change-favicon-to-color "#d00")
                   (impl/change-favicon-to-color "#0d0"))
                 (dom/div #js {:className "test-count"}
                          (dom/h2 nil
                                  (str "Tested " (count namespaces) " namespaces containing "
                                       total  " assertions. "
                                       passed " passed "
                                       failed " failed "
                                       error  " errors"))))))

(def ui-test-count (om/factory TestCount))

(defui TestReport
       static om/IQuery
       (query [this] [:top :report/filter])
       Object
       (render [this]
               (let [props (om/props this)
                     test-report-data (-> props :top)
                     current-filter (-> props :report/filter)
                     set-filter! (fn [new-filter]
                                   #(om/transact! this `[(~'set-filter {:new-filter ~new-filter})]))]
                 (dom/section #js {:className "test-report"}
                              (ui-filters {:report/filter current-filter
                                           :set-filter! set-filter!})
                              (dom/ul #js {:className "test-list"}
                                      (->> (:namespaces test-report-data)
                                           (remove #(when (= :failed current-filter)
                                                      (not (#{:failed :error} (:status %)))))
                                           (mapv (comp ui-test-namespace
                                                       #(assoc % :report/filter current-filter)))))
                              (ui-test-count test-report-data)))))