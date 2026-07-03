

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * Worker thread for the benchmark (fixed-time design).
 *
 * All threads wait on {@code startGate} so they all begin work simultaneously,
 * eliminating the warm-up skew that would otherwise occur when threads are
 * started sequentially but the clock starts with the first one.
 *
 * Each worker performs operations until the shared {@code stop} flag is set
 * by the timekeeper, counting completed operations in a plain local variable
 * (zero shared-memory traffic during the run) and flushing the total to the
 * shared {@code LongAdder} once at the end.
 *
 * {@code ThreadLocalRandom} is used instead of {@code Math.random()} because
 * {@code Math.random()} shares a single {@code AtomicLong} seed across all
 * threads and itself becomes a contention point under high thread counts.
 */
public final class WorkloadRunner implements Runnable {

    private final ConcurrentQueue<Integer> queue;
    private final AtomicBoolean  stop;
    private final double         enqueueRatio;
    private final CountDownLatch startGate;
    private final CountDownLatch doneLatch;
    private final LongAdder      completedOps;

    public WorkloadRunner(ConcurrentQueue<Integer> queue,
                          AtomicBoolean stop,
                          double enqueueRatio,
                          CountDownLatch startGate,
                          CountDownLatch doneLatch,
                          LongAdder completedOps) {
        this.queue        = queue;
        this.stop         = stop;
        this.enqueueRatio = enqueueRatio;
        this.startGate    = startGate;
        this.doneLatch    = doneLatch;
        this.completedOps = completedOps;
    }

    @Override
    public void run() {
        try {
            startGate.await();  // barrier — all threads released at once
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            doneLatch.countDown();
            return;
        }

        ThreadLocalRandom rng = ThreadLocalRandom.current();
        long localOps = 0;
        for (int i = 0; !stop.get(); i++) {
            if (rng.nextDouble() < enqueueRatio) {
                queue.enqueue(i);
            } else {
                queue.dequeue();
            }
            localOps++;
        }

        completedOps.add(localOps);
        doneLatch.countDown();
    }
}
