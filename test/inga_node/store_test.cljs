(ns inga-node.store-test
  "The disk. This is the half a Durable Object was being used for, so it is
  the half that has to survive the process it belongs to."
  (:require [clojure.test :refer [deftest is testing]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [inga-node.store :as store]
            [inga-node.identity :as ident]
            [inga-node.seams :as seams]))

(defn- tmp []
  (fs/mkdtempSync (path/join (os/tmpdir) "inga-node-test-")))

(defn- rec [ref seq' cid]
  {"v" "inga.head/v1" "ref" ref "seq" seq' "cid" cid "prev" nil "height" nil})

(defn- block [& proposals]
  {:inga.block/height 1 :inga.block/proposals (mapv #(js/JSON.stringify (clj->js %)) proposals)})

;; ── snapshot ────────────────────────────────────────────────────────────────

(deftest snapshot-round-trips-namespaced-keywords
  (testing "EDN text, not clj->js — the lossy path took four replicas dark at once"
    (let [d (tmp)
          snap {:inga.snapshot/version 1 :witness "w1" :committed []
                :pm {:view 7 :deadline 0} :nested {:k :a-keyword-value}}]
      (store/write-snapshot! d snap)
      (is (= snap (store/read-snapshot d))))))

(deftest a-missing-snapshot-is-nil-not-an-error
  (is (nil? (store/read-snapshot (tmp)))))

(deftest an-unreadable-snapshot-starts-cold-and-does-not-brick
  (testing "the failure is in the LOAD, so every retry hits it again"
    (let [d (tmp)]
      (fs/writeFileSync (store/snapshot-path d) "{:this is not( readable" "utf8")
      (is (nil? (store/read-snapshot d)))
      (is (not (fs/existsSync (store/snapshot-path d)))
          "and the poison is removed, so the next start is clean"))))

(deftest snapshot-leaves-no-temp-file-behind
  (let [d (tmp)]
    (store/write-snapshot! d {:a 1})
    (is (= #{"snapshot.edn"} (set (fs/readdirSync d)))
        "a rename that left its temp file would grow the data dir forever")))

;; ── the committed log ───────────────────────────────────────────────────────

(deftest one-record-is-one-file-however-many-leaders-commit-it
  (testing "the key IS the deduplication: (ref, seq) is what the ref plane arbitrates"
    (let [d (tmp)
          same (rec "g" 0 "cid-a")]
      (is (= 1 (store/sink-blocks! d [(block same same same same)])))
      (is (= 1 (store/count-records d)))
      (is (zero? (store/sink-blocks! d [(block same)]))
          "a second commit of the same record writes nothing"))))

(deftest first-write-wins-for-a-contested-sequence
  (testing "the log's ORDER decided it; a later write would contradict the decision"
    (let [d (tmp)]
      (store/sink-blocks! d [(block (rec "g" 0 "winner"))])
      (store/sink-blocks! d [(block (rec "g" 0 "loser"))])
      (is (= 1 (store/count-records d)))
      (is (= "winner" (get (store/head-of d "g") "cid"))))))

(deftest a-late-certificate-backfills-the-existing-record-proof
  (testing "record visibility must not permanently suppress quorum evidence"
    (let [d (tmp)
          b (block (rec "g" 0 "cid-a"))
          bh (seams/hash-fn b)
          qc {:inga.qc/block-hash bh :inga.qc/witnesses #{"w1" "w2" "w3"}}]
      (is (= 1 (store/sink-blocks! d [b] nil seams/hash-fn)))
      (is (nil? (store/proof-text d "g" 0)))
      (is (zero? (store/sink-blocks! d [b] {bh qc} seams/hash-fn))
          "only the proof is filled; the record is not counted twice")
      (is (string? (store/proof-text d "g" 0))
          "the same committed block acquires its proof after the QC arrives"))))

(deftest a-proposal-this-node-cannot-read-is-skipped
  (testing "the log is shared; another consumer's entry must not stop the ref plane"
    (let [d (tmp)]
      (is (= 1 (store/sink-blocks!
                d [{:inga.block/proposals
                    ["bafy-someone-elses-transfer-cid"
                     "{not json"
                     (js/JSON.stringify (clj->js (rec "g" 0 "cid-a")))]}])))
      (is (= 1 (store/count-records d))))))

(deftest head-of-stops-at-a-gap
  (testing "a head past a gap claims a history the log does not have"
    (let [d (tmp)]
      (store/sink-blocks! d [(block (rec "g" 0 "c0") (rec "g" 1 "c1") (rec "g" 3 "c3"))])
      (is (= "c1" (get (store/head-of d "g") "cid")))
      (is (= 3 (store/count-records d)) "the gapped record is still STORED, just not head"))))

(deftest head-of-is-nil-for-an-unknown-ref
  (is (nil? (store/head-of (tmp) "never-written"))))

(deftest head-of-walks-past-single-digits
  (testing "the walk parses numbers rather than comparing filenames"
    ;; NOT a test of the zero-padding: removing `pad` leaves this green,
    ;; which is how the padding's original docstring was found to be wrong.
    ;; What it does check is that sequence 10 and 11 are reachable at all —
    ;; a walk that compared strings would stop at 1.
    (let [d (tmp)]
      (store/sink-blocks! d [(apply block (map #(rec "g" % (str "c" %)) (range 0 12)))])
      (is (= "c11" (get (store/head-of d "g") "cid")))
      (is (= 12 (store/count-records d))))))

(deftest a-ref-containing-a-slash-does-not-escape-its-directory
  (let [d (tmp)]
    (store/sink-blocks! d [(block (rec "kotobase/db/did:key:zAbc/mydb" 0 "c"))])
    (is (= "c" (get (store/head-of d "kotobase/db/did:key:zAbc/mydb") "cid")))
    (is (= 1 (count (fs/readdirSync (path/join d "committed"))))
        "one directory, not a tree carved out of the ref")))

;; ── identity ────────────────────────────────────────────────────────────────

(deftest the-seed-is-minted-once-and-kept
  (let [d (tmp)
        a (ident/ensure-seed! d)
        b (ident/ensure-seed! d)]
    (is (= a b) "a restart must not change this witness's identity")
    (is (= 64 (count a)))))

(deftest the-seed-file-is-not-world-readable
  (testing "0600 at creation — a key that was readable for a microsecond was readable"
    (let [d (tmp)]
      (ident/ensure-seed! d)
      (is (= "600" (.toString (bit-and (.-mode (fs/statSync (path/join d "seed"))) 0777) 8))))))

(deftest two-nodes-mint-two-identities
  (testing "generated locally, so no two witnesses can share a key by accident of config"
    (is (not= (ident/ensure-seed! (tmp)) (ident/ensure-seed! (tmp))))))
