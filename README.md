# Concurrent Queue Algorithms: MSQ, RCQB, and Elastic Relaxation

> ## 📢 Update for Moataz — July 3, 2026
>
> **What changed (all changes applied to both `src/` and `flattened_for_server/`):**
> 1. **Benchmark redesigned: fixed-work → fixed-time.** Threads no longer run a fixed
>    100k ops each; they run for a fixed 2-second window (`RUN_MILLIS`) and we count
>    completed ops. Each configuration now runs **5 trials and reports the median**.
>    Reason: the old design mostly measured JIT warmup and let one straggler thread
>    stretch the clock.
> 2. **RCQB ring enlarged 4096 → 65,536 slots.** Under a 2-second 50/50 workload the
>    old ring filled up, parking enqueuers in 1 ms sleeps and destroying RCQB's numbers.
> 3. **Your lane-printing (task 1) and tuning constants (0.25 / 2048) are now also in
>    `src/`** — the two folders had drifted apart; they're back in sync.
> 4. `bin/` and `out/` build outputs are now gitignored (compiled `.class` files were
>    tracked in git; after pulling, rebuild with `bash compile.sh`).
>
> **What we need from you: re-run on the university server** (same as before —
> `flattened_for_server/Commands.txt`) and send back the full output. The results
> table below is preliminary (Windows laptop); the report numbers should come from
> the server. Watch RCQB specifically: on Windows it alternates between a fast and
> a slow regime run-to-run — we want to see if Linux smooths that out.

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

An adaptive queue that does not exist in either paper. It borrows the FAA-based
round-robin assignment idea from Paper 2 and adds a self-tuning controller that
dynamically adjusts how many parallel operation sites are active.

**Core idea:** maintain an array of K independent MS-queue *lanes*. K starts at 1
(identical behaviour to a plain MS queue, strict FIFO). As CAS failure rate rises,
K expands — operations fan out across more lanes, reducing per-lane contention at
the cost of relaxed ordering. When load drops, K contracts back toward 1.

**Enqueue:** FAA on `enqCounter` gives a lane index (`counter % K`). If the CAS on
that lane's tail fails, `enqCounter` was already incremented — the next attempt lands
on a different lane automatically, spreading retries without extra logic.

**Dequeue:** scans up to `scanHighWater` lanes in round-robin and returns the first
non-null result. `scanHighWater` is the high-watermark of K ever reached and never
decreases — this prevents a correctness bug where K contracts after an item is
written to a now-inactive lane, silently losing that item.

**ContentionMonitor:** thread-local `long[]` arrays accumulate CAS outcomes with
zero shared-memory writes per record. Every 64 ops the local window flushes to a
`LongAdder` (striped cells — far faster than `AtomicLong` under write contention).

**Elasticity controller (inline, no background thread):** every 1024 completed
operations one thread samples the global failure rate and either expands K
(rate > 30%) or contracts K (rate < 5%) via a single CAS on `activeLanes`. Inline
checks avoid the OS scheduling jitter that a background thread would introduce into
latency measurements.

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

## Benchmark Results (preliminary — awaiting server run)

**Methodology (new):** fixed-time — every configuration runs for a 2-second
wall-clock window and we count completed operations; each configuration is run
**5 times and the median is reported**, with a fresh queue per trial.
50 % enqueue / 50 % dequeue. Run `java -cp bin benchmark.BenchmarkMain` to reproduce.

**Platform:** Windows 11 · AMD Ryzen 7 7800X3D (8 physical cores / 16 logical threads)
· OpenJDK 1.8.0_482 (Eclipse Temurin). **Final report numbers must come from the
university server — do not cite this table in the report.**

| Threads | MSQ (M ops/s) | RCQB (M ops/s) | ERQ (M ops/s) | ERQ max→final K |
|--------:|--------------:|---------------:|--------------:|:---------------|
| 1       | 83.3          | **85.2**       | 45.6          | 1 → 1          |
| 2       | 21.0          | **28.1**       | 14.7          | 1 → 1          |
| 4       | 13.2          | **31.0**       | 12.9          | 2 → 1          |
| 8       | 8.5           | **26.3**       | 21.0          | 3 → 2          |
| 16      | 6.0           | 16.4           | **31.5**      | 6 → 5          |
| 32      | 5.9           | **66.5**       | 32.7          | 6 → 6          |
| 64      | 5.9           | 15.2           | **29.7**      | 6 → 5          |
| 128     | 5.9           | **67.3**       | 26.1          | —              |
| 256     | 5.9           | **50.6**       | 29.2          | —              |
| 512     | 5.9           | **68.1**       | 15.3          | —              |
| 1024    | 5.8           | **52.5**       | 18.8          | —              |

**What the numbers show so far:**

- **MSQ is the textbook baseline:** dead-flat ~5.9 M ops/s from 16 to 1024 threads.
  All threads compete on one `tail` CAS, so total throughput equals one CAS
  round-trip's worth of work regardless of thread count.

- **ERQ is the stable scaler:** 26–33 M ops/s (≈5× MSQ) from 16 through 256 threads,
  with the elasticity controller expanding K from 1 to 6 exactly when contention
  rises — confirming the adaptive mechanism fires. Degrades gracefully at extreme
  oversubscription (still 3× MSQ at 1024 threads). Reproducible run-to-run.

- **RCQB is fast but erratic on Windows:** it alternates between a ~15–30 M and a
  ~66 M regime with no pattern by thread count, even with median-of-5. Working
  hypothesis: whole runs fall into (or avoid) the 1 ms sleep/wake path of its
  blocking design depending on early queue-occupancy drift — the oversubscription
  sensitivity the Kappes paper warns about for blocking algorithms (§3, §7.2).
  The server run should confirm or rule this out.

---

## What Remains To Be Done

### 1 · Print K during the benchmark — ✅ DONE
Both mains ([BenchmarkMain.java](src/benchmark/BenchmarkMain.java) and
`flattened_for_server/MppRunner.java`) print `Max Lanes Opened` and `Final Lanes`
for ERQ after every configuration. Confirmed working: K=1 at 1–2 threads,
expanding to 6 at 16+ threads.

### 2 · Run the new benchmark on the university server (Priority: High — MOATAZ)
Same procedure as before (`flattened_for_server/Commands.txt`), now with the
fixed-time median-of-5 methodology. Bring back the full output. Current tuning
constants (`HIGH_THRESHOLD=0.25`, `CHECK_INTERVAL=2048`) are in both folders;
earlier server attempt with 0.20/512 performed badly. If tuning further, consider
`MAX_LANES` = physical core count of the server.

### 3 · Write the report analysis section (Priority: High)
Minimum required content (using **server** numbers):

- Throughput table: MSQ / RCQB / ERQ at every thread count from the benchmark.
- Crossover point: the first thread count where ERQ beats MSQ.
- Cite **Theorem 1** from Kappes & Anastasiadis (2022): with K lanes the maximum
  FIFO deviation per item is `(K−1) × min(T_e, T_d−1)`. At K=1 this is 0 —
  strictly equivalent to a plain MS queue.
- Explain why MSQ degrades: N threads compete on one `tail` CAS; throughput equals
  one CAS latency, not N × (one CAS latency).
- Explain why RCQB scales: the assign step (FAA) is contention-free; CAS contention
  is local to one slot at a time.
- Explain why ERQ scales: CAS pressure is spread over K lanes; K auto-adjusts so
  the queue pays for exactly as much parallelism as the current load requires.
- Address RCQB's run-to-run variance under oversubscription (blocking sleep/wake
  vs ERQ's lock-free stability) — see the results section above.

---

## Academic References

1. M. M. Michael and M. L. Scott. *Simple, Fast, and Practical Non-Blocking and
   Blocking Concurrent Queue Algorithms.* Proceedings of the 15th ACM Symposium on
   Principles of Distributed Computing (PODC), 1996, pp. 267–275.

2. G. Kappes and S. V. Anastasiadis. *A Family of Relaxed Concurrent Queues for
   Low-Latency Operations and Item Transfers.* ACM Transactions on Parallel Computing,
   Vol. 9, No. 4, Article 16, December 2022.
