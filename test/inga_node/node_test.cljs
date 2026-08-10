(ns inga-node.node-test
  "One witness, alone, restarted.

  `devnet` was supposed to prove this and cannot: its restart leg runs only
  after its kill leg, and the kill leg fails on a defect in `inga` that has
  nothing to do with restarting. So the single property a process has to have
  to replace a Durable Object — **it comes back** — has never actually been
  checked. This namespace checks it, with no peers and no sockets to anyone,
  because none of that is needed to answer the question.

  A lone witness never commits anything (quorum of one witness is still a
  quorum it cannot reach with n=4 configured), and that is fine: what has to
  survive here is the replica's own state and the submitted work, which is
  exactly what `inga.replica/snapshot` carries and what a cold start would
  silently discard."
  (:require [clojure.test :refer [deftest is testing async]]
            [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [inga-node.identity :as ident]
            [inga-node.store :as store]
            [inga-node.node :as node]))

(defn- tmp [] (fs/mkdtempSync (path/join (os/tmpdir) "inga-node-node-")))

;; Derived from the pid so two runs on one machine cannot collide, which is
;; how the devnet harness spent two diagnoses talking to its own leftovers.
(def ^:private base-port (+ 21000 (mod (.-pid js/process) 3000)))
(defn- port [n] (+ base-port n))

(defn- boot
  "A lone witness on `d`. `:witnesses` is four so the quorum is unreachable —
  this is about persistence, not agreement."
  [d p]
  (node/start! {:witness "w1" :witnesses ["w1" "w2" "w3" "w4"]
                :peers {} :pubkeys {}
                :seed-hex (ident/ensure-seed! d)
                :chain-id "inga-node-test" :port p
                :tick-ms 100000   ; long enough that no tick fires mid-test
                :data-dir d}))

(defn- GET [p path]
  (-> (js/fetch (str "http://127.0.0.1:" p path)) (.then #(.json %))))

(defn- POST [p path body]
  (-> (js/fetch (str "http://127.0.0.1:" p path)
                #js {:method "POST" :headers #js {"content-type" "application/json"}
                     :body (js/JSON.stringify (clj->js body))})
      (.then (fn [r] (-> (.json r) (.then (fn [j] [(.-status r) j])))))))

(defn- rec [ref seq' cid]
  {"v" "inga.head/v1" "ref" ref "seq" seq' "cid" cid "prev" nil "height" nil})

(defn- once [done] (let [f (atom false)] (fn [] (when-not @f (reset! f true) (done)))))

(deftest a-restart-keeps-the-submitted-work
  (testing "the property that replaces the Durable Object: it comes back"
    (async raw-done
      (let [done (once raw-done)
            d (tmp) p (port 1)
            n1 (boot d p)]
        (-> (POST p "/submit" {"record" (rec "g" 0 "cid-a")})
            (.then (fn [[status _]]
                     (is (= 200 status))
                     (GET p "/health")))
            (.then (fn [h]
                     (is (= 1 (aget h "pending")) "submitted work is queued")
                     ((:stop! n1))
                     ;; a NEW process would read the same directory; a new
                     ;; `start!` on the same data-dir is that, minus the fork
                     (let [n2 (boot d (port 2))]
                       (-> (GET (port 2) "/health")
                           (.then (fn [h2]
                                    (is (= 1 (aget h2 "pending"))
                                        "a cold start would report 0 here and look fine")
                                    (is (= "w1" (aget h2 "witness")))
                                    ((:stop! n2))
                                    (done)))))))
            (.catch (fn [e] (is false (str "threw: " (or (.-message e) e))) (done))))))))

(deftest a-restart-with-no-snapshot-starts-clean
  (testing "a fresh data dir is not an error condition"
    (async raw-done
      (let [done (once raw-done)
            d (tmp) p (port 3)
            n (boot d p)]
        (-> (GET p "/health")
            (.then (fn [h]
                     (is (true? (aget h "ok")))
                     (is (= 0 (aget h "pending")))
                     (is (= "inga-node-test" (aget h "chain-id")))
                     ((:stop! n))
                     (done)))
            (.catch (fn [e] (is false (str "threw: " (or (.-message e) e))) (done))))))))

(deftest an-unusable-snapshot-starts-cold-instead-of-refusing-to-boot
  (testing "the failure is in the LOAD, so refusing would make it permanent"
    (async raw-done
      (let [done (once raw-done)
            d (tmp) p (port 4)]
        ;; mint the seed first so the only broken thing is the snapshot
        (ident/ensure-seed! d)
        (fs/writeFileSync (store/snapshot-path d) "{:truncated" "utf8")
        (let [n (boot d p)]
          (-> (GET p "/health")
              (.then (fn [h]
                       (is (true? (aget h "ok"))
                           "a poisoned snapshot must not stop the witness from starting")
                       ((:stop! n))
                       (done)))
              (.catch (fn [e]
                        (is false (str "a bad snapshot stopped the boot: " (or (.-message e) e)))
                        (done)))))))))

(deftest submit-refuses-a-record-that-is-not-one
  (async raw-done
    (let [done (once raw-done)
          d (tmp) p (port 5)
          n (boot d p)]
      (-> (POST p "/submit" {"record" {"ref" "g"}})
          (.then (fn [[status j]]
                   (is (= 400 status))
                   (is (false? (aget j "ok")))
                   (GET p "/health")))
          (.then (fn [h]
                   (is (= 0 (aget h "pending"))
                       "a refused record must not reach the replica's queue")
                   ((:stop! n))
                   (done)))
          (.catch (fn [e] (is false (str "threw: " (or (.-message e) e))) (done)))))))

(deftest health-names-the-fault-model
  (testing "what the fault model IS depends on where the keys live, so it is asserted"
    (async raw-done
      (let [done (once raw-done)
            d (tmp) p (port 6)
            n (boot d p)]
        (-> (GET p "/health")
            (.then (fn [h]
                     ;; If someone ever moves seed generation out of the node,
                     ;; this string stops being true and this test is where
                     ;; that gets noticed.
                     (is (str/includes? (aget h "fault-model") "one key per host"))
                     (is (fs/existsSync (path/join d "seed"))
                         "and the key it is claiming about is on this host's disk")
                     ((:stop! n))
                     (done)))
            (.catch (fn [e] (is false (str "threw: " (or (.-message e) e))) (done))))))))

(deftest committed-reports-the-durable-log-not-memory
  (async raw-done
    (let [done (once raw-done)
          d (tmp) p (port 7)
          n (boot d p)]
      ;; written behind the node's back, exactly as a commit would
      (store/sink-blocks! d [{:inga.block/proposals
                              [(js/JSON.stringify (clj->js (rec "g" 0 "cid-a")))]}])
      (-> (GET p "/committed?ref=g")
          (.then (fn [j]
                   (is (= 1 (aget j "records")))
                   (is (= "filesystem" (aget j "source")) "and says where the answer came from")
                   (is (= "cid-a" (aget (aget j "head") "cid")))
                   ((:stop! n))
                   (done)))
          (.catch (fn [e] (is false (str "threw: " (or (.-message e) e))) (done)))))))
