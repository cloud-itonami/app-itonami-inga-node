(ns inga-node.archive-test
  (:require [clojure.test :refer [deftest is async]]
            [clojure.edn :as edn]
            [inga-node.archive :as archive]
            [inga-node.node :as node]
            [inga.reservation-test :as fixture]
            [inga.replica :as replica]
            [kotobase.cid :as cid]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:os" :as os]))

(defn tmp [] (fs/mkdtempSync (path/join (os/tmpdir) "inga-prefix-test-")))
(defn disk-fetch [dir requests]
  (fn [url init]
    (swap! requests conj {:url url :method (.-method init) :headers (.-headers init)})
    (when-not (.startsWith url "https://kotobase.net/ipld/")
      (throw (js/Error. "unexpected persistence authority")))
    (let [key (.substring url (count "https://kotobase.net/ipld/"))]
      (when-not (re-matches #"b[a-z2-7]+" key) (throw (js/Error. "bad CID")))
      (let [p (path/join dir key)]
        (js/Promise.resolve
         (if (= "PUT" (.-method init))
           (do (fs/writeFileSync p (.-body init)) (js/Response. nil #js {:status 201}))
           (if (fs/existsSync p)
             (js/Response. (fs/readFileSync p) #js {:status 200})
             (js/Response. nil #js {:status 404}))))))))

(defn opened [dir calls & [context]]
  (let [nonce (atom 0)]
    (archive/open {:context (or context fixture/context) :max-bytes (* 4 1024 1024)
                   :authorization #(str "test-only-nonce-" (swap! nonce inc))
                   :fetch-fn (disk-fetch dir calls)})))
(defn envelope [proof]
  {:version archive/version :chain-id fixture/chain-id
   :genesis-hash ((:hash-fn fixture/context) fixture/genesis) :proof proof})
(defn seed-bytes! [dir env]
  (let [bytes (cid/text->bytes (pr-str env)) key (archive/raw-cid bytes)]
    (fs/writeFileSync (path/join dir key) bytes)
    key))
(defn rejected [promise reason]
  (-> promise
      (.then (fn [_] (is false (str "unexpected success: " reason))))
      (.catch (fn [e]
                (is (= reason (archive/refusal-reason e)))))))

(deftest durable-prefix-survives-new-client-and-bounded-replica-tail
  (async done
    (let [dir (tmp) calls (atom []) p (fixture/proof [[fixture/a] [] [] [] [] [] [] [] [] [] []])
          first-proof {:blocks (subvec (:blocks p) 0 8)
                       :tip-qc (fixture/certify (nth (:blocks p) 7) (take 3 fixture/witnesses))}]
      (-> (archive/publish! (opened dir calls) first-proof)
          (.then (fn [checkpoint]
                   (is (= 8 (:block-count checkpoint)))
                   ;; Throw away all client state. New process/client needs
                   ;; only the CID and configured trust context.
                   (-> (archive/read! (opened dir calls) (:cid checkpoint))
                       (.then (fn [restored]
                                (is (= first-proof (:proof restored)))
                                (is (= (:cid checkpoint) (get-in restored [:checkpoint :cid])))
                                (archive/extend! (opened dir calls) (:cid checkpoint)
                                                 {:blocks (vec (take-last replica/resume-tail (:blocks p)))
                                                  :tip-qc (:tip-qc p)}))))))
          (.then (fn [checkpoint]
                   (is (= 12 (:block-count checkpoint)))
                   (is (= 9 (:finalized-height checkpoint)))
                   (archive/response! (opened dir calls) {:method "GET" :checkpoint (:cid checkpoint)})))
          (.then (fn [response]
                   (is (= 200 (:status response)))
                   (is (= p (:proof (edn/read-string (:body response)))))
                   (is (every? #(.startsWith (:url %) "https://kotobase.net/ipld/") @calls))))
          (.catch (fn [e] (is false (str "restore failed: " e " " (ex-data e)))))
          (.finally done)))))

(deftest missing-tampered-reordered-chain-and-bounds-are-refused
  (async done
    (let [dir (tmp) calls (atom []) client (opened dir calls)
          proof (fixture/proof [[fixture/a] [] [] []])
          variants [[(update proof :blocks #(vec (concat (subvec % 0 1) (subvec % 2)))) :does-not-attach]
                    [(assoc-in proof [:blocks 1 :inga.block/ts] 998) :does-not-link]
                    [(update proof :blocks #(assoc % 1 (nth % 2) 2 (nth % 1))) :non-contiguous]]
          valid-cid (seed-bytes! dir (envelope proof))]
      (-> (reduce (fn [promise [bad reason]]
                    (.then promise (fn [_]
                                     (rejected (archive/read! client (seed-bytes! dir (envelope bad))) reason))))
                  (js/Promise.resolve nil) variants)
          (.then (fn [_] (rejected (archive/read! client (seed-bytes! dir (assoc (envelope proof) :chain-id "other"))) :wrong-chain)))
          (.then (fn [_] (rejected (archive/read! client (seed-bytes! dir (assoc (envelope proof) :genesis-hash "other"))) :wrong-genesis)))
          (.then (fn [_] (rejected (archive/read! (opened dir calls (assoc fixture/context :max-blocks 4)) valid-cid) :max-blocks-exceeded)))
          (.then (fn [_]
                   (fs/writeFileSync (path/join dir valid-cid) "tampered")
                   (rejected (archive/read! client valid-cid) :kotobase.blocks/corrupt)))
          (.then (fn [_]
                   (fs/unlinkSync (path/join dir valid-cid))
                   (rejected (archive/read! client valid-cid) :missing-checkpoint)))
          (.then (fn [_] (archive/response! client {:method "GET" :checkpoint valid-cid})))
          (.then (fn [r] (is (= 422 (:status r)))))
          (.catch (fn [e] (is false (str e " " (ex-data e)))))
          (.finally done)))))

(deftest cannot-publish-invalid-proof-or-announce-missing-readback
  (async done
    (let [dir (tmp) calls (atom []) client (opened dir calls)
          proof (fixture/proof [[fixture/a] [] []])]
      (-> (rejected (archive/publish! client {:blocks (subvec (:blocks proof) 0 2)}) :incomplete-prefix)
          (.then (fn [_] (is (empty? @calls))))
          (.then (fn [_]
                   (let [a (archive/open {:context fixture/context :max-bytes 8
                                          :authorization (constantly "test") :fetch-fn (disk-fetch dir calls)})]
                     (rejected (archive/publish! a proof) :max-bytes-exceeded))))
          (.then (fn [_]
                   (let [a (archive/open {:context fixture/context :max-bytes 100000
                                          :authorization (constantly "test")
                                          :fetch-fn (fn [_ init]
                                                      (js/Promise.resolve (js/Response. nil #js {:status (if (= "PUT" (.-method init)) 201 404)})))})]
                     (rejected (archive/publish! a proof) :missing-checkpoint))))
          (.catch (fn [e] (is false (str e))))
          (.finally done)))))

(deftest incompatible-extension-and-wrong-checkpoint-bytes-refused
  (async done
    (let [dir (tmp) calls (atom []) client (opened dir calls)
          proof (fixture/proof [[fixture/a] [] []]) other (fixture/proof [[fixture/b] [] []])]
      (-> (archive/publish! client proof)
          (.then (fn [checkpoint]
                   (-> (rejected (archive/extend! client (:cid checkpoint) other) :finalized-prefix-conflict)
                       (.then (fn [_] (rejected (archive/extend! client (:cid checkpoint)
                                                                 {:blocks [{:inga.block/height 8}]}) :missing-segment)))
                       (.then (fn [_]
                                (let [other-cid (seed-bytes! dir (envelope other))]
                                  (fs/copyFileSync (path/join dir other-cid) (path/join dir (:cid checkpoint)))
                                  (rejected (archive/read! client (:cid checkpoint)) :kotobase.blocks/corrupt)))))))
          (.catch (fn [e] (is false (str e " " (ex-data e)))))
          (.finally done)))))

(deftest http-distributes-requested-checkpoint-after-node-restart
  (async done
    (let [dir (tmp) data (tmp) calls (atom []) proof (fixture/proof [[fixture/a] [] []])
          p (+ 26000 (mod (.-pid js/process) 1000)) running (atom nil)
          boot (fn [port]
                 (node/start! {:witness "w1" :witnesses fixture/witnesses :peers {}
                               :pubkeys fixture/pubkeys :seed-hex (fixture/seeds "w1")
                               :chain-id fixture/chain-id :port port :tick-ms 100000
                               :data-dir data
                               :prefix-service #(archive/response! (opened dir calls) %)}))
          get! (fn [port checkpoint]
                 (js/fetch (str "http://127.0.0.1:" port "/finalized-prefix?checkpoint=" checkpoint)))]
      (let [checkpoint (atom nil)]
        (-> (archive/publish! (opened dir calls) proof)
            (.then (fn [result]
                     (reset! checkpoint result)
                     (reset! running (boot p))
                     (get! p (:cid result))))
            (.then (fn [r] (is (= 200 (.-status r))) (.text r)))
            (.then (fn [body]
                     (is (= proof (:proof (edn/read-string body))))
                     ((:stop! @running))
                     (reset! running (boot (inc p)))
                     (get! (inc p) (:cid @checkpoint))))
            (.then (fn [r] (is (= 200 (.-status r))) (.text r)))
            (.then (fn [body]
                     (let [result (edn/read-string body)]
                       (is (= (:cid @checkpoint) (get-in result [:checkpoint :cid])))
                       (is (= proof (:proof result))))
                     (get! (inc p) "wrong-checkpoint")))
            (.then (fn [r]
                     (is (= 422 (.-status r)))
                     (js/fetch (str "http://127.0.0.1:" (inc p) "/finalized-prefix") #js {:method "POST"})))
            (.then (fn [r] (is (= 405 (.-status r)))))
            (.catch (fn [e] (is false (str "HTTP test failed: " e))))
            (.finally (fn [] (when @running ((:stop! @running))) (done))))))))

(deftest duplicate-publish-keeps-cid-and-uses-new-write-authorization
  (async done
    (let [dir (tmp) calls (atom []) client (opened dir calls)
          p (fixture/proof [[fixture/a] [] []]) first-result (atom nil)]
      (-> (archive/publish! client p)
          (.then (fn [result] (reset! first-result result) (archive/publish! client p)))
          (.then (fn [result]
                   (is (= @first-result result))
                   (let [writes (filter #(= "PUT" (:method %)) @calls)]
                     (is (= 2 (count writes)))
                     (is (= 2 (count (set (map #(aget (:headers %) "authorization") writes))))))))
          (.catch (fn [e] (is false (str e))))
          (.finally done)))))

(deftest captures-real-replica-and-refuses-uncertified-state
  (async done
    (let [dir (tmp) calls (atom []) client (opened dir calls)
          states (fixture/network (fn [_ _] true)) s (get states "w1")]
      (-> (archive/capture! client nil s)
          (.then (fn [checkpoint]
                   (is (pos? (:finalized-height checkpoint)))
                   (archive/read! (opened dir calls) (:cid checkpoint))))
          (.then (fn [restored]
                   (is (seq (get-in restored [:proof :blocks])))
                   (rejected (archive/capture! client nil (replica/replica (fixture/opts "w1"))) :unfinalized)))
          (.then (fn [_]
                   (let [bad (assoc (fixture/proof [[fixture/a] [] []]) :tip-qc {})]
                     (rejected (archive/read! client (seed-bytes! dir (envelope bad))) :invalid-tip-certificate))))
          (.catch (fn [e] (is false (str e " " (ex-data e)))))
          (.finally done)))))
