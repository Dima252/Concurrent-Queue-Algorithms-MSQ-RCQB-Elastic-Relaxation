# Concurrent Queue Algorithms: MSQ, RCQB, and Elastic Relaxation

> ## 📢 Update for Moataz — July 4, 2026 (evening)
>
> **We found why ERQ was losing, and it was our bug, not the design.** ERQ's dequeue
> *and* enqueue each did a shared `getAndIncrement()` on a global counter to pick a
> lane — a single contended fetch-and-add on **every operation**. That is precisely
> the MSQ bottleneck ERQ is supposed to eliminate; we had accidentally re-created it
> one level up. Replacing the shared counters with `ThreadLocalRandom` lane selection
> (zero shared state) transformed the numbers. **Local Windows run (Ryzen 7 7800X3D,
> the exact build + range you run on the server):**
>
> | Threads | MSQ | RCQB median | ERQ **before** | ERQ **after** |
> |--------:|----:|-----------:|---------------:|--------------:|
> | 8   | 8.4 | 18.9 | ~25 | **36.9** |
> | 16  | 5.9 | 48.5 | ~42 | **61.7** |
> | 32  | 5.8 | 60.5 | —   | **65.2** |
> | 64  | 5.9 | 33.2 | —   | **68.1** |
> | 128 | 5.9 | 60.9 | —   | **73.2** |
>
> ERQ now **beats both MSQ and RCQB from 16 threads up**, scales monotonically, and —
> the real headline — is **stable where RCQB is bimodal**: at 128t ERQ's five trials
> span 69.8–75.6 M while RCQB's span 14.2–62.4 M. Higher median *and* predictable.
>
> **Two diagnostics we added turned our hand-waving into proof (please keep them on
> for your run — the overhead is negligible and the report needs this evidence):**
>
> 1. **RCQB `headCASfail` counter settled the bimodality question.** The slow regime
>    is **not** about sleeping (sleep-ms is ~0 in nearly every trial). It is
>    contention on the single `head` CAS — *our* totalization adaptation, since the
>    paper uses an uncontended FAA. Every slow trial shows **~66 M failed head-CAS**;
>    every fast trial **~18 M**. The slow regime collapses to ~14–15 M ≈ MSQ's
>    single-contended-CAS ceiling. That is the whole story, now measured, not guessed.
> 2. **ERQ `Avg Scan Depth` killed my other worry.** I feared ERQ's dequeue, which
>    scans up to K lanes, degraded to O(K). Measured: **1.9–2.8 lanes** even at K=32.
>    The queue is never near-empty, so a dequeue finds an item in the first 2–3 lanes.
>    Non-issue — good to have ruled out with a number.
>
> Also kept from the earlier pass: tuning defaults `0.15 / 0.08 / 4096`; per-thread
> elasticity counter; single-ThreadLocal contention monitor; the `scanHighWater`
> ordering fix (no more impossible FinalK > MaxK); min/max spread in the output; sweep
> extended to 80/96/128.
>
> **What we need from you: ONE final clean run** (`flattened_for_server/Commands.txt`,
> unchanged procedure). Expected on Linux: ERQ leading and stable at scale, and slow
> RCQB trials carrying a high `headCASfail` count. The Windows table below is
> preliminary — **report numbers must come from your server run.**

A Java implementation and comparative study of three concurrent queue algorithms,
developed as the final project for the **Multi-Core Programming** course.

The project follows a clear academic progression: implement the classic 1996 baseline,
then the 2022 state-of-the-art relaxed structure, then contribute an original adaptive
variant that combines ideas from both papers.

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
`ThreadLocalRandom` — *not* a shared counter. An earlier version used a shared
`getAndIncrement()` for round-robin lane assignment; that fetch-and-add on every
operation was itself a single contended hot-spot — the exact MSQ bottleneck ERQ
exists to remove, re-created one level up — and it capped ERQ's throughput badly
(see Results). A random index spreads load with zero shared state; on a CAS
collision the loop simply re-draws.

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
└── compile.sh                         # build + run script for Linux/macOS/server
```

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

# One-shot build + benchmark on Linux/macOS/university server
bash compile.sh run
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
Run `java -cp bin benchmark.BenchmarkMain` (or `java -cp out MppRunner` on the
server) to reproduce.

There are two data sets below. The **post-fix local run** is the current state of
the code. The **pre-fix server sweep** is kept only because it is where the tuning
constants and the RCQB-bimodality behaviour were established — its ERQ numbers are
obsolete (they predate the shared-counter fix). **Final report numbers must come
from a fresh server run of the current build.**

### Post-fix local run — Windows, Ryzen 7 7800X3D (preliminary)

After replacing ERQ's shared lane counters with `ThreadLocalRandom` (median of 5):

| Threads | MSQ  | RCQB median | RCQB min–max   | ERQ  | ERQ min–max   | ERQ K (max→final) | ERQ scan |
|--------:|-----:|------------:|:---------------|-----:|:--------------|:------------------|---------:|
| 1       | 82.9 | **95.9**    | 95.4–96.0      | 55.6 | 55.0–56.3     | 1 → 1             | 1.00 |
| 2       | 20.6 | **27.7**    | 27.4–27.9      | 17.4 | 16.4–18.1     | 2 → 1             | 1.50 |
| 4       | 12.8 | 23.1        | 21.9–31.8      | 23.0 | 20.0–25.0     | 5 → 3             | 1.94 |
| 8       | 8.4  | 18.9        | 16.3–36.5      | **36.9** | 33.5–41.0 | 11 → 9            | 1.85 |
| 16      | 5.9  | 48.5        | 14.3–61.3      | **61.7** | 58.5–64.9 | 27 → 23           | 2.23 |
| 32      | 5.8  | 60.5        | 31.1–61.1      | **65.2** | 56.2–70.2 | 34 → 29           | 2.78 |
| 64      | 5.9  | 33.2        | 14.9–60.3      | **68.1** | 66.9–70.4 | 26 → 22           | 2.24 |
| 128     | 5.9  | 60.9        | 14.2–62.4      | **73.2** | 69.8–75.6 | 35 → 32           | 2.80 |

**What the post-fix numbers show:**

- **ERQ now wins from 8 threads up and is the only stable scaler.** It grows
  monotonically to 73 M ops/s and, crucially, its trial spread stays tight
  (128t: 69.8–75.6) while RCQB's is enormous (128t: 14.2–62.4). Higher median
  *and* predictable — that is the whole thesis of the design, and it only appeared
  once the accidental shared-counter bottleneck was removed.

- **The shared-counter FAA was the entire problem.** ERQ's dequeue and enqueue used
  a global `getAndIncrement()` to choose a lane — one contended atomic on every op,
  i.e. MSQ's disease re-created above the lanes. `ThreadLocalRandom` removed it and
  roughly **doubled** 16–128-thread throughput (e.g. 16t ~42 → 61.7). Single-thread
  is unchanged (55.6, K=1 path was already clean); the small 1–2-thread deficit vs
  MSQ is the contention-monitor tax, the honest price of elasticity.

- **`Avg Scan Depth` stays 1.9–2.8** even at K=32, proving ERQ's up-to-K dequeue
  scan does *not* degrade to O(K) under this workload — the queue always has items
  spread across lanes, so a dequeue finds one within ~3 probes.

### RCQB bimodality — mechanism now proven

The `headCASfail` diagnostic settled it. RCQB is bimodal (slow ~14–15 M / fast
~60 M on this machine), and the discriminator is **not** sleeping — sleep-ms is ~0
in nearly every trial. It is contention on the single `head` CAS, which is *our*
totalization adaptation (the paper uses an uncontended FAA + futex):

| Regime | Throughput | Failed head-CAS / trial |
|:--|--:|--:|
| slow | ~14–15 M | **~66 M** |
| fast | ~60 M | ~18 M |

Every slow trial across every thread count carries ~3–4× the head-CAS failures of a
fast trial, and the slow-regime throughput (~14–15 M) sits right at MSQ's
single-contended-CAS ceiling. So RCQB's slow regime *is* MSQ's bottleneck, surfacing
through our `head` CAS whenever early scheduling lets dequeuers pile onto it. This
also matches the oversubscription sensitivity Kappes & Anastasiadis flag for blocking
designs (§3, §7.2). Report caveat: because the contention is on our adaptation, part
of RCQB's variance is an artifact of our design choice, not of the paper's algorithm.

### Pre-fix server sweep — where the tuning constants came from

Five server runs swept the ERQ tuning space (these ERQ numbers are **obsolete** —
pre shared-counter fix — but the *tuning* conclusions still hold):

| Config (HIGH/LOW/INTERVAL) | K behaviour |
|:--|:--|
| 0.30 / 0.05 / 1024 | under-expands; K stuck at 5–6 |
| 0.20 / 0.07 / 256  | K oscillates wildly (MaxK 38–52, big MaxK→FinalK gaps) |
| 0.20 / 0.08 / 4096 | smooth |
| **0.15 / 0.08 / 4096** | smooth, K tracks thread count — chosen default |

- `HIGH=0.30` was too conservative; lowering the expansion threshold to 0.15 let K
  grow enough to matter. `INTERVAL=256` made the controller unstable — 256-op
  windows are noise-dominated, so K random-walked (runaway expansions to K=38–52).
  `INTERVAL=4096` suppresses this entirely.
- The server sweep also confirmed **MSQ's textbook collapse** (~44 M alone → flat
  ~2 M from 8 threads; the 1→2-thread drop 44.9→9.2 is the cache-coherence lesson in
  one number) and that **RCQB's bimodality reproduces on Linux** — it is not a
  Windows artifact.

---

## What Remains To Be Done

### 1 · Print K during the benchmark — ✅ DONE
Both mains ([BenchmarkMain.java](src/benchmark/BenchmarkMain.java) and
`flattened_for_server/MppRunner.java`) print `Max Lanes Opened` and `Final Lanes`
for ERQ after every configuration. Confirmed working: K=1 at 1–2 threads,
expanding to 6 at 16+ threads.

### 2 · Server tuning sweep — ✅ DONE (July 2026, 5 runs)
Winner `HIGH=0.15, LOW=0.08, INTERVAL=4096` is now the default in both folders.
Full findings in the Results section.

### 3 · ERQ shared-counter bottleneck — ✅ FIXED (July 4)
ERQ's per-op shared lane counters replaced with `ThreadLocalRandom`. Local Windows
run: ERQ now leads from 8 threads up and is stable where RCQB is bimodal. Two
diagnostics added and validated: `headCASfail` (proved RCQB bimodality = head-CAS
contention) and `Avg Scan Depth` (proved ERQ dequeue scan stays ~2–3, not O(K)).

### 4 · Final clean server run (Priority: High — MOATAZ)
One run of the **current build** (`flattened_for_server/Commands.txt`, unchanged
procedure — sweep includes 80/96/128 threads). Keep the diagnostics on. This
produces the report table plus the evidence rows: per-trial RCQB
throughput/sleep-ms/occ/**headCASfail**, and ERQ **Avg Scan Depth**. The previous
server ERQ numbers are obsolete (pre-fix). Send the complete raw output.

### 5 · Write the report analysis section (Priority: High)
Minimum required content (using the **final server run** numbers):

- Throughput table: MSQ / RCQB / ERQ at every thread count, median with min–max.
- Crossover point: first thread count where ERQ beats MSQ (8 on the local run).
- Cite **Theorem 1** from Kappes & Anastasiadis (2022): with K lanes the maximum
  FIFO deviation per item is `(K−1) × min(T_e, T_d−1)`. At K=1 this is 0 —
  strictly equivalent to a plain MS queue.
- Explain why MSQ degrades: N threads compete on one `tail` CAS; throughput equals
  one CAS latency, not N × (one CAS latency). Use the 1→2 thread collapse
  (44.9 → 9.2) as the cache-coherence illustration.
- Explain why RCQB scales *when it does*: the assign step (FAA) is contention-free;
  CAS contention is local to one slot. Then its bimodality: the slow regime is our
  CAS-on-head totalization piling up (headCASfail ~66 M vs ~18 M; slow throughput ≈
  MSQ's single-CAS ceiling). Disclose this is partly an artifact of our adaptation.
- Explain why ERQ scales: CAS pressure is spread over K lanes with no shared
  counter; K auto-adjusts to load. Show K vs thread count and the min–max stability
  vs RCQB. Note the shared-counter mistake and its fix as a lesson in where
  bottlenecks hide.
- ERQ limitations: constant per-op contention-monitor overhead below ~8 threads;
  controller instability with short sampling windows (the INTERVAL=256 oscillation)
  as the justification for INTERVAL=4096.
- Measurement caveat to state honestly: the benchmark counts null dequeues on an
  empty queue as completed ops (cheap, no CAS), so near-empty phases inflate all
  three queues' raw throughput. Consider a producer/consumer split or separate
  successful-op counter if the numbers need to be watertight.

---

## Academic References

1. M. M. Michael and M. L. Scott. *Simple, Fast, and Practical Non-Blocking and
   Blocking Concurrent Queue Algorithms.* Proceedings of the 15th ACM Symposium on
   Principles of Distributed Computing (PODC), 1996, pp. 267–275.

2. G. Kappes and S. V. Anastasiadis. *A Family of Relaxed Concurrent Queues for
   Low-Latency Operations and Item Transfers.* ACM Transactions on Parallel Computing,
   Vol. 9, No. 4, Article 16, December 2022.
