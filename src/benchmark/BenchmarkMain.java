package benchmark;

import queue.ConcurrentQueue;
import queue.ElasticRelaxedQueue;
import queue.MichaelScottQueue;
import queue.RCQBQueue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.LongAdder;

/**
 * Entry point for the benchmark suite.
 *
 * Runs both the Michael-Scott Queue (MSQ) and the Elastic Relaxed Queue (ERQ)
 * at each thread count and prints a side-by-side comparison table.
 *
 * Usage (from project root after building):
 *   java -cp bin benchmark.BenchmarkMain
 *
 * Optional JVM flags for high thread counts:
 *   -Xss256k          reduce per-thread stack to allow more OS threads
 *   -Xmx512m          cap heap if the server is memory-constrained
 */
public final class BenchmarkMain {

    // Thread counts to sweep. Adjust the upper bound to match available cores.
    private static final int[] THREAD_COUNTS  = {1, 2, 4, 8, 16, 32, 64, 128, 256, 512, 1024};
    private static final int   OPS_PER_THREAD = 100_000;
    private static final int   WARMUP_OPS     = 10_000;
    private static final double ENQ_RATIO     = 0.5;   // 50 % enqueue / 50 % dequeue

    public static void main(String[] args) throws Exception {
        System.out.println("=== Elastic Relaxed Concurrent Queue — Benchmark ===");
        System.out.println("ops/thread=" + OPS_PER_THREAD + "  enqRatio=" + ENQ_RATIO + "\n");
        System.out.printf("%-5s | %-8s | %-12s | %s%n",
                "Queue", "Threads", "Total Ops", "Throughput");
        System.out.println("-------------------------------------------------------");

        for (int n : THREAD_COUNTS) {
            System.out.println(runBenchmark("MSQ ",  new MichaelScottQueue<>(),  n));
            System.out.println(runBenchmark("RCQB", new RCQBQueue<>(),            n));
            System.out.println(runBenchmark("ERQ ",  new ElasticRelaxedQueue<>(), n));
            System.out.println();
        }
    }

    /**
     * Runs a single benchmark: warm up → start all threads simultaneously →
     * wait for completion → return a result object.
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
        LongAdder      completedOps = new LongAdder();

        Thread[] workers = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            workers[i] = new Thread(new WorkloadRunner(
                    queue, OPS_PER_THREAD, ENQ_RATIO,
                    startGate, doneLatch, completedOps));
            workers[i].setDaemon(true);
            workers[i].start();
        }

        long startNs = System.nanoTime();
        startGate.countDown();   // release all threads at once
        doneLatch.await();       // wait for all threads to finish
        long elapsedNs = System.nanoTime() - startNs;

        return new BenchmarkResult(label, threadCount, completedOps.sum(), elapsedNs);
    }
}
