

import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * Entry point for the benchmark suite (fixed-time design).
 *
 * Runs MSQ, RCQB, and ERQ at each thread count and prints a side-by-side
 * comparison table. Each measurement runs for a fixed wall-clock window
 * (RUN_MILLIS); throughput = operations completed within the window / window
 * duration. Unlike the earlier fixed-work design, a single straggler thread
 * cannot stretch the measured time — it merely contributes fewer ops.
 *
 * Usage (from this folder after building, see Commands.txt):
 *   java -cp out MppRunner
 *
 * Optional JVM flags for high thread counts:
 *   -Xss256k          reduce per-thread stack to allow more OS threads
 *   -Xmx512m          cap heap if the server is memory-constrained
 */
public final class MppRunner {

    // Thread counts to sweep. Adjust the upper bound to match available cores.
    private static final int[] THREAD_COUNTS  = {1, 2, 4, 8, 16, 24, 32, 48, 64};
    private static final long  RUN_MILLIS     = 2_000;  // measurement window per run
    private static final int   TRIALS         = 5;      // independent runs; median reported
    private static final int   WARMUP_OPS     = 10_000;
    private static final double ENQ_RATIO     = 0.5;   // 50 % enqueue / 50 % dequeue

    public static void main(String[] args) throws Exception {
        System.out.println("=== Elastic Relaxed Concurrent Queue — Benchmark ===");
        System.out.println("runMillis=" + RUN_MILLIS + "  trials=" + TRIALS
                + " (median)  enqRatio=" + ENQ_RATIO + "\n");
        System.out.printf("%-5s | %-8s | %-12s | %s%n",
                "Queue", "Threads", "Total Ops", "Throughput");
        System.out.println("-------------------------------------------------------");

        for (int n : THREAD_COUNTS) {
            System.out.println(runTrials("MSQ ", MichaelScottQueue<Integer>::new,   n).result);
            System.out.println(runTrials("RCQB", RCQBQueue<Integer>::new,           n).result);
            Trial erqTrial = runTrials("ERQ ", ElasticRelaxedQueue<Integer>::new,   n);
            System.out.println(erqTrial.result);

            ElasticRelaxedQueue<Integer> erq = (ElasticRelaxedQueue<Integer>) erqTrial.queue;
            System.out.printf("      +-- [ERQ] Max Lanes Opened: %d | Final Lanes: %d%n",
                erq.getMaxLanesReached(),
                erq.getActiveLanes());

            System.out.println();
        }
    }

    /**
     * Runs TRIALS independent measurements (fresh queue each, so no items or
     * adapted state carry over) and returns the median-throughput trial.
     */
    private static Trial runTrials(String label,
                                   Supplier<ConcurrentQueue<Integer>> newQueue,
                                   int threadCount) throws Exception {
        Trial[] trials = new Trial[TRIALS];
        for (int t = 0; t < TRIALS; t++) {
            ConcurrentQueue<Integer> queue = newQueue.get();
            trials[t] = new Trial(runBenchmark(label, queue, threadCount), queue);
        }
        Arrays.sort(trials, Comparator.comparingDouble(t -> t.result.throughputMops));
        return trials[TRIALS / 2];
    }

    /** One measurement outcome plus the queue it ran on (kept for ERQ lane stats). */
    private static final class Trial {
        final BenchmarkResult result;
        final ConcurrentQueue<Integer> queue;
        Trial(BenchmarkResult result, ConcurrentQueue<Integer> queue) {
            this.result = result;
            this.queue  = queue;
        }
    }

    /**
     * Runs a single benchmark: warm up → start all threads simultaneously →
     * let them work for RUN_MILLIS → raise the stop flag → return a result
     * object built from the ops completed within the window.
     */
    private static BenchmarkResult runBenchmark(String label,
                                                ConcurrentQueue<Integer> queue,
                                                int threadCount) throws Exception {
        // Warmup: interleave enqueue+dequeue so bounded queues (e.g. RCQB)
        // never fill up during warmup.  A pure enqueue-then-drain loop would
        // deadlock RCQB after 256 items because no thread is freeing slots.
        for (int i = 0; i < WARMUP_OPS; i++) {
            queue.enqueue(i);
            queue.dequeue();
        }

        CountDownLatch startGate    = new CountDownLatch(1);
        CountDownLatch doneLatch    = new CountDownLatch(threadCount);
        AtomicBoolean  stop         = new AtomicBoolean(false);
        LongAdder      completedOps = new LongAdder();

        Thread[] workers = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            workers[i] = new Thread(new WorkloadRunner(
                    queue, stop, ENQ_RATIO,
                    startGate, doneLatch, completedOps));
            workers[i].setDaemon(true);
            workers[i].start();
        }

        long startNs = System.nanoTime();
        startGate.countDown();   // release all threads at once
        Thread.sleep(RUN_MILLIS);
        stop.set(true);
        long elapsedNs = System.nanoTime() - startNs;

        // A worker can be stuck inside a blocking enqueue (RCQB with all slots
        // full) after the flag is raised; keep freeing slots until every
        // worker has flushed its count and exited.
        while (!doneLatch.await(10, TimeUnit.MILLISECONDS)) {
            for (int i = 0; i < threadCount; i++) {
                queue.dequeue();
            }
        }

        return new BenchmarkResult(label, threadCount, completedOps.sum(), elapsedNs);
    }
}
