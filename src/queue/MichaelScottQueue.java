package queue;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Textbook Michael-Scott non-blocking lock-free FIFO queue (PODC 1996).
 *
 * Invariants (§3 of the paper, adapted for Java GC):
 *   1. The linked list is always connected.
 *   2. Nodes are only inserted after the last node in the list.
 *   3. Nodes are only removed from the head of the list.
 *   4. Head always points to the first (sentinel) node.
 *   5. Tail always points to a node in the list (last or second-to-last).
 *
 * ABA safety: Java's garbage collector prevents node reclamation while any
 * thread holds a reference, so no counted-pointer trick is needed here.
 */
public class MichaelScottQueue<T> implements ConcurrentQueue<T> {

    private final AtomicReference<Node<T>> head;
    private final AtomicReference<Node<T>> tail;

    public MichaelScottQueue() {
        Node<T> sentinel = new Node<>();          // dummy head; value == null
        head = new AtomicReference<>(sentinel);
        tail = new AtomicReference<>(sentinel);
    }

    @Override
    public void enqueue(T value) {
        Node<T> newNode = new Node<>(value);
        while (true) {
            Node<T> t    = tail.get();
            Node<T> next = t.next.get();
            if (t == tail.get()) {                // re-read to detect races
                if (next == null) {               // tail truly points to last node
                    if (t.next.compareAndSet(null, newNode)) {
                        tail.compareAndSet(t, newNode); // best-effort; ok if it fails
                        return;
                    }
                } else {
                    tail.compareAndSet(t, next);  // help a thread that stalled mid-enqueue
                }
            }
        }
    }

    @Override
    public T dequeue() {
        while (true) {
            Node<T> h    = head.get();
            Node<T> t    = tail.get();
            Node<T> next = h.next.get();
            if (h == head.get()) {                // snapshot still consistent?
                if (h == t) {                     // queue empty or tail lagging
                    if (next == null) return null; // truly empty
                    tail.compareAndSet(t, next);  // advance lagging tail
                } else {
                    T value = next.value;          // read value BEFORE CAS (§D12)
                    if (head.compareAndSet(h, next)) return value;
                }
            }
        }
    }

    @Override
    public boolean isEmpty() {
        return head.get().next.get() == null;
    }
}
