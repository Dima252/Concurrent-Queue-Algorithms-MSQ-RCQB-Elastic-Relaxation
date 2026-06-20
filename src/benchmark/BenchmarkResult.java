package benchmark;

/**
 * Immutable value object holding the outcome of one benchmark run.
 */
public final class BenchmarkResult {
    public final String label;
    public final int    threadCount;
    public final long   totalOps;
    public final long   durationNanos;
    /** Throughput in millions of operations per second. */
    public final double throughputMops;

    public BenchmarkResult(String label, int threadCount,
                           long totalOps, long durationNanos) {
        this.label          = label;
        this.threadCount    = threadCount;
        this.totalOps       = totalOps;
        this.durationNanos  = durationNanos;
        // durationNanos / 1e9 → seconds;  totalOps / seconds / 1e6 → M ops/sec
        this.throughputMops = (durationNanos == 0) ? 0
                : (double) totalOps / durationNanos * 1_000.0;
    }

    @Override
    public String toString() {
        return String.format("%-5s | threads=%4d | ops=%,10d | %8.3f M ops/sec",
                label, threadCount, totalOps, throughputMops);
    }
}
