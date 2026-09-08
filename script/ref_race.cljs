;; Two writers, one ref, one sequence. Real witnesses, real OS processes.
;;
;;   nbb --classpath "src:<inga>/src" script/ref_race.cljs
;;
;; `inga`'s own `ref_test` proves the adapter refuses a second writer, but its
;; `propose!` is a cooperative oracle in the same process: it is told who won.
;; This asks the network. Two records for the SAME `[ref seq]` with different
;; CIDs are submitted CONCURRENTLY to two DIFFERENT witnesses, and the answer
;; is read from a THIRD that received neither — so what decides is the
;; committed order, not one node's local opinion of its own queue.
;;
;; The decision procedure is not reimplemented here. `inga.ref/project` folds
;; the committed records first-wins and `inga.ref/outcome` says whether a given
;; record won its sequence and, if not, which CID holds it. That is the whole
;; compare-and-set, and this script only supplies the network under it.
;;
;; `BOTH_WIN=1` is the control: it moves the second writer to seq 1, where
;; both records legitimately commit. The run must then FAIL — a check that
;; cannot tell "one won" from "both won" would report success either way, and
;; the point of the exercise is exactly that distinction.
(ns ref-race
  (:require [kotoba.lang.text :as str]
            [inga.commitment :as cm]
            [inga.consensus :as c]
            [inga.ref :as ref]
            [inga-node.identity :as ident]
            [inga-node.seams :as seams]
            [inga-node.proof :as proof]
            ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]))

(def witnesses
  (mapv #(str "w" %)
        (range 1 (inc (js/parseInt (or (some-> js/process .-env .-N) "7") 10)))))

;; Same moving range as `devnet.cljs`, for the same reason recorded there: a
;; leaked child from an earlier run holds a listening socket, and a fixed range
;; lets the previous network answer this one's questions.
(def base-port (+ 19400 (* 10 (mod (js/Math.floor (/ (.now js/Date) 1000)) 400))))
(def root (path/join (os/tmpdir) (str "inga-ref-race-" (.now js/Date))))
(defn- port-of [w] (+ base-port (.indexOf (to-array witnesses) w)))
(defn- data-of [w] (path/join root w))
(def classpath (or (some-> js/process .-env .-INGA_CP) "src"))
(def both-win? (= "1" (some-> js/process .-env .-BOTH_WIN)))

(def ^:private children (atom []))
(defn- kill-all! []
  (doseq [p @children] (try (.kill p "SIGKILL") (catch :default _ nil)))
  (reset! children []))
(js/process.on "exit" kill-all!)
(js/process.on "SIGINT" (fn [] (kill-all!) (js/process.exit 130)))

(defn- sleep [ms] (js/Promise. (fn [res] (js/setTimeout res ms))))

(defn- spawn! [w pubkeys]
  (let [others (remove #{w} witnesses)
        peers (str/join "," (map #(str % "=ws://127.0.0.1:" (port-of %)) others))
        env (js/Object.assign js/process.env
                              #js {"INGA_WITNESS" w
                                   "INGA_WITNESSES" (str/join "," witnesses)
                                   "INGA_PEERS" peers
                                   "INGA_PUBKEYS" (str/join "," (map (fn [[k v]] (str k "=" v)) pubkeys))
                                   "INGA_PORT" (str (port-of w))
                                   "INGA_TICK_MS" "500"
                                   "INGA_CHAIN_ID" "inga-ref-race-1"
                                   "INGA_NODE_DATA" (data-of w)})
        _ (fs/mkdirSync (data-of w) #js {:recursive true})
        log (fs/openSync (path/join (data-of w) "node.log") "a")
        p (cp/spawn "nbb" #js ["--classpath" classpath "script/run.cljs"]
                    #js {:env env :stdio #js ["ignore" log log]})]
    (swap! children conj p)
    p))

(defn- http [w p]
  (-> (js/fetch (str "http://127.0.0.1:" (port-of w) p))
      (.then #(.json %))
      (.catch (fn [_] nil))))

(defn- submit! [w rec]
  (-> (js/fetch (str "http://127.0.0.1:" (port-of w) "/submit")
                #js {:method "POST"
                     :headers #js {"content-type" "application/json"}
                     :body (js/JSON.stringify (clj->js {"record" rec}))})
      (.then #(.json %))
      (.catch (fn [_] nil))))

(defn- record [ref-name seq' cid]
  {"v" "inga.head/v1" "ref" ref-name "seq" seq' "cid" cid "prev" nil "height" nil})

(defn- head-from [w ref-name]
  (-> (http w (str "/committed?ref=" (js/encodeURIComponent ref-name)))
      (.then (fn [j] (when j (js->clj (aget j "head")))))))

(defn- committed-from
  "`{:head :proof}` as a witness serves it — the proof travels with the head,
  so a reader never has to decide whether two answers describe one moment."
  [w ref-name]
  (-> (http w (str "/committed?ref=" (js/encodeURIComponent ref-name)))
      (.then (fn [j]
               (when j
                 {:head (js->clj (aget j "head"))
                  :proof (proof/decode (aget j "proof"))})))))

(defn- committed-records
  "Every committed head record for `ref-name` on `w`'s own disk, in sequence
  order.

  Not `/committed?ref=`: that endpoint answers `head-of`, a single record, and
  a single record cannot show two writers BOTH winning at different sequences
  — which is exactly the failure `BOTH_WIN=1` exists to produce. Measured: the
  first version of this script projected the head alone, and the control run
  passed `exactly one writer won` while both records sat committed in the log.
  The instrument could not see the thing it was built to detect.

  Reading the log directly is what `inga.ref/project` asks for (`records` is
  already-decoded head records in committed order) and it is the same
  filesystem `/committed` reports as its `:source`."
  [w ref-name]
  (let [d (path/join (data-of w) "committed" (js/encodeURIComponent ref-name))]
    (if-not (fs/existsSync d)
      []
      (->> (fs/readdirSync d)
           (keep (fn [f]
                   (let [n (js/parseInt (str/replace f #"\.json$" "") 10)]
                     (when-not (js/isNaN n)
                       [n (try (js->clj (js/JSON.parse
                                         (fs/readFileSync (path/join d f) "utf8")))
                               (catch :default _ nil))]))))
           (sort-by first)
           (keep second)
           vec))))

(defn- await-until [f pred tries gap]
  (letfn [(step [i last]
            (if (zero? i)
              (js/Promise.resolve last)
              (-> (f)
                  (.then (fn [v]
                           (if (pred v)
                             (js/Promise.resolve v)
                             (-> (sleep gap) (.then (fn [_] (step (dec i) v))))))))))]
    (step tries nil)))

(def failures (atom []))
(defn- check [ok? label & [detail]]
  (if ok?
    (println (str "  ok   " label))
    (do (println (str "  FAIL " label (when detail (str " — " (pr-str detail)))))
        (swap! failures conj label)))
  ok?)

(def ref-name "race/main")

(defn -main []
  (fs/mkdirSync root #js {:recursive true})
  (println "\ninga ref race — two writers, one ref, one sequence\n")
  (println (str "  root " root))
  (when both-win?
    (println "  ..   BOTH_WIN=1 — the second writer takes seq 1, so both records"
             "commit legitimately and this run MUST fail"))
  (let [pubkeys (into {} (map (fn [w] [w (ident/public-hex (ident/ensure-seed! (data-of w)))])
                              witnesses))
        rec-b (record ref-name 0 "cid-from-bot-b")
        rec-c (record ref-name (if both-win? 1 0) "cid-from-bot-c")
        reader (last witnesses)]
    (doseq [w witnesses] (spawn! w pubkeys))
    (println (str "  ..   spawned " (count witnesses) " witness processes"
                  ", ports " base-port "-" (+ base-port (dec (count witnesses)))))
    (-> (sleep 7000)
        (.then
         (fn [_]
           ;; A warm-up on another ref: proves the network commits at all
           ;; before the race is read as evidence about anything. Without it a
           ;; dead network and a decisive race look the same from here — both
           ;; produce one head and no second winner.
           (submit! "w1" (record "race/warmup" 0 "cid-warmup"))))
        (.then (fn [_] (await-until #(head-from reader "race/warmup") some? 60 500)))
        (.then
         (fn [warm]
           (check (some? warm)
                  (str "the network commits at all — " reader " sees the warm-up head")
                  {:head warm})
           (when-not (some? warm)
             (println "  ..   giving up: nothing below would mean anything")
             (kill-all!)
             (js/process.exit 1))
           (println (str "  ..   both writers submit for " ref-name
                         " seq " (get rec-b "seq") "/" (get rec-c "seq")
                         ", concurrently, to w1 and w2"))
           ;; Different witnesses on purpose. Submitting both to one node would
           ;; let that node's queue order them, which is not the claim.
           (js/Promise.all #js [(submit! "w1" rec-b) (submit! "w2" rec-c)])))
        (.then
         (fn [acks]
           (check (every? #(and % (aget % "ok")) (array-seq acks))
                  "both submissions were accepted — neither writer was refused up front")
           (await-until #(head-from reader ref-name) some? 80 500)))
        (.then
         (fn [head]
           (check (some? head) (str "a head for " ref-name " committed") {:head head})
           (when head
             (let [projection (ref/project (committed-records reader ref-name))
                   out-b (ref/outcome projection rec-b)
                   out-c (ref/outcome projection rec-c)
                   winners (filter :certified? [out-b out-c])
                   losers (remove :certified? [out-b out-c])]
               (println (str "  ..   committed head: " (pr-str head)))
               (println (str "  ..   committed log:  "
                             (pr-str (mapv #(vector (get % "seq") (get % "cid"))
                                           (committed-records reader ref-name)))))
               (println (str "  ..   bot-b outcome:  " (pr-str out-b)))
               (println (str "  ..   bot-c outcome:  " (pr-str out-c)))
               (check (= 1 (count winners))
                      "exactly one writer won the sequence"
                      {:winners (count winners)})
               (check (= 1 (count losers))
                      "…and exactly one lost"
                      {:losers (count losers)})
               (when (= 1 (count losers))
                 ;; The half that makes a loss recoverable. A store that only
                 ;; answered `false` would leave the loser retrying against
                 ;; the base it already knows is stale.
                 (check (= (get head "cid") (:current (first losers)))
                        "…and the loser is told which CID actually holds the sequence"
                        {:told (:current (first losers)) :head (get head "cid")}))))
           ;; Read it from every witness: one node's answer is not the plane's.
           (js/Promise.all (into-array (map #(head-from % ref-name) witnesses)))))
        (.then
         (fn [heads]
           (let [cids (set (map #(get % "cid") (array-seq heads)))]
             (check (= 1 (count cids))
                    "every witness reports the same winning CID"
                    {:cids cids}))
           ;; And now the part that does not take the witness's word for it.
           (committed-from reader ref-name)))
        (.then
         (fn [{:keys [head proof]}]
           (let [ctx {:chain-id "inga-ref-race-1"
                      :hash-fn seams/hash-fn
                      :quorum (c/quorum-size (count witnesses))
                      :verify-fn (seams/verify-fn pubkeys)
                      :admitted? (set witnesses)
                      :decode-proposal #(js->clj (js/JSON.parse %))}]
             (check (some? proof)
                    "the head arrives with a proof that it was committed"
                    {:has-block (some? (:block proof)) :has-qc (some? (:qc proof))})
             (check (some? (cm/verify-head head ref-name proof ctx))
                    "…and it verifies OFFLINE — the record is in the block, and the block carries a quorum")
             ;; A verifier that accepts anything is the same shape as one that
             ;; works. Three ways of being wrong, each refused for its own
             ;; reason.
             (check (nil? (cm/verify-head head ref-name
                                          (assoc-in proof [:qc :inga.qc/block-hash] "not-this-block")
                                          ctx))
                    "…a certificate naming another block does not prove this one")
             (check (nil? (cm/verify-head head ref-name
                                          (update-in proof [:qc :inga.qc/sigs]
                                                     (fn [m] (into {} (map (fn [[k _]] [k "AAAA"])) m)))
                                          ctx))
                    "…forged signatures do not verify")
             (check (nil? (cm/verify-head head "some/other/ref" proof ctx))
                    "…and a real proof answers the ref it was made for, not the one asked about"))
           (js/Promise.resolve nil)))
        (.then
         (fn [_]
           (kill-all!)
           (println)
           (if (seq @failures)
             (do (println (str "REF RACE FAILED: " (str/join ", " @failures)))
                 (js/process.exit 1))
             (do (println "REF RACE PASSED")
                 (js/process.exit 0)))))
        (.catch
         (fn [e]
           (println (str "\nREF RACE ERROR: " e "\n" (.-stack e)))
           (kill-all!)
           (js/process.exit 1))))))

(-main)
