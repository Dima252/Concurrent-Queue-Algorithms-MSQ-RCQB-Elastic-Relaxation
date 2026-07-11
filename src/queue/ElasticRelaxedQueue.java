package queue;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Elastic Relaxed Queue (ERQ).
 *
 * <h2>Core idea</h2>
 * Maintains an array of {@code MAX_LANES} MS-queue lanes, but only the first
 * {@code activeLanes} (= K) are live at any time. Each operation picks one of
 * the live lanes; at K &gt; 1 the choice is made with {@link ThreadLocalRandom}
 * so lane selection adds no shared-memory contention of its own.
 *
 * <ul>
 *   <li><b>K = 1</b>: all operations funnel through a single lane → identical to
 *       a plain MS queue with strict FIFO ordering.</li>
 *   <li><b>K &gt; 1</b>: enqueues and dequeues spread across K lanes, reducing
 *       per-lane CAS contention at the cost of relaxed FIFO order whose expected
 *       deviation grows with K.</li>
 * </ul>
 *
 * <h2>Elasticity</h2>
 * Each thread counts its own completed operations in a ThreadLocal; after
 * {@code CHECK_INTERVAL} of them it samples the global CAS failure rate from
 * the {@link ContentionMonitor}. Globally this still averages one sample per
 * CHECK_INTERVAL operations, but costs zero shared-memory traffic per op —
 * a single global op counter here would itself be a contended FAA on every
 * operation, re-creating the very bottleneck ERQ exists to remove.
 * <ul>
 *   <li>Rate &gt; {@code HIGH_THRESHOLD} → expand K by 1 (up to {@code MAX_LANES}).</li>
 *   <li>Rate &lt; {@code LOW_THRESHOLD}  → contract K by 1 (down to 1).</li>
 * </ul>
 * The adjustment uses a single CAS on {@code activeLanes}, so concurrent
 * samplers cannot stampede K in one step.
 *
 * <h2>Ordering guarantee</h2>
 * Within a single lane ordering is strict FIFO (each lane is a plain MS queue,
 * proven linearizable by Michael &amp; Scott 1996), so reordering can only ever
 * occur ACROSS lanes. Unlike Kappes &amp; Anastasiadis (2022), whose round-robin
 * fetch-and-add assignment yields a deterministic worst-case bound (their
 * Theorem 1), ERQ picks lanes at random and therefore has NO hard worst-case
 * bound — an adversarial scheduler could keep favouring a subset of lanes.
 * Instead the guarantee is probabilistic: under a fair scheduler and balanced
 * load the expected out-of-order (rank) error of an item is O(K), independent of
 * how many items are queued (see Proof.pdf). At K=1 it is exactly 0 — strict FIFO.
 */
public class ElasticRelaxedQueue<T> implements ConcurrentQueue<T> {

    // ── Tuning knobs ──────────────────────────────────────────────────────────
    // Chosen by a tuning sweep. A high expansion threshold (e.g. 0.30) makes K
    // grow too slowly to relieve contention; a short sampling interval (e.g.
    // 256 ops) makes K oscillate because each window is dominated by noise.
    // 0.15 / 0.08 / 4096 gives smooth K growth that tracks the thread count.
    private static final int    MAX_LANES       = 64;
    private static final int    MIN_LANES       = 1;
    private static final double HIGH_THRESHOLD  = 0.15;  // expand when >15% CAS failures
    private static final double LOW_THRESHOLD   = 0.08;  // contract when <8% CAS failures
    private static final int    CHECK_INTERVAL  = 4096;  // per-thread ops between samples

    // ── State ─────────────────────────────────────────────────────────────────
    @SuppressWarnings("unchecked")
    private final Lane<T>[]     lanes         = new Lane[MAX_LANES];
    private final AtomicInteger activeLanes   = new AtomicInteger(MIN_LANES);
    // High-watermark: tracks the highest K ever reached and NEVER decreases.
    // dequeue() and isEmpty() scan up to scanHighWater so that items written
    // to a lane just before K contracted are never abandoned.
    private final AtomicInteger scanHighWater = new AtomicInteger(MIN_LANES);
    private final ContentionMonitor monitor   = new ContentionMonitor();

    // Per-thread counter that triggers the elasticity check. It is thread-local,
    // not a single shared counter, because a shared counter incremented on every
    // operation would itself be a contention hot-spot — the exact cost ERQ sets
    // out to avoid. Globally this still averages one check per CHECK_INTERVAL ops.
    private final ThreadLocal<int[]> opsSinceCheck =
            ThreadLocal.withInitial(() -> new int[1]);

    // Diagnostics for the benchmark output (not used by the algorithm itself):
    // total dequeue calls and total lanes examined across them. Their ratio is
    // the mean scan depth — how many lanes a dequeue inspects before finding an
    // item. It stays near 1 when lanes hold items and would approach K only if
    // the queue ran near-empty, so it shows whether the O(K) scan matters here.
    private final LongAdder deqCalls    = new LongAdder();
    private final LongAdder lanesProbed = new LongAdder();

    public ElasticRelaxedQueue() {
        for (int i = 0; i < MAX_LANES; i++) lanes[i] = new Lane<>();
    }

    // ── Public operations ─────────────────────────────────────────────────────

    @Override
    public void enqueue(T value) {
        while (true) {
            int k = activeLanes.get();
            // At K=1 lane 0 is the only choice. At K>1 pick a random lane: this
            // spreads enqueues across lanes without any shared counter, so lane
            // selection never becomes a contention point of its own. If the
            // chosen lane's CAS loses, the loop simply re-draws another lane.
            int idx = (k == 1) ? 0 : ThreadLocalRandom.current().nextInt(k);
            if (lanes[idx].tryEnqueue(value, monitor)) {
                maybeAdjustK();
                return;
            }
        }
    }

    @Override
    public T dequeue() {
        // Use the high-watermark, not activeLanes, so we never skip a lane
        // that had an item written to it just before K contracted.
        int k     = scanHighWater.get();
        // Random start lane at K>1, same reasoning as enqueue: no shared counter.
        int start = (k == 1) ? 0 : ThreadLocalRandom.current().nextInt(k);
        for (int i = 0; i < k; i++) {
            T val = lanes[(start + i) % k].tryDequeue(monitor);
            if (val != null) {
                deqCalls.increment();
                lanesProbed.add(i + 1);   // lanes examined before finding an item
                maybeAdjustK();
                return val;
            }
        }
        deqCalls.increment();
        lanesProbed.add(k);               // examined all k lanes, all empty
        return null;
    }

    @Override
    public boolean isEmpty() {
        int k = scanHighWater.get();   // same reasoning as dequeue
        for (int i = 0; i < k; i++) {
            if (!lanes[i].isEmpty()) return false;
        }
        return true;
    }

    // ── Elasticity control ────────────────────────────────────────────────────

    private void maybeAdjustK() {
        int[] c = opsSinceCheck.get();
        if (++c[0] < CHECK_INTERVAL) return;
        c[0] = 0;

        double failRate = monitor.getFailureRate();
        monitor.reset();

        int k = activeLanes.get();
        if (failRate > HIGH_THRESHOLD && k < MAX_LANES) {
            int newK = k + 1;
            // Raise the scan watermark BEFORE activating the new lane, so that
            // as soon as an enqueuer can write to lane newK-1, dequeue() and
            // isEmpty() already scan it. The reverse order would leave a brief
            // window in which an item sits in a lane no reader inspects. If the
            // activeLanes CAS below loses, the watermark was raised needlessly
            // and dequeue simply scans one extra empty lane — harmless.
            int w;
            do { w = scanHighWater.get(); }
            while (w < newK && !scanHighWater.compareAndSet(w, newK));
            activeLanes.compareAndSet(k, newK);
        } else if (failRate < LOW_THRESHOLD && k > MIN_LANES) {
            activeLanes.compareAndSet(k, k - 1);
            // scanHighWater intentionally stays put: dequeue() will keep
            // draining the now-inactive lane until it is empty, then the
            // overhead of scanning one extra empty lane is trivial.
        }
    }

    // ── Diagnostics (read by the benchmark) ───────────────────────────────────

    /** Current number of active lanes, K. */
    public int getActiveLanes() { return activeLanes.get(); }

    /** Highest K ever reached during the run (the scan high-watermark). */
    public int getMaxLanesReached() { return scanHighWater.get(); }

    /** Mean lanes examined per dequeue. ~1 means the up-to-K scan costs little. */
    public double getAvgScanDepth() {
        long calls = deqCalls.sum();
        return calls == 0 ? 0.0 : (double) lanesProbed.sum() / calls;
    }
}
