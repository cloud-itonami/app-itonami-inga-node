(ns inga-node.store
  "What survives a restart: this witness's own state, and the log everyone
  agreed about.

  ## This is the half a Durable Object was being used for

  The Cloudflare deployment got three things from a DO — a single writer, an
  address, and a place to keep a bounded snapshot. A long-lived process is
  the first two by construction. This file is the third, and it is a
  filesystem, which is the point: **delete this directory and the witness
  rejoins from its peers.** `inga.sync` is how a replica behind the others
  catches up, and a witness with no snapshot is simply the most behind.

  ## Two different durabilities, deliberately

  `snapshot` is PRIVATE working state, bounded by `inga.replica/snapshot`
  itself (`resume-tail` blocks, one committed block). Losing it costs this
  witness its head start, nothing more.

  `committed` is the SHARED log — one file per `(ref, seq)`, which is the
  identity the ref plane arbitrates, so the same record committed by four
  leaders is one file. First write wins, because when two different records
  contend for one sequence the log's ORDER decided it and a later write would
  contradict the thing that decided.

  Neither is a consensus primitive. Both are projections of a decision the
  quorum already made, which is why a filesystem is an honest place for them
  and a vendor's conditional write would not have been (ADR-2608039000)."
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            [inga-node.proof :as proof]
            ["node:fs" :as fs]
            ["node:path" :as path]))

(defn- ensure-dir! [d]
  (when-not (fs/existsSync d) (fs/mkdirSync d #js {:recursive true})))

(defn snapshot-path [data-dir] (path/join data-dir "snapshot.edn"))

(defn write-snapshot!
  "Write via a temp file and rename.

  A snapshot written in place is a snapshot that can be half a file when the
  process dies mid-write, and a half-read snapshot is refused by
  `inga.replica/resume` — which turns a crash into a witness that will not
  start. `rename` within one filesystem is atomic, so the file a reader sees
  is always a whole one."
  [data-dir snap]
  (ensure-dir! data-dir)
  (let [f (snapshot-path data-dir)
        tmp (str f ".tmp")]
    (fs/writeFileSync tmp (pr-str snap) "utf8")
    (fs/renameSync tmp f)))

(defn read-snapshot
  "The stored snapshot, or nil.

  Unreadable is treated as absent, and that is a decision rather than
  laziness: a stored value this version cannot parse must not brick the
  witness forever, because the failure is in the LOAD and every retry hits it
  again. Starting cold costs this witness its history — the others have it —
  while refusing to start costs the network a witness with no way back."
  [data-dir]
  (let [f (snapshot-path data-dir)]
    (when (fs/existsSync f)
      (try (edn/read-string (fs/readFileSync f "utf8"))
           (catch :default _
             (js/console.warn "inga-node: unreadable snapshot, starting cold")
             (try (fs/unlinkSync f) (catch :default _ nil))
             nil)))))

;; ── the shared committed log ────────────────────────────────────────────────

(defn- pad
  "Zero-padded to 12 digits so a LEXICOGRAPHIC listing is sequence order.

  `head-of` does not need this — it parses the number out of each filename
  and sorts numerically, so it is immune to `ls` putting 10 before 9. The
  padding is for everything that lists WITHOUT parsing: a human reading the
  directory, an object store's `list` (the shared-log sibling in
  `net-kotobase` pages through keys in lexicographic order), and any future
  consumer that assumes the obvious.

  Stated this way because the first version claimed the contiguous walk
  depended on it, and a test written against that claim passed with the
  padding removed — the claim was false and the test was checking something
  else."
  [n]
  (let [s (str n)]
    (str (str/join "" (repeat (max 0 (- 12 (count s))) "0")) s)))

(defn decode-proposal
  "A proposal string back into a head record, or nil.

  Total: a proposal this node cannot read is skipped rather than thrown from.
  The log is shared and another consumer may put its own proposals in it —
  engi's are transfer CIDs — and a ref plane that dies on someone else's
  entry is a ref plane one other tenant can stop."
  [s]
  (try
    (let [m (js->clj (js/JSON.parse s))]
      (when (and (map? m) (get m "ref") (get m "seq") (get m "cid")) m))
    (catch :default _ nil)))

(defn- record-path [data-dir rec]
  (path/join data-dir "committed"
             (js/encodeURIComponent (get rec "ref"))
             (str (pad (get rec "seq")) ".json")))

(defn- proof-path [data-dir rec]
  ;; A directory of its own, not a `<seq>.proof.json` beside the record.
  ;; `head-of` derives a sequence with `parseInt`, which stops at the first
  ;; non-digit, so `000000000000.proof` parses as 0 and a proof file would
  ;; masquerade as the genesis record.
  ;;
  ;; **EDN, not JSON.** A proof is the one value here whose exact shape is
  ;; load-bearing: `:inga.block/proposals` and `:inga.qc/block-hash` are
  ;; QUALIFIED keywords and `:inga.qc/witnesses` is a SET. `clj->js` writes a
  ;; keyword by its `name` alone, so a JSON round trip silently returns
  ;; `:proposals` and `:block-hash` and the certificate then names no block at
  ;; all. Measured: the first version of this did exactly that, and every
  ;; proof failed `:block-not-certified` with an empty hash.
  (path/join data-dir "proofs"
             (js/encodeURIComponent (get rec "ref"))
             (str (pad (get rec "seq")) ".edn")))

(defn sink-blocks!
  "Write every head record in `blocks` that is not already logged, and beside
  it the proof that it was committed. Returns how many records were created.

  `qcs` is the certificate map keyed by block hash, and `hash-fn` the
  deployment's own — `inga.commitment` verifies a record by MEMBERSHIP (this
  record is in this block, this block's hash is the one the certificate
  names), because inga's witnesses sign a vote payload over a block hash and
  never the head record itself. Without the block and the certificate, a
  reader can only take this node's word for the log, which is the one thing
  `inga.ref` exists not to do.

  Both are optional. A caller with no certificates writes records exactly as
  before and readers fall back to trusting the host — worse, and still
  working, rather than a node that refuses to log."
  ([data-dir blocks] (sink-blocks! data-dir blocks nil nil))
  ([data-dir blocks qcs hash-fn]
   (reduce
    (fn [n [block rec]]
      (let [f (record-path data-dir rec)]
        (if (fs/existsSync f)
          n
          (do (ensure-dir! (path/dirname f))
              (fs/writeFileSync f (js/JSON.stringify (clj->js rec)) "utf8")
              (when-let [qc (and hash-fn qcs (get qcs (hash-fn block)))]
                (let [pf (proof-path data-dir rec)]
                  (ensure-dir! (path/dirname pf))
                  ;; Written AFTER the record, so a crash between the two
                  ;; leaves a record without a proof rather than a proof
                  ;; without a record. A reader treats the first as
                  ;; unverifiable and the second as a phantom head.
                  ;; `*print-namespace-maps*` pinned off, the same reason
                  ;; `kekkai.envelope` pins it: a map whose keys share a
                  ;; namespace prints as `#:inga.qc{…}` by default in some
                  ;; contexts and not others, so a file written by hand and a
                  ;; file written by this process would differ. `clojure.edn`
                  ;; reads both, so this is stability rather than correctness
                  ;; — and a proof that reads back differently depending on
                  ;; who wrote it is a proof nobody can diff.
                  (fs/writeFileSync pf (proof/encode {:block block :qc qc}) "utf8")))
              (inc n)))))
    0
    (for [b blocks
          rec (keep decode-proposal (:inga.block/proposals b))]
      [b rec]))))

(defn proof-text
  "The proof for `ref`/`seq` as the EDN text it is stored as, or nil.

  Text rather than a parsed value, because the HTTP layer serves JSON and a
  parsed proof would have to be re-encoded to get there — which is the lossy
  step this format exists to avoid. One string crosses the wire and the client
  parses it with the same `decode-proof` this node uses."
  [data-dir ref seq']
  (let [f (path/join data-dir "proofs" (js/encodeURIComponent ref)
                     (str (pad seq') ".edn"))]
    (when (fs/existsSync f)
      (try (fs/readFileSync f "utf8") (catch :default _ nil)))))

(defn proof-of
  "`{:block :qc}` for the record at `ref`/`seq`, or nil."
  [data-dir ref seq']
  (some-> (proof-text data-dir ref seq') proof/decode))

(defn count-records [data-dir]
  (let [root (path/join data-dir "committed")]
    (if-not (fs/existsSync root)
      0
      (reduce (fn [n d]
                (+ n (count (fs/readdirSync (path/join root d)))))
              0 (fs/readdirSync root)))))

(defn head-of
  "The head record for `ref`: the highest sequence reachable CONTIGUOUSLY
  from 0, which is the rule `inga.ref/head-of` applies to the in-memory
  projection. A gap means some earlier sequence was never committed, and a
  head past a gap would claim a history the log does not have."
  [data-dir ref]
  (let [d (path/join data-dir "committed" (js/encodeURIComponent ref))]
    (when (fs/existsSync d)
      (let [seqs (->> (fs/readdirSync d)
                      (keep (fn [f]
                              (let [n (js/parseInt (str/replace f #"\.json$" "") 10)]
                                (when-not (js/isNaN n) n))))
                      sort vec)
            top (reduce (fn [expected n] (if (= n expected) (inc expected) (reduced expected)))
                        0 seqs)]
        (when (pos? top)
          (try (js->clj (js/JSON.parse
                         (fs/readFileSync (path/join d (str (pad (dec top)) ".json")) "utf8")))
               (catch :default _ nil)))))))
