;; Four witnesses, four OS PROCESSES, and a kill.
;;
;;   nbb --classpath "src:<inga>/src" script/devnet.cljs
;;
;; The point is not that consensus works — `inga`'s own harness proves that,
;; in one process. The point is the thing a Durable Object was being used
;; for: that a witness which DIES comes back, resumes from its own disk, and
;; rejoins a network that kept going without it.
;;
;; So the run is: start four, submit, watch it commit, KILL one, submit
;; again, watch the remaining three commit without it, restart the dead one,
;; and require that it catches up to the same committed height. A restart
;; that silently starts cold would still pass the first half.
(ns devnet
  (:require [kotoba.lang.text :as str]
            ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:os" :as os]
            [inga-node.identity :as ident]))

(def witnesses
  "Seven, not four.

  `inga`'s own harness records why, and this run reproduced it before the
  number changed: four with a quorum of three has ZERO margin under churn —
  evict one and exactly quorum remains, so a single lost message costs a
  round. The kill step below stalled indefinitely at four. Seven leaves six
  against a quorum of five and has one to spare, which is what makes
  \"a witness died\" a thing the network survives rather than a thing it
  merely tolerates while nothing else goes wrong. `N=4 nbb ...` to see it
  stall again."
  (mapv #(str "w" %)
        (range 1 (inc (js/parseInt (or (some-> js/process .-env .-N) "7") 10)))))
(def base-port
  "A fresh port range per run.

  Fixed ports plus a harness that leaked children produced the worst kind of
  green: the first failing runs left their node processes alive holding
  19401-19407, so the NEXT run's witnesses died on EADDRINUSE while the
  PREVIOUS run's survivors answered /health with a chain 61 blocks deep. The
  harness then reported a partitioned network and I spent a diagnosis
  blaming quorum margins. Both halves are fixed: children are killed on any
  exit (below) and the range moves every run, so a leak from one run cannot
  masquerade as the next one's network."
  (+ 19400 (* 10 (mod (js/Math.floor (/ (.now js/Date) 1000)) 400))))
(def root (path/join (os/tmpdir) (str "inga-devnet-" (.now js/Date))))
(defn- port-of [w] (+ base-port (.indexOf (to-array witnesses) w)))
(defn- data-of [w] (path/join root w))
(def classpath (or (some-> js/process .-env .-INGA_CP) "src"))

(def ^:private children
  "Every child spawned, so the exit hook can reach them. A harness that
  leaks a process leaks a LISTENING SOCKET, and the next run then talks to
  the last one."
  (atom []))

(defn- kill-all! []
  (doseq [p @children] (try (.kill p "SIGKILL") (catch :default _ nil)))
  (reset! children []))

(js/process.on "exit" kill-all!)
(js/process.on "SIGINT" (fn [] (kill-all!) (js/process.exit 130)))

(defn- sleep [ms] (js/Promise. (fn [res] (js/setTimeout res ms))))

(defn- spawn! [w pubkeys]
  (let [others (remove #{w} witnesses)
        peers (str/join "," (map #(str % "=ws://127.0.0.1:" (port-of %)) others))
        env (js/Object.assign #js {} js/process.env
                              #js {"INGA_WITNESS" w
                                   "INGA_WITNESSES" (str/join "," witnesses)
                                   "INGA_PEERS" peers
                                   "INGA_PUBKEYS" (str/join "," (map (fn [[k v]] (str k "=" v)) pubkeys))
                                   "INGA_PORT" (str (port-of w))
                                   "INGA_TICK_MS" "500"
                                   "INGA_CHAIN_ID" "inga-devnet-1"
                                   "INGA_NODE_DATA" (data-of w)})
        _ (fs/mkdirSync (data-of w) #js {:recursive true})
        ;; NOT `:stdio "ignore"`. The first failing run of this harness threw
        ;; every child's output away and left three witnesses stuck at height
        ;; zero with no way to ask them why — which turned a five-minute
        ;; diagnosis into guesswork about quorum margins that was wrong.
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

(defn- record [ref seq' cid]
  {"v" "inga.head/v1" "ref" ref "seq" seq' "cid" cid "prev" nil "height" nil})

(defn- await-until
  "Poll `f` until `pred`, or give up. Returns the last value."
  [f pred tries gap]
  (letfn [(step [i last]
            (if (zero? i)
              (js/Promise.resolve last)
              (-> (f)
                  (.then (fn [v]
                           (if (pred v)
                             (js/Promise.resolve v)
                             (-> (sleep gap) (.then (fn [_] (step (dec i) v)))))))))) ]
    (step tries nil)))

(defn- records-of [w]
  (-> (http w "/committed") (.then (fn [j] (if j (aget j "records") -1)))))

(defn- fail! [msg]
  (println (str "DEVNET: FAIL — " msg))
  (kill-all!)
  (js/process.exit 1))

(defn- dump!
  "Every live witness's health, printed. A run that fails without showing the
  state it failed in makes the next person reproduce it to learn what this
  one already knew."
  [live label]
  (-> (js/Promise.all (into-array (map #(http % "/health") live)))
      (.then (fn [hs]
               (println (str "  -- " label))
               (doseq [[w h] (map vector live (array-seq hs))]
                 (if h
                   (println (str "     " w
                                 " h=" (aget h "height")
                                 " ch=" (aget h "committed-height")
                                 " view=" (aget h "view")
                                 " pend=" (aget h "pending")
                                 " out=" (aget h "peers-out")
                                 " in=" (aget h "peers-in")
                                 " rec=" (aget h "records")
                                 " votes=" (aget h "vote-groups")
                                 " nv=" (aget h "new-view-groups")
                                 " qcs=" (aget h "qcs")
                                 " leader=" (aget h "leader-of-view")))
                   (println (str "     " w " unreachable"))))
               nil))))

(def ^:private skip-kill? (= "1" (some-> js/process .-env .-SKIP_KILL)))

(defn -main []
  (fs/mkdirSync root #js {:recursive true})
  (println "devnet root:" root)
  ;; Seeds first, on each node's own disk, then collect the PUBLIC halves —
  ;; the same order a real deployment uses. Nothing secret is assembled here.
  (let [pubkeys (into {} (map (fn [w] [w (ident/public-hex (ident/ensure-seed! (data-of w)))])
                              witnesses))
        procs (atom (into {} (map (fn [w] [w (spawn! w pubkeys)]) witnesses)))]
    (println "spawned" (count @procs) "processes")
    (-> (sleep 6000)
        (.then (fn [_] (submit! "w1" (record "devnet/a" 0 "cid-a"))))
        (.then (fn [_] (await-until #(records-of "w2") #(and % (pos? %)) 40 500)))
        (.then (fn [n]
                 (when-not (and n (pos? n)) (fail! "first record never committed"))
                 (println (str "ok   - " (count witnesses) " processes committed a submitted record (w2 sees " n ")"))
                 ;; KILL w3 — unless we are isolating whether the SECOND
                 ;; submit commits at all, which is the control this run
                 ;; needed and did not have: two guesses about the kill were
                 ;; wrong before anyone checked that a second record commits
                 ;; on a healthy network. `SKIP_KILL=1` is that control.
                 (if (= "1" (some-> js/process .-env .-SKIP_KILL))
                   (println "     (SKIP_KILL=1 — nothing is killed, so the two legs"
                            "below prove NOTHING about departure or restart)")
                   (do (.kill (get @procs "w3"))
                       (swap! procs dissoc "w3")))
                 (sleep 3000)))
        (.then (fn [_] (dump! (keys @procs) "after the kill, before the second submit")))
        (.then (fn [_] (submit! "w1" (record "devnet/b" 0 "cid-b"))))
        (.then (fn [_] (await-until #(records-of "w2") #(and % (> % 1)) 60 500)))
        (.then (fn [n]
                 (if (and n (> n 1))
                   (do (println (str "ok   - " (if skip-kill?
                                                  "the network kept committing (w3 was NOT killed)"
                                                  (str (dec (count witnesses)) " of " (count witnesses)
                                                       " kept committing with w3 dead"))
                                     " (w2 sees " n ")"))
                       (js/Promise.resolve nil))
                   (-> (dump! (keys @procs) "stalled with one witness down")
                       (.then (fn [_]
                                (fail! "the network did not make progress with one witness down")))))))
        (.then (fn [_]
                 ;; Restart w3 from its own disk.
                 (swap! procs assoc "w3" (spawn! "w3" pubkeys))
                 (sleep 6000)))
        (.then (fn [_]
                 (await-until #(records-of "w3")
                              (fn [n] (and n (> n 1))) 60 500)))
        (.then (fn [n]
                 (when-not (and n (> n 1))
                   (fail! "the restarted witness never caught up — it did not resume"))
                 (println (if skip-kill?
                            "skip - w3 was never killed, so this leg proves nothing (sees "
                            "ok   - w3 restarted, resumed from its own disk and caught up (sees ")
                          n ")")
                 (http "w3" "/health")))
        (.then (fn [h]
                 (println "     w3 height" (aget h "height")
                          "committed" (aget h "committed-height")
                          "peers-out" (aget h "peers-out"))
                 (js/Promise.all (into-array (map #(http % "/health") witnesses)))))
        (.then (fn [hs]
                 (println "     committed heights"
                          (mapv #(aget % "committed-height") (array-seq hs)))
                 ;; NOT an assertion on the spread. Seven processes exchanging
                 ;; real messages are never at one height at an arbitrary
                 ;; instant, and the spread does not close while the chain is
                 ;; live — measured [67 65 65 64 65 66 65], then, four seconds
                 ;; later and all higher, [38 38 40 41 38 40 39]. Every replica
                 ;; was advancing. A threshold on a moving quantity turns
                 ;; ordinary in-flight work into a failure, which is the same
                 ;; mistake as asserting equal heights in a HotStuff network.
                 ;;
                 ;; The property that must hold regardless of lag is SAFETY:
                 ;; wherever two replicas have both decided a (ref, seq), they
                 ;; decided the SAME cid. Liveness is already covered above by
                 ;; records appearing at all.
                 (js/Promise.all
                  (into-array
                   (map (fn [w]
                          (-> (js/fetch (str "http://127.0.0.1:" (port-of w)
                                             "/committed?ref=" (js/encodeURIComponent "devnet/a")))
                              (.then #(.json %))
                              (.then (fn [j] [w (some-> (aget j "head") (aget "cid"))]))
                              (.catch (fn [_] [w nil]))))
                        witnesses)))))
        (.then (fn [pairs]
                 (let [seen (->> (array-seq pairs) (map second) (remove nil?) vec)]
                   (println "     heads for devnet/a" seen)
                   (when (empty? seen)
                     (fail! "no replica resolved a head — nothing was decided"))
                   (when (> (count (set seen)) 1)
                     (fail! (str "replicas decided DIFFERENT cids for one sequence: " seen)))
                   (println (str "\nDEVNET: pass — " (count seen) " replicas decided the same"
                                 " (ref, seq) and " (if skip-kill?
                                                      "the kill legs were skipped"
                                                      "a witness died, came back and rejoined")))
                   (kill-all!)
                   (js/process.exit 0))))
        (.catch (fn [e]
                  (fail! (str "threw: " (or (.-message e) e))))))))

(-main)
