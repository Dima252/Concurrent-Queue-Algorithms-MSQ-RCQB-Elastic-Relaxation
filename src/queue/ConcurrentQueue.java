package queue;

/**
 * Minimal interface shared by all queue implementations in this project.
 * dequeue() returns null when the queue is empty rather than blocking.
 */
public interface ConcurrentQueue<T> {
    void    enqueue(T value);
    T       dequeue();
    boolean isEmpty();
}
