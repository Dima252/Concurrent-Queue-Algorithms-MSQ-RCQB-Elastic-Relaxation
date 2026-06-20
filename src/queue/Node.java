package queue;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Singly-linked list node used by MichaelScottQueue and Lane.
 * The no-arg constructor produces a sentinel (dummy head) node whose value is null.
 */
public final class Node<T> {
    public final T value;
    public final AtomicReference<Node<T>> next = new AtomicReference<>(null);

    /** Data node. */
    public Node(T value) { this.value = value; }

    /** Sentinel / dummy-head node. */
    public Node() { this.value = null; }
}
