

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
    private static final int[] THREAD_COUNTS  = {1, 2, 4, 8, 16, 24, 32, 48, 64, 80, 96, 128};
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
            report(runTrials("MSQ ", MichaelScottQueue<Integer>::new, n));

            // RCQB is bimodal, so print each trial individually: throughput /
            // sleep-ms / mean occupancy / failed head-CAS count. The head-CAS
            // column is the one that separates the fast and slow regimes.
            Trial[] rcqbTrials = runTrials("RCQB", RCQBQueue<Integer>::new, n);
            report(rcqbTrials);
            StringBuilder diag = new StringBuilder(
                    "      +-- [RCQB] per-trial Mops/sleep-ms/avg-occ/headCASfail (sorted): ");
            for (Trial t : rcqbTrials) {
                diag.append(String.format("%.1f/%,d/%,d/%,d  ",
                        t.result.throughputMops,
                        ((RCQBQueue<Integer>) t.queue).getDequeueSleepWaits(),
                        t.avgOccupancy,
                        ((RCQBQueue<Integer>) t.queue).getHeadCasFails()));
            }
            System.out.println(diag);

            Trial erqTrial = report(runTrials("ERQ ", ElasticRelaxedQueue<Integer>::new, n));
            ElasticRelaxedQueue<Integer> erq = (ElasticRelaxedQueue<Integer>) erqTrial.queue;
            System.out.printf("      +-- [ERQ] Max Lanes Opened: %d | Final Lanes: %d | Avg Scan Depth: %.2f%n",
                erq.getMaxLanesReached(),
                erq.getActiveLanes(),
                erq.getAvgScanDepth());

            System.out.println();
        }
    }

    /**
     * Runs TRIALS independent measurements (fresh queue each, so no items or
     * adapted state carry over) and returns them sorted by throughput.
     */
    private static Trial[] runTrials(String label,
                                     Supplier<ConcurrentQueue<Integer>> newQueue,
                                     int threadCount) throws Exception {
        Trial[] trials = new Trial[TRIALS];
        for (int t = 0; t < TRIALS; t++) {
            trials[t] = runBenchmark(label, newQueue.get(), threadCount);
        }
        Arrays.sort(trials, Comparator.comparingDouble(t -> t.result.throughputMops));
        return trials;
    }

    /**
     * Prints the median trial plus the min–max spread across all trials and
     * returns the median trial. The spread matters as much as the median:
     * RCQB's bimodal regimes hide inside a single median number.
     */
    private static Trial report(Trial[] trials) {
        Trial median = trials[trials.length / 2];
        String line = median.result.toString();
        if (trials.length > 1) {
            line += String.format("   [min %8.3f / max %8.3f]",
                    trials[0].result.throughputMops,
                    trials[trials.length - 1].result.throughputMops);
        }
        System.out.println(line);
        return median;
    }

    /**
     * One measurement outcome plus the queue it ran on (kept for ERQ lane and
     * RCQB sleep stats) and the mean queue occupancy sampled during the run
     * (RCQB only; 0 for the unbounded queues, which are not sampled).
     */
    private static final class Trial {
        final BenchmarkResult result;
        final ConcurrentQueue<Integer> queue;
        final long avgOccupancy;
        Trial(BenchmarkResult result, ConcurrentQueue<Integer> queue, long avgOccupancy) {
            this.result       = result;
            this.queue        = queue;
            this.avgOccupancy = avgOccupancy;
        }
    }

    /**
     * Runs a single benchmark: warm up → start all threads simultaneously →
     * let them work for RUN_MILLIS → raise the stop flag → return a Trial
     * built from the ops completed within the window.
     */
    private static Trial runBenchmark(String label,
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

        // The timekeeper thread doubles as an occupancy sampler for RCQB
        // (~10 ms cadence — zero cost on the workers' hot path), recording the
        // mean number of items in the ring during the run.
        long occSum = 0, occSamples = 0;
        if (queue instanceof RCQBQueue) {
            RCQBQueue<Integer> rcqb = (RCQBQueue<Integer>) queue;
            long deadlineNs = startNs + RUN_MILLIS * 1_000_000L;
            while (System.nanoTime() < deadlineNs) {
                Thread.sleep(10);
                occSum += rcqb.occupancy();
                occSamples++;
            }
        } else {
            Thread.sleep(RUN_MILLIS);
        }
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

        return new Trial(
                new BenchmarkResult(label, threadCount, completedOps.sum(), elapsedNs),
                queue,
                occSamples == 0 ? 0 : occSum / occSamples);
    }
}
