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
