package queue;

import java.util.concurrent.atomic.LongAdder;

/**
 * Tracks CAS success/failure rates across threads with minimal overhead.
 *
 * Hot path: thread-local array accumulation — zero shared-memory writes per record.
 * Sampling path: LongAdder.sum() — striped cells, no single bottleneck.
 *
 * Thread-local counters are flushed to the global LongAdders every FLUSH_INTERVAL
 * operations. This means getFailureRate() can lag by up to FLUSH_INTERVAL events
 * per thread, which is acceptable for the approximate elasticity control loop.
 */
public final class ContentionMonitor {

    private static final int FLUSH_INTERVAL = 64;

    // Index 0 = successes, index 1 = failures (thread-local pending flush)
    private final ThreadLocal<long[]> localWindow =
            ThreadLocal.withInitial(() -> new long[2]);

    // Counts operations since last flush for this thread
    private final ThreadLocal<int[]> opsSinceFlush =
            ThreadLocal.withInitial(() -> new int[1]);

    // Global aggregates — LongAdder outperforms AtomicLong under high write contention
    private final LongAdder globalSuccesses = new LongAdder();
    private final LongAdder globalFailures  = new LongAdder();

    public void recordSuccess() { record(0); }
    public void recordFailure()  { record(1); }

    private void record(int idx) {
        long[] w   = localWindow.get();
        int[]  cnt = opsSinceFlush.get();
        w[idx]++;
        if (++cnt[0] >= FLUSH_INTERVAL) {
            globalSuccesses.add(w[0]);
            globalFailures.add(w[1]);
            w[0] = w[1] = 0;
            cnt[0] = 0;
        }
    }

    /**
     * Returns the approximate CAS failure rate in [0.0, 1.0].
     * Safe to call from any thread at any time.
     */
    public double getFailureRate() {
        long f     = globalFailures.sum();
        long s     = globalSuccesses.sum();
        long total = f + s;
        return (total == 0) ? 0.0 : (double) f / total;
    }

    /**
     * Resets global counters to start a fresh sampling window.
     * Thread-local unflushed data is intentionally left intact — it will be
     * included in the next window, which is fine for approximate monitoring.
     */
    public void reset() {
        globalSuccesses.reset();
        globalFailures.reset();
    }
}
