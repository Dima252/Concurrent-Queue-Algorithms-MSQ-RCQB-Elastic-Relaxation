# How This Project Reached Its Final State

A short, plain account of the steps we took to get the benchmark working and to make
our own queue (ERQ) actually win. Read this alongside the results in the README.

## The goal

Compare three concurrent queues — the classic **MSQ** (1996), the relaxed **RCQB**
(2022), and our own adaptive **ERQ** — and show that ERQ scales better under heavy
multi-threaded load. Getting a trustworthy measurement, and then getting ERQ to live
up to the idea, took several rounds of fixing.

## Step 1 — Fix the benchmark before trusting any numbers

Our first benchmark gave every thread a fixed amount of work (e.g. 100k operations
each). That measured the wrong thing: most of the time went into JIT warm-up, and one
slow thread could stretch the total time and drag the score down.

**Change:** switch to a **fixed-time** design. Every thread runs for a fixed 2-second
window; we count how many operations completed. We also run each setting **5 times and
take the median**, with a fresh queue each time. Now one slow thread just contributes
fewer ops instead of distorting the clock.

## Step 2 — Give RCQB a fair chance

RCQB stores items in a fixed circular array. Our array was too small (4096 slots), so
during a 2-second run it kept filling up, which forced enqueuers to sleep and wrecked
RCQB's score for reasons that had nothing to do with the algorithm.

**Change:** enlarge the ring to 65,536 slots so normal runs don't hit the ceiling.

## Step 3 — Tune ERQ's controller

ERQ adjusts **K**, the number of parallel "lanes" it uses, based on how often threads
collide. Two knobs control this: how much collision triggers adding a lane, and how
often it checks. We swept five settings on the server and found:

- Too cautious a threshold → K grows too slowly and never relieves the pressure.
- Too short a check interval → K jumps around randomly because each sample is noisy.
- The sweet spot (`0.15 / 0.08 / 4096`) makes K grow smoothly with the thread count.

## Step 4 — Find out why ERQ was *still* losing

Even after tuning, ERQ trailed the others. It turned out our own code had recreated the
exact problem ERQ was designed to avoid.

To pick which lane to use, enqueue and dequeue both did a **shared counter increment on
every single operation**. That shared counter became a traffic jam — the same
one-hot-spot bottleneck that cripples MSQ — just moved up one level. So no matter how
many lanes ERQ opened, every operation still funnelled through one contended counter.

**Change:** pick the lane with a **per-thread random choice** instead of a shared
counter. No shared state, no traffic jam. This roughly **doubled** ERQ's throughput
from 16 threads up and is what finally made it the fastest queue in the comparison.

## Step 5 — Prove the explanations instead of guessing

We were telling stories about *why* the numbers looked the way they did. We replaced
the stories with two measurements built into the benchmark:

- **`headCASfail`** (for RCQB): counts failed attempts on its shared `head` pointer.
  This proved that RCQB's wildly inconsistent speed (fast some runs, slow others) comes
  from contention on that one pointer — the slow runs show far more failures. It is a
  real effect, and it appears on both Windows and the Linux server.
- **`Avg Scan Depth`** (for ERQ): checks a worry that ERQ's dequeue, which may look at
  several lanes, could get slow when there are many lanes. Measured at ~2–3 lanes even
  with dozens open — so it is not a problem in practice.

## The result

ERQ ends up **faster than both baselines from 4 threads up, and far more stable**: when
RCQB has a bad run it collapses to a few million ops/sec, while ERQ's worst run stays
high. The full tables and analysis are in the README.

