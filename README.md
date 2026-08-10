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

**`SKIP_KILL=1` passes.** Seven separate OS processes, real sockets, real
Ed25519, each with its own key and its own disk, agree on a chain and commit
submitted records to a durable log. That is the whole DO-free claim and it
holds.

**The default run FAILS, on purpose, and it is not this repo's bug.** Kill one
witness of seven — quorum is five, six remain, there is a spare — and the
chain stops for good:

```
before the kill   h=28 ch=26 view=30 nv=2  qcs=28   (all seven agree)
after             h=29 ch=27 view=38 nv=9  qcs=29   (six agree, height frozen)
```

Views stay synchronised, leadership rotates onto witnesses that are alive, and
`new-view` groups pile up — so the timeout path works and **the step from a
new-view quorum to a proposal does not**. It never recovers.

`inga` has 358 tests and a socket harness that survives a Byzantine validator
for a hundred blocks, and none of them can see this: its `evict!` rebuilds a
replica *in place* and leaves the peer connected, so **state loss is tested
and departure is not**. This harness is the first thing to remove a witness.

Until that is fixed, this repo is a reproduction, not a deployment. Standing
one up would replace a plane that depends on a vendor with a plane that stops
when a machine reboots.

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
