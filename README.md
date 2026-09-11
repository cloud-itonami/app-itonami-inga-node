# inga-node

The mechanism layer of [`inga`](https://github.com/kotoba-lang/inga): one
witness, one long-lived process, one socket to every peer, one disk.
**`inga` decides; this executes.** Nothing here casts a vote, orders a block
or picks a leader.

Design: superproject
[ADR-2608670000](https://github.com/com-junkawasaki/root/blob/main/90-docs/adr/2608670000-inga-witnesses-are-processes-not-durable-objects.edn).

## Why it exists

The ref plane's first deployment ran its witnesses as Cloudflare **Durable
Objects**. A DO passes the deletion test the wrong way: remove it and the
plane does not merely slow down, it stops — the witness has no identity and
no serialiser without it. That makes it a *premise*, which is exactly what
superproject ADR-2608039000 forbids on a path that calls itself
decentralised, and it is the same objection that already retired D1.

A DO was being asked for three things. A process gives all three and asks
nobody:

| Durable Object | here |
|---|---|
| one writer per witness | **the process is one writer.** No lease, no fencing epoch |
| an address | a port |
| a bounded snapshot | a file |

## The keys are generated here, never distributed

Each node mints its own Ed25519 seed on first start into `INGA_NODE_DATA`,
0600, and publishes only the public half. **Nothing secret is ever
transmitted**, which is why this can run on a fleet whose standing invariant
is that nodes are given tests and not secrets.

It is also what changes the fault-model claim. The DO deployment held all
four seeds in one Worker `env` and said so honestly: four replicas under one
operator is *crash* fault tolerance, because compromising that Worker
compromises every witness at once with no threshold left to cross. Witnesses
that each generated their own key on their own machine have a threshold.

## Run one

```bash
INGA_WITNESS=w1 INGA_WITNESSES=w1,w2,w3,w4,w5,w6,w7 \
INGA_PEERS='w2=ws://judah:19402,w3=ws://levi:19403,...' \
INGA_PUBKEYS='w2=<hex>,w3=<hex>,...' \
INGA_PORT=19401 INGA_TICK_MS=1000 INGA_NODE_DATA=~/.inga/w1 \
nbb --classpath "src:../inga/src" script/run.cljs
```

`INGA_PRINT_PUBKEY=1` mints the seed if absent, prints `w1=<hex>` and exits —
the bootstrap step, run once per node, whose output is public.

| route | |
|---|---|
| `GET /health` | height, committed height, view, pending, peers, and which step of a round is missing |
| `GET /committed[?ref=]` | how many records the durable log holds; the head for a ref |
| `POST /submit` | `{"record": {...}}` into this witness's pending |

## The devnet, and what it currently proves

```bash
nbb --classpath "src:../inga/src" script/devnet.cljs      # 7 processes, one gets killed
SKIP_KILL=1 nbb --classpath "src:../inga/src" script/devnet.cljs
N=4 nbb --classpath "src:../inga/src" script/devnet.cljs
```

**The default run passes** (measured 2026-08-11, against `inga` at
`f75d3db`). Seven separate OS processes, real sockets, real Ed25519, each with
its own key and its own disk: they agree on a chain, commit submitted records
to a durable log, **keep committing when one of them is killed**, and take the
killed one back when it restarts from its own directory.

```
ok   - 7 processes committed a submitted record
ok   - 6 of 7 kept committing with w3 dead
ok   - w3 restarted, resumed from its own disk and caught up
     heads for devnet/a [cid-a cid-a cid-a cid-a cid-a cid-a cid-a]
```

That last line is the assertion that matters and the one that is stable: two
replicas that both decided a `(ref, seq)` decided the same cid. Committed
HEIGHTS are not asserted — seven processes exchanging real messages are never
at one height at an instant, and a threshold on a moving quantity turns
ordinary in-flight work into a failure.

**This used to fail, and for most of this repo's life it did.** Killing one
witness of seven — quorum five, six remaining — stopped the chain for good,
because leadership was keyed by height and a departed leader held its turn
forever. `inga` now carries the round in the block and lets a proposer claim a
skipped one when it holds the evidence that the intervening leaders produced
nothing (superproject ADR-2608680000). `SKIP_KILL=1` still exists and still
skips the kill legs, and says so rather than reporting them as passed.

**Restart is also proven by the unit suite, not only here.** `devnet`'s
restart leg runs after its kill leg, so while the kill leg failed the restart
leg proved nothing; `node-test` checks it directly — submit, stop, start again
on the same directory, and the queued work is still there. Disabling
`read-snapshot` turns that test red.

## The three parts assembled (2026-08-20)

`inga_node.ref` is one `kotobase.storage.core/IRefStore`: it reads a head,
**proves it was committed** with `inga.commitment`, and compare-and-sets by
submitting and waiting. Until now the `head-of!` seam, the verifier and the
witness that serves `{block, qc}` were each measured and never put together.

```clojure
(nref/ref-store
 {:committed (fn [ref-name] {:head … :proof "<edn>"})   ; GET /committed?ref=
  :submit!   (fn [record] …)                            ; POST /submit
  :sleep!    (fn [ms] …)                                ; must BLOCK
  :context   {…}})                                      ; inga.commitment
```

**The transport is injected and this namespace is `.cljc`, because
`-compare-and-set-ref!` is synchronous and a commit is not.** On Node nothing
can block, so a live deployment of this store is a JVM consumer supplying
`java.net.http` and a real sleep. That is `inga.ref`'s own reading — its
docstring says the waiting lives in the host — made concrete rather than
restated.

The proof's format lives in its own portable `inga_node.proof` — the store is
`.cljs` because it is a filesystem, and a `.cljc` namespace that requires a
ClojureScript one is portable in name only. clj-kondo said so before a
consumer did; `clojure -M -e \"(require 'inga-node.ref)\"` on a JVM is the
check that it is not just quiet.

`ref_test` drives it over the **real bytes**: `store/sink-blocks!` writes the
proof, real Ed25519 keys sign the votes, `seams/hash-fn` hashes the block, and
only the socket is replaced by a direct read of the files the HTTP surface
serves. The load-bearing assertion is that editing a proof on disk makes the
head **absent** — the store verifies rather than reads — and restoring it
brings the head back. Breaking `verified-head` to return the head unverified
fails that assertion and the wrong-ref one, and nothing else.

What is still untested here is the socket and the waiting, and both are the
caller's by construction.

## Two writers, one ref, one sequence (2026-08-19)

`script/ref_race.cljs` asks the network the question `inga`'s own `ref_test`
asks a cooperative oracle in-process: two records for the **same `[ref seq]`**
with different CIDs, submitted **concurrently to two different witnesses**, and
the answer read from a **third** that received neither.

```bash
npm install
INGA_CP="src:<inga>/src:<kotobase-storage>/src" \
  nbb --classpath "src:<inga>/src:<kotobase-storage>/src" script/ref_race.cljs
```

The decision procedure is not reimplemented — `inga.ref/project` folds the
committed log first-wins and `inga.ref/outcome` says whether a record won its
sequence. This script only supplies the network under it. Measured, seven
witnesses:

```
committed log:  [[0 "cid-from-bot-b"]]
bot-b outcome:  {:certified? true}
bot-c outcome:  {:certified? false, :current "cid-from-bot-b"}
```

The loser's record is never written at all — `store/sink-blocks!` skips a
sequence whose file exists, which is where first-wins actually lands on disk.
The loser being *told the winning CID* is the half that makes a loss
recoverable: a writer that learns only `false` retries against the base it
already knows is stale.

The head now arrives **with a proof that it was committed**, and the run
verifies it offline: `inga.commitment/verify-head` recomputes the block hash,
checks the certificate names that block, checks the certificate against the
real Ed25519 witness keys, and checks the record is among the block's
proposals. Three ways of being wrong are refused beside it — a certificate for
another block, forged signatures, and a real proof answered against a ref it
was not made for.

**Proofs are stored and served as EDN, not JSON.** `:inga.block/proposals` and
`:inga.qc/block-hash` are qualified keywords and `:inga.qc/witnesses` is a set;
`clj->js` writes a keyword by its `name` alone, so the first version of this
silently produced `:proposals` and `:block-hash` and every proof failed
`:block-not-certified` with an empty hash. The wire carries one EDN string and
the client parses it with the same `store/decode-proof` the node uses.

`BOTH_WIN=1` is the control and the run must FAIL under it: the second writer
moves to seq 1, both records commit legitimately, and `exactly one writer won`
has to notice. **It did not, at first.** The first version projected the
`/committed?ref=` head alone, and a single head cannot show two winners at
different sequences — the control passed while both records sat in the log.
The script now projects the log.

## Standing witnesses up on a fleet

```bash
nbb script/fleet.cljs pubkeys judah,benjamin,simeon,joseph
nbb script/fleet.cljs config  judah,benjamin,simeon,joseph
```

`pubkeys` mints `~/.inga/<witness>/seed` on each host if it is absent, 0600,
and prints the PUBLIC half. **Nothing secret crosses the link in either
direction.** Re-running is idempotent — the same four public keys come back,
because an identity that changes on every provisioning run is not an identity.

Measured 2026-08-11 on four murakumo nodes: four keys, four machines,
`-rw-------` on each seed. That is what makes the fault-model claim real
rather than aspirational — four machines that each generated their own key
have a threshold to cross; four Durable Objects sharing one `env` do not.

`config` prints the `INGA_WITNESSES` / `INGA_PUBKEYS` / per-node `INGA_PEERS`
strings. Peers are addressed by tailnet address: **bare hostnames do not
resolve on the nodes themselves** (`ping benjamin` → Unknown host, measured),
only `tailscale ping` does.

## It runs on the fleet (2026-08-11)

Four witnesses on four murakumo nodes — judah, benjamin, simeon, joseph —
each with its own Ed25519 key on its own disk, talking over tailnet:

```
judah     h 46 ch 44 out 3 in 3 fault byzantine-capable (one key per host)
benjamin  h 46 ch 44 out 3 in 3
joseph    h 46 ch 44 out 3 in 3
```

and a submitted record reaching every durable log:

```
judah     head {ref fleet/first, seq 0, cid bafyfleet1}
benjamin  head {ref fleet/first, seq 0, cid bafyfleet1}
joseph    head {ref fleet/first, seq 0, cid bafyfleet1}
```

This is the deployment ADR-2608670000 chose over Durable Objects, actually
standing up. The fault-model string is not aspirational here: those four keys
were generated on four machines and none of them has ever been transmitted.

**Node version was a variable and is not any more.** The one node on Node 22
died at startup with an undici stack while the three on Node 26 formed a
chain: a failed outbound `new WebSocket(...)` escaped `onerror` there. The
client now uses the `ws` package the server side already used, so the runtime
version stops mattering. (The immediate crash was startup ordering — dialling
peers that were not listening yet — and it recovers once they are.)

## Layout

```
src/inga_node/identity.cljs  the seed, minted locally, never sent
src/inga_node/seams.cljs     hash/sign/verify over node:crypto — byte-identical
                             to the Worker deployment on purpose
src/inga_node/store.cljs     snapshot (private, bounded) + committed log
                             (shared, one file per (ref, seq), first write wins)
src/inga_node/node.cljs      the wiring: sockets, clock, HTTP
script/run.cljs              one witness from the environment
script/devnet.cljs           seven processes and a kill
```

## Test

```bash
INGA_SRC=../inga/src npm test          # 20 unit tests, ~2s
nbb --classpath "src:../inga/src" script/devnet.cljs   # 7 processes, ~1min
```

The unit suite covers the three pieces that REPLACE the Durable Object —
`seams` (the crypto, including the DER splice that would otherwise fail
silently), `identity` (the seed minted locally, 0600, stable across restarts)
and `store` (snapshot atomicity, first-write-wins, the contiguous head walk).

Each was checked by breaking it: one byte off the DER prefix fails three
assertions. One that did NOT bite is recorded rather than quietly fixed — the
zero-padding turned out not to be load-bearing for `head-of`, which parses
numbers instead of comparing filenames, so the docstring claiming otherwise
was wrong and now says so.

### Vote durability and restart boundary

Every `advance!` transition now persists its next replica snapshot before
shipping its outbox. Snapshot writes fsync the file, rename it, and fsync the
directory. A persistence error emits no messages from that transition and
poisons the running instance; subsequent `/submit` requests receive 503 rather
than overwriting an uncertain durable state. The requester must treat that
response as unresolved, not as evidence that its proposal lost.

Unreadable, incompatible, wrong-witness, or vote-watermark-free snapshots fail
startup. Restore the witness's durable state; do not delete it to resume using
the same key. A missing snapshot is still allowed for first boot: protecting
against disk deletion/rollback and cloned processes requires deployment-level
identity ownership/recovery fencing. Local files are not that fencing, and a
single event loop does not exclude another process holding the same key.

The unit suite exercises the actual `advance!` closure returned by `start!`,
real signed replica transitions, injected persistence failure, and a second
`start!` with the same key after stopping before/after shipment. Delivery is
captured locally. These are deterministic crash-boundary tests, not power-loss
or multi-host network qualification. All keys in tests are newly generated.
