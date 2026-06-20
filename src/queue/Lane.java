package queue;

import java.util.concurrent.atomic.AtomicReference;

/**
 * A single MS-queue lane used as a building block by ElasticRelaxedQueue.
 *
 * Differs from MichaelScottQueue in two ways:
 *   1. Reports every CAS outcome to a shared ContentionMonitor.
 *   2. tryEnqueue() returns false on the first CAS miss instead of retrying
 *      indefinitely — the caller (ElasticRelaxedQueue) retries on a different
 *      lane, which is the key mechanism for contention spreading.
 *
 * tryDequeue() still retries within the lane when the lane is non-empty,
 * because there is no alternative lane to redirect to (round-robin is only
 * applied at the ElasticRelaxedQueue level for dequeues).
 */
public final class Lane<T> {

    final AtomicReference<Node<T>> head;
    final AtomicReference<Node<T>> tail;

    public Lane() {
        Node<T> sentinel = new Node<>();
        head = new AtomicReference<>(sentinel);
        tail = new AtomicReference<>(sentinel);
    }

    /**
     * Attempts to enqueue {@code value} into this lane.
     *
     * @return true on success; false if a CAS collision was detected, signalling
     *         the caller to redirect to a different lane.
     */
    public boolean tryEnqueue(T value, ContentionMonitor monitor) {
        Node<T> newNode = new Node<>(value);
        while (true) {
            Node<T> t    = tail.get();
            Node<T> next = t.next.get();
            if (t == tail.get()) {
                if (next == null) {
                    if (t.next.compareAndSet(null, newNode)) {
                        monitor.recordSuccess();
                        tail.compareAndSet(t, newNode); // best-effort tail advance
                        return true;
                    } else {
                        monitor.recordFailure();
                        return false;           // tell caller to try another lane
                    }
                } else {
                    tail.compareAndSet(t, next); // help stalled enqueuer
                }
            }
        }
    }

    /**
     * Attempts to dequeue from this lane.
     *
     * @return the dequeued value, or {@code null} if the lane is empty.
     */
    public T tryDequeue(ContentionMonitor monitor) {
        while (true) {
            Node<T> h    = head.get();
            Node<T> t    = tail.get();
            Node<T> next = h.next.get();
            if (h == head.get()) {
                if (h == t) {
                    if (next == null) return null;  // lane is empty
                    tail.compareAndSet(t, next);    // advance lagging tail
                } else {
                    T value = next.value;            // read before CAS
                    if (head.compareAndSet(h, next)) {
                        monitor.recordSuccess();
                        return value;
                    } else {
                        monitor.recordFailure();
                        // another thread won this slot; retry — item is still here
                    }
                }
            }
        }
    }

    public boolean isEmpty() {
        return head.get().next.get() == null;
    }
}
