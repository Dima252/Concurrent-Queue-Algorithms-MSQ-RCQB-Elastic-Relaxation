
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Elastic Relaxed Queue (ERQ).
 *
 * <h2>Core idea</h2>
 * Maintains an array of {@code MAX_LANES} MS-queue lanes, but only the first
 * {@code activeLanes} (= K) are live at any time. Threads pick a lane in
 * round-robin order via an atomic counter.
 *
 * <ul>
 *   <li><b>K = 1</b>: all operations funnel through a single lane → identical to
 *       a plain MS queue with strict FIFO ordering.</li>
 *   <li><b>K &gt; 1</b>: enqueues and dequeues spread across K lanes, reducing
 *       per-lane CAS contention at the cost of relaxed (K-bounded) FIFO order.</li>
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
 * <h2>Relaxation bound</h2>
 * Analogous to Kappes & Anastasiadis (2022) Theorem 1: with K lanes and T
 * enqueuer threads, an item can be dequeued at most
 * {@code (K-1) * min(T_e, T_d - 1)} positions out of strict FIFO order.
 * When K=1 this is 0 — strict FIFO.
 */
public class ElasticRelaxedQueue<T> implements ConcurrentQueue<T> {

    // ── Tuning knobs ──────────────────────────────────────────────────────────
    // Values fixed by the 2026-07 server tuning sweep (5 configurations):
    // 0.30/1024 under-expanded (K stuck at 5-6, throughput stalled ~5 M at 64t);
    // INTERVAL=256 made K oscillate wildly (windows too noisy); 0.15/0.08/4096
    // gave smooth K growth tracking thread count and the best 64/128-thread
    // throughput (9.5 / 13.4 M ops/s, median of 5).
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

    // Per-thread op count for elasticity sampling. Replaces a global AtomicLong
    // that was an FAA hot-spot on EVERY operation — the reason ERQ used to run
    // at less than half of MSQ's single-thread speed.
    private final ThreadLocal<int[]> opsSinceCheck =
            ThreadLocal.withInitial(() -> new int[1]);

    // Diagnostics (report evidence, not part of the algorithm): total dequeue
    // calls and total lanes scanned across them, so we can report the mean scan
    // depth per dequeue. If the queue is not near-empty this stays close to 1
    // and the O(K) scan is a non-issue; if it climbs with K it is a real cost.
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
            // K=1 fast path: lane 0 is the only choice. K>1: pick a lane with
            // ThreadLocalRandom, NOT a shared getAndIncrement. The old shared
            // counter was a contended FAA on every enqueue — a fresh single
            // hot-spot that re-created the exact MSQ bottleneck ERQ exists to
            // spread. A random index spreads load just as well with zero shared
            // state, and on a CAS collision the next iteration simply re-draws.
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
        // Random start (K>1), same reasoning as enqueue: no shared counter.
        int start = (k == 1) ? 0 : ThreadLocalRandom.current().nextInt(k);
        for (int i = 0; i < k; i++) {
            T val = lanes[(start + i) % k].tryDequeue(monitor);
            if (val != null) {
                deqCalls.increment();
                lanesProbed.add(i + 1);   // lanes touched before finding an item
                maybeAdjustK();
                return val;
            }
        }
        deqCalls.increment();
        lanesProbed.add(k);               // scanned all k, found nothing
        return null; // all lanes empty
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
            // Raise the scan watermark BEFORE activating the lane. The old
            // order (activate first) opened a window where an enqueuer could
            // write to the new lane while dequeue()/isEmpty() did not scan it
            // yet — items were temporarily invisible, and the diagnostics
            // could even report FinalK > MaxK. If the activeLanes CAS below
            // loses, the watermark was raised for nothing; dequeue merely
            // scans one extra empty lane, which is harmless.
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

    // ── Diagnostics ───────────────────────────────────────────────────────────

    /** Current number of active lanes (K). Useful for logging and assertions. */
    public int    getActiveLanes() { return activeLanes.get(); }

    /** Maximum number of lanes used. Useful for logging and assertions. */
    public int getMaxLanesReached() { return scanHighWater.get(); }

    /** Snapshot of the current CAS failure rate [0.0, 1.0]. */
    public double getFailureRate() { return monitor.getFailureRate(); }

    /** Mean lanes touched per dequeue call. ~1 = O(1); near K = O(K) scan cost. */
    public double getAvgScanDepth() {
        long calls = deqCalls.sum();
        return calls == 0 ? 0.0 : (double) lanesProbed.sum() / calls;
    }
}
