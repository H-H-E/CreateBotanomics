package education.poiesis.createbattlebots.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ArenaBoundsTest {
    @Test
    void allThreeAxesAndExactBoundaryAreRespected() {
        ArenaBounds bounds = new ArenaBounds(-10, 60, -10, 10, 90, 10);
        assertTrue(bounds.contains(-10, 60, -10));
        assertTrue(bounds.contains(10, 90, 10));
        assertFalse(bounds.contains(0, 90.001, 0), "Flying bots cannot escape through the ceiling");
        assertFalse(bounds.contains(10.001, 70, 0));
        assertFalse(bounds.contains(0, 70, Double.NaN));
        assertFalse(bounds.contains(Double.POSITIVE_INFINITY, 70, 0));
        assertEquals(12000, bounds.volume());
    }

    @Test
    void adjacentArenasMayTouchButCannotOverlap() {
        ArenaBounds first = new ArenaBounds(0, 60, 0, 20, 90, 20);
        assertFalse(first.overlaps(new ArenaBounds(20, 60, 0, 40, 90, 20)));
        assertTrue(first.overlaps(new ArenaBounds(19, 60, 0, 40, 90, 20)));
        assertTrue(first.contains(new ArenaBounds(1, 61, 1, 19, 89, 19)));
        assertFalse(first.contains(new ArenaBounds(1, 61, 1, 21, 89, 19)));
    }

    @Test
    void degenerateNonfiniteAndOutsideWorldDefinitionsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ArenaBounds(0, 0, 0, 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ArenaBounds(0, 0, 0, -1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ArenaBounds(0, 0, 0, Double.NaN, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ArenaBounds(0, 0, 0, 30_000_001, 1, 1));
    }
}
