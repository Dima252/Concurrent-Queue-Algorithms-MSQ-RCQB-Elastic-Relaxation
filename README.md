# Concurrent Queue Algorithms: MSQ, RCQB, and Elastic Relaxation

A Java implementation and comparative study of three concurrent queue algorithms,
developed as the final project for the **Multi-Core Programming** course.

The project follows a clear academic progression: implement the classic 1996 baseline,
then the 2022 state-of-the-art relaxed structure, then contribute an original adaptive
variant that combines ideas from both papers. The headline result: our adaptive queue
(ERQ) outscales both baselines and, unlike the relaxed queue, delivers that throughput
*predictably* rather than in unstable bursts.

See [`PROGRESS.md`](PROGRESS.md) for a short, plain-language account of how the project
evolved to its final state.

---

## The Problem

A concurrent queue shared between producer and consumer threads must protect its
`head` and `tail` pointers from simultaneous writes. The standard tool is
**Compare-And-Swap (CAS)**: read a pointer, compute a new value, write it only if
no one else changed it since the read. Under low concurrency this works well. Under
heavy load, every thread races to CAS the same pointer — most fail, spin, and retry.
**Throughput collapses even as more hardware is added.**

The two academic papers this project is based on each take a different approach to
this bottleneck.

---

## The Three Algorithms

### 1 · MSQ — Michael-Scott Queue
**Source:** Michael & Scott, PODC 1996
**File:** `src/queue/MichaelScottQueue.java`

The textbook lock-free FIFO queue. A singly-linked list with a sentinel (dummy)
head node and two `AtomicReference` pointers.

**Enqueue:** CAS on `tail.next` (null → new node), then swing `tail` forward.
If another thread's enqueue is half-done (tail is lagging), this thread helps
advance it before retrying — the "helping" pattern from the paper.

**Dequeue:** reads the value at `head.next` *before* the CAS — this is a critical
ordering detail from §D12 of the paper. Then CAS `head` forward to the next node.

**Ordering:** strict FIFO. Items come out in exactly the order they went in.

**Bottleneck:** all N enqueuers fight over a single `tail` pointer. One CAS winner
per round; the rest spin. At 16+ threads, throughput plateaus and then falls.

---

### 2 · RCQB — Relaxed Concurrent Queue (Blocking)
**Source:** Kappes & Anastasiadis, ACM TOPC 2022, §5, Listings 1–3
**File:** `src/queue/RCQBQueue.java`

A faithful Java adaptation of the RCQB algorithm from the Kappes paper.

**Core structural difference from MSQ:** instead of a linked list with dynamic
node allocation, RCQB uses a **fixed-size circular array of pre-allocated slots**.
Each slot is a four-state machine:

```
FREE  →  ENQPEND  →  OCCUPIED  →  DEQPEND  →  FREE
```

**Enqueue — stage 1 (assign):** `tail.getAndIncrement() & mask` is a fetch-and-add
(FAA) that gives each enqueuer a unique slot index. This step is contention-free —
no CAS, no failure possible.

**Enqueue — stage 2 (update):** CAS `FREE → ENQPEND` to claim the slot, write the
data, then set `OCCUPIED`. If two enqueuers land on the same slot (possible when the
array wraps), only one wins; the other spins on the current slot state.

**Dequeue:** claims a slot index via CAS on `head`; waits for the slot to reach
`OCCUPIED` (spin up to MAX_SPINS, then `synchronized wait/notifyAll`); CAS
`OCCUPIED → DEQPEND`; reads data; sets `FREE`.

**Ordering:** relaxed FIFO. Items can be dequeued out of insertion order by up to
`(N−1) × min(ke, kd−1)` positions (Theorem 1 of the paper).

**Bounded buffer:** the array holds at most N items (default 65,536; the paper's
demo value was 256). Enqueuers block when all slots are full — this requires
concurrent dequeuers to be running.

**Java adaptations vs. the paper:**
- `head` uses **CAS instead of FAA** so `dequeue()` can return `null` on an empty
  queue. The paper's RCQB uses FAA and blocks indefinitely (partial-method semantics).
- Sleep/wake uses `synchronized + wait(1ms)/notifyAll` instead of Linux futex.
  Semantics are identical; the implementation primitive differs.
- No separate `waiters` counter because our CAS-on-head guarantees at most one
  dequeuer per slot at any time.

---

### 3 · ERQ — Elastic Relaxed Queue
**Our original contribution**
**Files:** `src/queue/ElasticRelaxedQueue.java`, `src/queue/Lane.java`,
`src/queue/ContentionMonitor.java`

An adaptive queue that does not exist in either paper. It borrows the idea of
spreading operations across parallel sites from Paper 2 and adds a self-tuning
controller that dynamically adjusts how many sites are active.

**Core idea:** maintain an array of K independent MS-queue *lanes*. K starts at 1
(identical behaviour to a plain MS queue, strict FIFO). As CAS failure rate rises,
K expands — operations fan out across more lanes, reducing per-lane contention at
the cost of relaxed ordering. When load drops, K contracts back toward 1.

**Enqueue:** at K=1 lane 0 is the only choice. At K>1 the lane is chosen with
`ThreadLocalRandom`, deliberately *not* a shared counter: a counter incremented on
every operation would itself be a single contended hot-spot — the very MSQ
bottleneck ERQ exists to remove — so lane selection uses no shared state at all. On
a CAS collision the loop simply re-draws another lane. This lane-selection choice was
the single most important performance fix in the project; [`PROGRESS.md`](PROGRESS.md)
tells that story.

**Dequeue:** from a random start lane (K>1), scans up to `scanHighWater` lanes and
returns the first non-null result. `scanHighWater` is the high-watermark of K ever
reached and never decreases — this prevents a correctness bug where K contracts
after an item is written to a now-inactive lane, silently losing that item. The
scan is O(K) in the worst case, but measured mean scan depth stays ~2–3 lanes even
at K=32 (the queue is never near-empty under this workload), so it is not a cost
in practice.

**ContentionMonitor:** thread-local `long[]` arrays accumulate CAS outcomes with
zero shared-memory writes per record. Every 64 ops the local window flushes to a
`LongAdder` (striped cells — far faster than `AtomicLong` under write contention).

**Elasticity controller (inline, no background thread):** each thread counts its own
completed operations in a ThreadLocal; after 4096 of them it samples the global
failure rate and either expands K (rate > 15%) or contracts K (rate < 8%) via a
single CAS on `activeLanes`. Globally that still averages one sample per 4096 ops,
with zero shared-counter traffic on the hot path. Inline checks avoid the OS
scheduling jitter that a background thread would introduce into latency measurements.
On expansion the scan watermark is raised *before* the new lane is activated, so a
dequeuer can never miss an item enqueued to a lane it does not scan yet.
Constants fixed by the July 2026 server tuning sweep (see Results).

---

## Project Structure

```
elastic-relaxed-queue/
├── src/
│   ├── queue/
│   │   ├── ConcurrentQueue.java       # shared interface: enqueue / dequeue / isEmpty
│   │   ├── Node.java                  # linked-list node with AtomicReference<Node> next
│   │   ├── MichaelScottQueue.java     # Paper 1 — strict FIFO, linked list
│   │   ├── RCQBQueue.java             # Paper 2 — relaxed FIFO, circular array + slot states
│   │   ├── ContentionMonitor.java     # CAS failure rate tracker (ThreadLocal + LongAdder)
│   │   ├── Lane.java                  # one MS-queue lane with monitor reporting
│   │   └── ElasticRelaxedQueue.java   # Our contribution — adaptive K-lane queue
│   └── benchmark/
│       ├── BenchmarkResult.java       # result POJO with M ops/sec formatting
│       ├── WorkloadRunner.java        # CountDownLatch barrier worker, ThreadLocalRandom ops
│       ├── BenchmarkMain.java         # 3-way comparison: MSQ vs RCQB vs ERQ
│       └── CorrectnessTest.java       # 31 test cases — all passing
├── flattened_for_server/              # same sources, no packages — for the Linux server
├── compile.sh                         # build + run script for Linux/macOS/server
├── PROGRESS.md                        # short plain-language story of how we got here
└── README.md
```

`flattened_for_server/` is a package-free mirror of `src/` (main class renamed
`MppRunner`) that the university server compiles with a single `javac *.java`; it is
kept byte-for-byte in sync with `src/` apart from those two intentional differences.

---

## How to Build and Run

```bash
# Build (any OS with Java 8+)
mkdir -p bin
find src -name "*.java" | xargs javac -d bin

# 3-way benchmark: MSQ vs RCQB vs ERQ
java -cp bin benchmark.BenchmarkMain

# 31 correctness tests (no items lost or duplicated)
java -cp bin benchmark.CorrectnessTest

# To run on the server
cd flattened_for_server
javac --release=8 -d out *.java
\\ Then move all the compiled .class files to the server
```

> If you get `OutOfMemoryError` at 512–1024 threads, add `-Xss256k` to reduce
> the per-thread stack size.

---

## Correctness Tests — 31 / 31 Passing

```
java -cp bin benchmark.CorrectnessTest
```

| Test group | Queues tested | What it checks |
|---|---|---|
| Single-threaded FIFO | MSQ | Items dequeued in exact insertion order |
| Dequeue-empty | MSQ, RCQB, ERQ | Returns `null`, no hang, no exception |
| No-loss, drain-after, 2/4/8/16 threads | MSQ, ERQ | Zero items lost with N enqueuers only |
| No-loss, concurrent-deq, 2/4/8/16 threads | MSQ, RCQB, ERQ | Zero items lost or duplicated with simultaneous enqueuers + dequeuers |
| Stress, 64 threads, concurrent-deq | MSQ, RCQB, ERQ | Same at high concurrency |

RCQB skips the drain-after tests because it is a bounded ring buffer — filling all
slots with no concurrent dequeuers running causes enqueuers to block forever.

---

## Benchmark Results

**Methodology:** fixed-time — every configuration runs for a 2-second wall-clock
window and we count completed operations; each configuration is run **5 times and
the median is reported**, with a fresh queue per trial. 50 % enqueue / 50 % dequeue.
Each row also shows the min–max across the 5 trials, because the spread matters as
much as the median (RCQB's two regimes hide inside a single median). Reproduce with
`java -cp bin benchmark.BenchmarkMain`, or `java -cp out MppRunner` on the server.

Two machines are reported: the **Linux server** (the primary result, run in the
cloud, 12 thread counts) and a **Windows desktop** (Ryzen 7 7800X3D, confirming the
same shape at higher clocks). Numbers are M ops/sec.

### Primary result — Linux server (median of 5)

| Threads | MSQ | RCQB (med) | RCQB min–max | ERQ | ERQ min–max | ERQ K (max→final) | ERQ scan |
|--------:|----:|-----------:|:-------------|----:|:------------|:------------------|---------:|
| 1   | **43.9** | 32.1 | 31.9–32.3 | 29.8 | 26.7–30.5 | 1 → 1  | 1.00 |
| 2   | 9.0  | **12.0** | 11.7–13.7 | 8.2  | 7.1–8.4   | 2 → 1  | 1.50 |
| 4   | 5.9  | 9.3  | 8.7–15.8  | **10.0** | 8.8–10.4 | 4 → 3  | 1.45 |
| 8   | 3.0  | **11.1** | 10.8–11.4 | 10.0 | 9.5–11.0  | 9 → 7  | 1.58 |
| 16  | 2.3  | 11.7 | 4.1–15.8  | **16.9** | 14.3–17.5 | 18 → 13 | 2.28 |
| 24  | 2.4  | 5.2  | 3.6–16.0  | **17.7** | 16.7–19.6 | 23 → 21 | 1.93 |
| 32  | 2.2  | 13.6 | 11.7–17.5 | **21.3** | 16.0–27.2 | 30 → 26 | 1.87 |
| 48  | 1.9  | 20.2 | 4.4–20.5  | **37.9** | 32.5–41.4 | 45 → 42 | 2.23 |
| 64  | 1.4  | 15.0 | 13.7–15.5 | **25.2** | 22.4–26.1 | 55 → 53 | 2.53 |
| 80  | 1.7  | 14.4 | 2.8–21.2  | **26.0** | 20.0–35.0 | 64 → 63 | 2.75 |
| 96  | 2.0  | 17.2 | 3.5–19.2  | **39.0** | 27.9–41.2 | 64 → 62 | 1.93 |
| 128 | 2.0  | 18.0 | 4.5–18.1  | **29.3** | 26.1–37.7 | 64 → 62 | 1.83 |

### Confirmation — Windows desktop, Ryzen 7 7800X3D (median of 5)

| Threads | MSQ | RCQB (med) | RCQB min–max | ERQ | ERQ min–max |
|--------:|----:|-----------:|:-------------|----:|:------------|
| 1   | **82.9** | 95.9 → see note | 95.4–96.0 | 55.6 | 55.0–56.3 |
| 8   | 8.4 | 18.9 | 16.3–36.5 | **36.9** | 33.5–41.0 |
| 16  | 5.9 | 48.5 | 14.3–61.3 | **61.7** | 58.5–64.9 |
| 32  | 5.8 | 60.5 | 31.1–61.1 | **65.2** | 56.2–70.2 |
| 64  | 5.9 | 33.2 | 14.9–60.3 | **68.1** | 66.9–70.4 |
| 128 | 5.9 | 60.9 | 14.2–62.4 | **73.2** | 69.8–75.6 |

*Note: at 1 thread there is no contention, so RCQB's cheap array slots beat both
linked-list queues; this reverses the moment a second thread appears.*

### What the numbers show

- **MSQ is the textbook collapse.** Fast alone (44–83 M), then it falls off a cliff:
  on the server the single largest drop is 1 → 2 threads (43.9 → 9.0), and from
  ~8 threads it is flat at ~2 M no matter how much hardware is added. All threads
  fight over one `tail` CAS, so total throughput equals one CAS round-trip.

- **ERQ is the only queue that both scales and stays predictable.** It beats MSQ
  from 4 threads and beats RCQB's median at every thread count from 16 up, on both
  machines. The stability is the real story: RCQB's slow trials collapse to ~3–5 M
  (its min column), while **ERQ's worst trial never drops below ~14 M** on the
  server and ~33 M on the desktop. Higher median *and* a much higher floor.

- **ERQ's controller works as a contention sensor.** K stays at 1 while single-
  threaded (strict FIFO, `scan = 1.00`) and grows with the thread count, saturating
  the `MAX_LANES = 64` cap at 64+ threads — at that point ERQ would take even more
  lanes if allowed, a natural knob for future tuning.

- **`Avg Scan Depth` stays 1.5–2.8** even when K is in the 30s–60s, proving ERQ's
  up-to-K dequeue scan does **not** degrade to O(K): lanes hold items, so a dequeue
  finds one within ~2–3 probes.

### RCQB bimodality — mechanism proven by `headCASfail`

RCQB alternates run-to-run between a fast and a slow regime on **both** machines, so
it is not a platform artifact. The `headCASfail` counter (failed CAS attempts on the
shared `head` index) identifies the cause, and it is **not** sleeping — sleep-ms is
near zero in the slow trials:

| Regime | Server throughput | Failed head-CAS / trial |
|:--|--:|--:|
| slow | ~3–5 M   | ~16–36 M |
| fast | ~15–21 M | ~1–17 M  |

Every slow trial carries several times the head-CAS failures of a fast trial, and
the slow-regime throughput sits at MSQ's single-contended-CAS ceiling. So RCQB's slow
regime *is* MSQ's bottleneck, surfacing through the `head` CAS whenever early
scheduling lets dequeuers pile onto it. This is exactly the oversubscription
sensitivity Kappes & Anastasiadis flag for blocking designs (§3, §7.2). Note that the
`head` CAS is *our* adaptation — the paper assigns indices with an uncontended
fetch-and-add and blocks instead of returning `null` — so part of RCQB's variance
here is a cost of making `dequeue()` return `null`, not of the paper's algorithm.

### How the tuning constants were chosen

`HIGH_THRESHOLD`, `LOW_THRESHOLD`, and `CHECK_INTERVAL` were fixed by a 5-run sweep:

| Config (HIGH/LOW/INTERVAL) | K behaviour |
|:--|:--|
| 0.30 / 0.05 / 1024 | under-expands; K grows too slowly to relieve contention |
| 0.20 / 0.07 / 256  | K oscillates — 256-op windows are noise-dominated |
| 0.20 / 0.08 / 4096 | smooth |
| **0.15 / 0.08 / 4096** | smooth, K tracks thread count — **chosen default** |

### Limitations and caveats

- **Null dequeues count as ops.** When the queue is momentarily empty a dequeue
  returns `null` after a couple of reads and no CAS, yet still counts as a completed
  operation. Near-empty phases therefore inflate raw throughput for all three
  queues equally. A producer/consumer split or a separate successful-op counter
  would make the absolute numbers watertight; the *relative* comparison is unaffected.
- **RCQB's slow regime is partly our `head`-CAS adaptation**, as noted above.
- **ERQ pays a small fixed tax** (lane indirection + contention monitoring) that
  only pays off past ~4 threads; below that a plain MS queue is faster.

---

## Academic References

1. M. M. Michael and M. L. Scott. *Simple, Fast, and Practical Non-Blocking and
   Blocking Concurrent Queue Algorithms.* Proceedings of the 15th ACM Symposium on
   Principles of Distributed Computing (PODC), 1996, pp. 267–275.

2. G. Kappes and S. V. Anastasiadis. *A Family of Relaxed Concurrent Queues for
   Low-Latency Operations and Item Transfers.* ACM Transactions on Parallel Computing,
   Vol. 9, No. 4, Article 16, December 2022.
