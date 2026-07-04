package queue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Faithful Java adaptation of the RCQB (Blocking RCQ) algorithm.
 *
 * Source: Kappes & Anastasiadis (2022), §5, Listings 1–3.
 *
 * ── Core structure (Listing 1) ───────────────────────────────────────────────
 *   struct slot  { state, data, waiters }
 *   struct queue { slots[N], head, tail  }
 *
 * ── Slot state machine ───────────────────────────────────────────────────────
 *   FREE → ENQPEND → OCCUPIED → DEQPEND → FREE
 *
 *   An enqueuer atomically transitions FREE→ENQPEND, writes data, then sets
 *   OCCUPIED (releasing the lock to a dequeuer). A dequeuer atomically
 *   transitions OCCUPIED→DEQPEND, reads data, then sets FREE (releasing the
 *   slot for the next enqueuer).
 *
 * ── Two-stage operations (§5.4.1) ────────────────────────────────────────────
 *   Stage 1 (assign): FAA on tail/head → slot index. Sequential, O(1).
 *   Stage 2 (update): CAS on slot state. Concurrent, retried until success.
 *   This split is the key insight that allows out-of-order completion across
 *   slots while maintaining linearizability (§5.6, Theorem 2).
 *
 * ── Java adaptations ─────────────────────────────────────────────────────────
 *   1. head uses CAS instead of FAA so dequeue() can return null on empty
 *      queue, matching the ConcurrentQueue interface. The paper's RCQB uses
 *      FAA and blocks indefinitely (partial dequeue semantics).
 *   2. waitEnq/wakeDeq use Java synchronized + wait/notifyAll instead of
 *      Linux futex (§Listing 3). Semantics are identical.
 *   3. No explicit waiters counter needed: our CAS-on-head guarantees at most
 *      one dequeuer waits per slot at any time.
 */
public class RCQBQueue<T> implements ConcurrentQueue<T> {

    // ── Slot states (paper §5.2.1) ────────────────────────────────────────────
    private static final int FREE     = 0;
    private static final int ENQPEND  = 1;
    private static final int OCCUPIED = 2;
    private static final int DEQPEND  = 3;

    // ── Constants ─────────────────────────────────────────────────────────────
    // 65,536 slots: under the fixed-time benchmark (seconds of 50/50 random
    // ops) queue occupancy does a random walk whose excursions exceed 4096,
    // and every "full" episode parks enqueuers in 1 ms sleeps, drowning the
    // measurement in blocking noise. 256 was the paper's demo value.
    private static final int DEFAULT_CAPACITY = 65_536;  // must be power of 2
    /** Spin iterations before a dequeuer sleeps on a free slot (paper MAXSPINS). */
    private static final int MAX_SPINS        = 1_000;

    // ── State (Listing 1 fields) ──────────────────────────────────────────────
    private final int           N;
    private final int           mask;   // N - 1, used for fast modulo
    private final Slot[]        slots;
    private final AtomicInteger head;   // dequeue-side counter
    private final AtomicInteger tail;   // enqueue-side counter

    // ── Diagnostics (not part of the paper's algorithm) ──────────────────────
    // Counts wait(1) calls by dequeuers that exhausted MAX_SPINS — i.e. roughly
    // the total milliseconds dequeuers spent asleep. Together with the
    // occupancy sampling in the benchmark this discriminates between the two
    // candidate explanations for the bimodal throughput seen on both Windows
    // and the Linux server: time lost sleeping vs. which occupancy mode
    // (near-empty fast handoff / loaded full-state-machine) the run locked
    // into. LongAdder, touched only on the sleep path — hot path unaffected.
    private final LongAdder deqSleepWaits = new LongAdder();

    // ── Construction ─────────────────────────────────────────────────────────

    public RCQBQueue(int capacity) {
        if (capacity <= 0 || Integer.bitCount(capacity) != 1)
            throw new IllegalArgumentException("capacity must be a positive power of 2");
        this.N     = capacity;
        this.mask  = capacity - 1;
        this.slots = new Slot[N];
        for (int i = 0; i < N; i++) slots[i] = new Slot();
        this.head  = new AtomicInteger(0);
        this.tail  = new AtomicInteger(0);
    }

    public RCQBQueue() { this(DEFAULT_CAPACITY); }

    // ── Enqueue (Listing 2, lines E1–E17 adapted) ────────────────────────────

    @Override
    public void enqueue(T value) {
        // Stage 1 — enq_assign: FAA on tail, mask to slot index (paper line 17)
        int  locTail = tail.getAndIncrement() & mask;
        Slot s       = slots[locTail];

        // Stage 2 — enq_update: spin until slot is FREE, then CAS to claim it
        while (true) {
            int state = s.state.get();
            if (state == FREE) {
                if (s.state.compareAndSet(FREE, ENQPEND)) {   // lock slot
                    s.data = value;                            // write data
                    s.state.set(OCCUPIED);                     // release to dequeuer
                    wakeDeq(s);                                // wake sleeping dequeuer
                    return;
                }
            }
            Thread.yield();    // spinPause — slot still busy, retry
        }
    }

    // ── Dequeue (Listing 2, lines D1–D20 adapted) ────────────────────────────

    @Override
    @SuppressWarnings("unchecked")
    public T dequeue() {
        // Adaptation: use CAS on head instead of FAA so we can return null
        // when the queue is empty (partial→total method totalization, §3).
        while (true) {
            int h = head.get();
            int t = tail.get();
            if (t - h <= 0) return null;              // queue is empty
            if (!head.compareAndSet(h, h + 1)) continue; // lost race, retry

            // Stage 1 — deq_assign succeeded: we own slot locHead
            int  locHead = h & mask;
            Slot s       = slots[locHead];

            // Stage 2 — deq_update: wait for OCCUPIED (paper waitEnq, line 47)
            waitForOccupied(s);

            // CAS OCCUPIED → DEQPEND to lock the slot
            while (!s.state.compareAndSet(OCCUPIED, DEQPEND)) {
                Thread.yield();
            }
            T value = (T) s.data;
            s.data  = null;
            s.state.set(FREE);   // release slot for next enqueuer
            return value;
        }
    }

    @Override
    public boolean isEmpty() {
        return tail.get() - head.get() <= 0;
    }

    // ── waitEnq / wakeDeq (Listing 3) ────────────────────────────────────────

    /**
     * Waits until the slot transitions out of FREE state.
     * Mirrors waitEnq() in Listing 3: spin MAX_SPINS, then sleep on the slot's
     * monitor (Java equivalent of Linux futex wait).
     */
    private void waitForOccupied(Slot s) {
        // Spin phase (paper lines 61–64)
        for (int i = 0; i < MAX_SPINS; i++) {
            if (s.state.get() != FREE) return;
            Thread.yield();
        }
        // Sleep phase (paper lines 65–70) — Java synchronized replaces futex.
        // Increment waiters BEFORE entering the monitor so wakeDeq() sees us.
        s.waiters++;                          // paper line 65: atomicInc(&s→waiters)
        synchronized (s) {
            while (s.state.get() == FREE) {
                deqSleepWaits.increment();    // diagnostic: ~1 ms about to be slept
                try { s.wait(1); } catch (InterruptedException e) { break; }
            }
        }
        s.waiters--;                          // paper line 70: atomicDec(&s→waiters)
    }

    /**
     * Wakes any dequeuer sleeping on this slot.
     * Mirrors wakeDeq() in Listing 3 (paper lines 53–57).
     * The waiters guard avoids a synchronized monitor acquire on every enqueue
     * when no dequeuer is actually sleeping — identical to the paper's
     * "if (s→waiters > 0) wake(...)" check.
     */
    private void wakeDeq(Slot s) {
        if (s.waiters > 0) {
            synchronized (s) { s.notifyAll(); }
        }
    }

    // ── Diagnostics ──────────────────────────────────────────────────────────

    /** Total wait(1) calls by sleeping dequeuers ≈ ms spent asleep. */
    public long getDequeueSleepWaits() { return deqSleepWaits.sum(); }

    /** Current item count (tail may be read slightly stale; clamped to 0). */
    public int occupancy() { return Math.max(0, tail.get() - head.get()); }

    // ── Slot (Listing 1 struct slot) ─────────────────────────────────────────

    private static final class Slot {
        /** State/condition variable; initially FREE. (paper: state: int 32 bits) */
        final AtomicInteger state   = new AtomicInteger(FREE);
        /** Value or pointer. (paper: data: int 64 bits) */
        volatile Object     data;
        /** Count of dequeuers sleeping on this slot. (paper: waiters: uint 32 bits) */
        volatile int        waiters = 0;
    }
}
