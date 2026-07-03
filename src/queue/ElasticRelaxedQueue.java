package queue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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
 * After every {@code CHECK_INTERVAL} completed operations, one thread samples
 * the global CAS failure rate from the {@link ContentionMonitor}:
 * <ul>
 *   <li>Rate &gt; {@code HIGH_THRESHOLD} → expand K by 1 (up to {@code MAX_LANES}).</li>
 *   <li>Rate &lt; {@code LOW_THRESHOLD}  → contract K by 1 (down to 1).</li>
 * </ul>
 * The adjustment uses a single CAS on {@code activeLanes}, so at most one thread
 * changes K per sampling window even under high concurrency.
 *
 * <h2>Relaxation bound</h2>
 * Analogous to Kappes & Anastasiadis (2022) Theorem 1: with K lanes and T
 * enqueuer threads, an item can be dequeued at most
 * {@code (K-1) * min(T_e, T_d - 1)} positions out of strict FIFO order.
 * When K=1 this is 0 — strict FIFO.
 */
public class ElasticRelaxedQueue<T> implements ConcurrentQueue<T> {

    // ── Tuning knobs ──────────────────────────────────────────────────────────
    private static final int    MAX_LANES       = 64;
    private static final int    MIN_LANES       = 1;
    private static final double HIGH_THRESHOLD  = 0.25;  // expand when >25% CAS failures
    private static final double LOW_THRESHOLD   = 0.05;  // contract when <5% CAS failures
    private static final long   CHECK_INTERVAL  = 2048;  // ops between elasticity samples

    // ── State ─────────────────────────────────────────────────────────────────
    @SuppressWarnings("unchecked")
    private final Lane<T>[]     lanes         = new Lane[MAX_LANES];
    private final AtomicInteger activeLanes   = new AtomicInteger(MIN_LANES);
    // High-watermark: tracks the highest K ever reached and NEVER decreases.
    // dequeue() and isEmpty() scan up to scanHighWater so that items written
    // to a lane just before K contracted are never abandoned.
    private final AtomicInteger scanHighWater = new AtomicInteger(MIN_LANES);
    private final ContentionMonitor monitor   = new ContentionMonitor();

    // Separate counters prevent a single shared counter from becoming a new bottleneck.
    private final AtomicLong enqCounter = new AtomicLong(0);
    private final AtomicLong deqCounter = new AtomicLong(0);
    private final AtomicLong opCount    = new AtomicLong(0);

    public ElasticRelaxedQueue() {
        for (int i = 0; i < MAX_LANES; i++) lanes[i] = new Lane<>();
    }

    // ── Public operations ─────────────────────────────────────────────────────

    @Override
    public void enqueue(T value) {
        while (true) {
            int k   = activeLanes.get();
            // Round-robin across active lanes; getAndIncrement ensures each
            // concurrent caller gets a distinct starting index.
            int idx = (int)(enqCounter.getAndIncrement() % k);
            if (lanes[idx].tryEnqueue(value, monitor)) {
                maybeAdjustK();
                return;
            }
            // tryEnqueue returned false: a CAS collision was observed.
            // enqCounter was already incremented, so the next loop iteration
            // will pick a different lane — naturally spreading the retry load.
        }
    }

    @Override
    public T dequeue() {
        // Use the high-watermark, not activeLanes, so we never skip a lane
        // that had an item written to it just before K contracted.
        int k     = scanHighWater.get();
        int start = (int)(deqCounter.getAndIncrement() % k);
        for (int i = 0; i < k; i++) {
            T val = lanes[(start + i) % k].tryDequeue(monitor);
            if (val != null) {
                maybeAdjustK();
                return val;
            }
        }
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
        if (opCount.incrementAndGet() % CHECK_INTERVAL != 0) return;

        double failRate = monitor.getFailureRate();
        monitor.reset();

        int k = activeLanes.get();
        if (failRate > HIGH_THRESHOLD && k < MAX_LANES) {
            int newK = k + 1;
            if (activeLanes.compareAndSet(k, newK)) {
                // Raise the scan watermark so dequeue() covers the new lane.
                // CAS loop because two threads could expand simultaneously.
                int w;
                do { w = scanHighWater.get(); }
                while (w < newK && !scanHighWater.compareAndSet(w, newK));
            }
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
}
