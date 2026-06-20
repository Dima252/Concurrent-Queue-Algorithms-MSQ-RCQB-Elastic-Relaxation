package benchmark;

import queue.ConcurrentQueue;
import queue.ElasticRelaxedQueue;
import queue.MichaelScottQueue;
import queue.RCQBQueue;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Correctness test suite for MichaelScottQueue and ElasticRelaxedQueue.
 *
 * Tests:
 *   1. Single-threaded FIFO ordering (MSQ only — ERQ is relaxed by design).
 *   2. Dequeue on an empty queue returns null.
 *   3. Multi-threaded no-loss: N enqueuer threads × M items each; after all
 *      enqueuers finish, drain and verify every item was received exactly once.
 *   4. Concurrent enqueue+dequeue no-loss: N enqueuers and N dequeuers run
 *      simultaneously; the union of dequeued items must equal the enqueued set.
 *   5. Stress: high thread count (64 threads) no-loss on both queues.
 *
 * Run:  java -cp bin benchmark.CorrectnessTest
 */
public final class CorrectnessTest {

    // ── Tunables ──────────────────────────────────────────────────────────────
    private static final int ITEMS_PER_THREAD   = 10_000;
    private static final int DRAIN_SPIN_LIMIT   = 5_000_000; // prevent infinite loop on bug

    private static int passed = 0;
    private static int failed = 0;

    // ── Entry point ───────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        System.out.println("=== Correctness Test Suite ===\n");

        // ── Test 1: Single-threaded FIFO (MSQ) ───────────────────────────────
        {
            MichaelScottQueue<Integer> q = new MichaelScottQueue<>();
            for (int i = 1; i <= 5; i++) q.enqueue(i);
            boolean ok = true;
            for (int i = 1; i <= 5; i++) {
                Integer v = q.dequeue();
                if (v == null || v != i) { ok = false; break; }
            }
            ok = ok && (q.dequeue() == null); // must be empty now
            report("MSQ single-threaded FIFO ordering", ok);
        }

        // ── Test 2: Dequeue on empty queue ────────────────────────────────────
        {
            report("MSQ  dequeue-empty returns null",
                    new MichaelScottQueue<Integer>().dequeue() == null);
            report("RCQB dequeue-empty returns null",
                    new RCQBQueue<Integer>().dequeue() == null);
            report("ERQ  dequeue-empty returns null",
                    new ElasticRelaxedQueue<Integer>().dequeue() == null);
        }

        // ── Test 3: Multi-threaded no-loss ────────────────────────────────────
        // RCQB is a BOUNDED ring buffer (N=256 slots). The drain-after pattern
        // deadlocks once the buffer fills: enqueuers block waiting for a FREE
        // slot, but no dequeuers are running to free any. RCQB must always use
        // concurrent dequeuers so the buffer is continuously drained as items
        // are added.  MSQ and ERQ are unbounded, so drain-after works fine.
        for (int threads : new int[]{2, 4, 8, 16}) {
            noLossTest("MSQ ", new MichaelScottQueue<>(),  threads, false);
            noLossTest("RCQB", new RCQBQueue<>(),           threads, true);
            noLossTest("ERQ ", new ElasticRelaxedQueue<>(), threads, false);
        }

        // ── Test 4: Concurrent enqueue + dequeue no-loss ─────────────────────
        for (int threads : new int[]{2, 4, 8, 16}) {
            noLossTest("MSQ ", new MichaelScottQueue<>(),  threads, true);
            noLossTest("RCQB", new RCQBQueue<>(),           threads, true);
            noLossTest("ERQ ", new ElasticRelaxedQueue<>(), threads, true);
        }

        // ── Test 5: Stress (64 threads) ───────────────────────────────────────
        noLossTest("MSQ -stress",  new MichaelScottQueue<>(),  64, true);
        noLossTest("RCQB-stress",  new RCQBQueue<>(),           64, true);
        noLossTest("ERQ -stress",  new ElasticRelaxedQueue<>(), 64, true);

        // ── Summary ───────────────────────────────────────────────────────────
        System.out.println();
        System.out.println("Results: " + passed + " passed, " + failed + " FAILED");
        if (failed > 0) System.exit(1);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * No-loss test.
     *
     * {@code concurrentDequeuers = false}: all N threads only enqueue; after they
     * finish the main thread drains the queue and checks completeness.
     *
     * {@code concurrentDequeuers = true}: N enqueuer threads + N dequeuer threads
     * run simultaneously; the dequeued set must equal the enqueued set.
     */
    private static void noLossTest(String label,
                                   ConcurrentQueue<Integer> queue,
                                   int threads,
                                   boolean concurrentDequeuers) throws Exception {
        int totalItems = threads * ITEMS_PER_THREAD;

        // Build expected set: thread T enqueues [T*M .. (T+1)*M - 1]
        Set<Integer> expected = new HashSet<>(totalItems * 2);
        for (int i = 0; i < totalItems; i++) expected.add(i);

        // Collected set (thread-safe)
        Set<Integer> received = Collections.newSetFromMap(new ConcurrentHashMap<>());

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch enqDone   = new CountDownLatch(threads);

        // ── Enqueuer threads ──────────────────────────────────────────────────
        for (int t = 0; t < threads; t++) {
            final int base = t * ITEMS_PER_THREAD;
            new Thread(() -> {
                try { startGate.await(); } catch (InterruptedException e) { return; }
                for (int i = 0; i < ITEMS_PER_THREAD; i++) queue.enqueue(base + i);
                enqDone.countDown();
            }).start();
        }

        // ── Optional concurrent dequeuer threads ──────────────────────────────
        CountDownLatch deqDone = new CountDownLatch(concurrentDequeuers ? threads : 0);
        AtomicInteger  stopDeq = new AtomicInteger(0);

        if (concurrentDequeuers) {
            for (int t = 0; t < threads; t++) {
                new Thread(() -> {
                    try { startGate.await(); } catch (InterruptedException e) { return; }
                    while (stopDeq.get() == 0 || !queue.isEmpty()) {
                        Integer v = queue.dequeue();
                        if (v != null) received.add(v);
                    }
                    // Drain any residual items after stop signal
                    Integer v;
                    while ((v = queue.dequeue()) != null) received.add(v);
                    deqDone.countDown();
                }).start();
            }
        }

        startGate.countDown();  // release all threads
        enqDone.await();        // wait for all enqueuers

        if (concurrentDequeuers) {
            stopDeq.set(1);     // signal dequeuers to finish after draining
            deqDone.await();
        } else {
            // Drain on main thread with a spin-limit to catch infinite loops
            int spins = 0;
            Integer v;
            while ((v = queue.dequeue()) != null) {
                received.add(v);
                if (++spins > DRAIN_SPIN_LIMIT) break;
            }
        }

        boolean noLoss      = received.containsAll(expected);
        boolean noDuplicate = received.size() == expected.size();
        boolean ok          = noLoss && noDuplicate;

        String mode = concurrentDequeuers ? "concurrent-deq" : "drain-after";
        String name = label + " no-loss t=" + threads + " [" + mode + "]";
        if (!ok) {
            int lost = 0;
            for (Integer i : expected) if (!received.contains(i)) lost++;
            int extra = received.size() - (expected.size() - lost);
            name += "  lost=" + lost + " extra=" + extra;
        }
        report(name, ok);
    }

    private static void report(String testName, boolean ok) {
        String status = ok ? "PASS" : "FAIL";
        System.out.printf("  [%-4s] %s%n", status, testName);
        if (ok) passed++; else failed++;
    }
}
