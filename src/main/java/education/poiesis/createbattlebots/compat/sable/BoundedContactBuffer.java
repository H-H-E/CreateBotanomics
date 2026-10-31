package education.poiesis.createbattlebots.compat.sable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/** A deliberately lossy, bounded handoff from physics callbacks to the game thread. */
final class BoundedContactBuffer<T> {
    private final ArrayBlockingQueue<T> queue;
    private final AtomicLong dropped = new AtomicLong();

    BoundedContactBuffer(int capacity) {
        queue = new ArrayBlockingQueue<>(capacity);
    }

    void offer(T contact) {
        if (!queue.offer(contact)) dropped.incrementAndGet();
    }

    List<T> drain(int maximum) {
        if (maximum < 0) throw new IllegalArgumentException("Negative contact budget");
        List<T> result = new ArrayList<>(Math.min(maximum, queue.size()));
        queue.drainTo(result, maximum);
        return result;
    }

    long dropped() {
        return dropped.get();
    }

    void clear() {
        queue.clear();
    }
}
