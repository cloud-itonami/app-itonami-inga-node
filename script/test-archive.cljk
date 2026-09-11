(require '[clojure.test :as t] '[inga-node.archive-test])
(def completed? (atom false))
(defmethod t/report [:cljs.test/default :end-run-tests] [m]
  (reset! completed? true)
  (when (pos? (+ (:fail m) (:error m))) (js/process.exit 1)))
(js/process.on "beforeExit" (fn [] (when-not @completed? (js/process.exit 2))))
(t/run-tests 'inga-node.archive-test)
