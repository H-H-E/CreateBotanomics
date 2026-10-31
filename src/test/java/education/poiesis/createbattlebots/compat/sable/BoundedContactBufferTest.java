package education.poiesis.createbattlebots.compat.sable;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BoundedContactBufferTest {
    @Test
    void excessivePhysicsCallbacksCannotGrowMemoryOrBypassTickBudget() {
        BoundedContactBuffer<Integer> buffer = new BoundedContactBuffer<>(3);
        for (int i = 0; i < 10_000; i++) buffer.offer(i);
        assertEquals(9_997, buffer.dropped());
        assertEquals(List.of(0, 1), buffer.drain(2));
        assertEquals(List.of(2), buffer.drain(2));
        assertEquals(List.of(), buffer.drain(2));
    }

    @Test
    void matchBoundaryDiscardPreventsPracticeContactsFromEnteringNextMatch() {
        BoundedContactBuffer<Integer> buffer = new BoundedContactBuffer<>(2);
        buffer.offer(1);
        buffer.clear();
        buffer.offer(2);
        assertEquals(List.of(2), buffer.drain(2));
        assertThrows(IllegalArgumentException.class, () -> buffer.drain(-1));
    }
}
