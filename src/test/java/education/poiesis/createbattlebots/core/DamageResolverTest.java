package education.poiesis.createbattlebots.core;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DamageResolverTest {
    private final UUID first = UUID.randomUUID();
    private final UUID second = UUID.randomUUID();
    private final DamageResolver.Settings settings = DamageResolver.Settings.defaults();

    @Test
    void energyScalesWithMassAndSquareOfClosingSpeedUntilCapped() {
        double base = DamageResolver.estimate(1, 0, 100, 1, settings);
        assertEquals(0.5, base, 1e-12);
        assertEquals(base * 4, DamageResolver.estimate(2, 0, 100, 1, settings), 1e-12);
        assertEquals(base * 2, DamageResolver.estimate(1, 0, 200, 1, settings), 1e-12);
        assertEquals(base / 2, DamageResolver.estimate(1, 0, 100, 2, settings), 1e-12);
        assertEquals(settings.maxDamagePerHit(), DamageResolver.estimate(100, 0, 10000, 1, settings));
    }

    @Test
    void lowSpeedRestingAndSeparatingContactsCauseNoDamage() {
        assertEquals(0, DamageResolver.estimate(0, 0, 100, 1, settings));
        assertEquals(0, DamageResolver.estimate(0.19, 0, 100, 1, settings));
        assertEquals(0, DamageResolver.estimate(-10, 0, 100, 1, settings));
        assertTrue(DamageResolver.estimate(0, 2, 100, 1, settings) > 0);
    }

    @Test
    void nonfinitePhysicsCannotDamageOrPoisonARealHitCooldown() {
        DamageResolver resolver = new DamageResolver(first, second);
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertEquals(0, resolver.process(first, second, 1, invalid, 100, 1));
            assertEquals(0, resolver.process(first, second, 1, 1, invalid, 1));
            assertEquals(0, resolver.process(first, second, 1, 1, 100, invalid));
        }
        assertEquals(0, resolver.process(first, second, 1, 1, -100, 1));
        assertEquals(0, resolver.process(first, second, 1, 1, 100, 0));
        assertTrue(resolver.process(first, second, 1, 1, 100, 1) > 0);
    }

    @Test
    void extremeFiniteObservationsStayFiniteAndRespectPerHitCap() {
        double damage = DamageResolver.estimate(Double.MAX_VALUE, Double.MAX_VALUE,
                Double.MAX_VALUE, Double.MIN_VALUE, settings);
        assertTrue(Double.isFinite(damage));
        assertEquals(settings.maxDamagePerHit(), damage);
    }

    @Test
    void repeatedCollisionCallbacksAreDeduplicatedUntilCooldownExpires() {
        DamageResolver resolver = new DamageResolver(first, second);
        assertTrue(resolver.process(first, second, 10, 2, 100, 1) > 0);
        assertEquals(0, resolver.process(first, second, 10, 2, 100, 1));
        assertEquals(0, resolver.process(first, second, 19, 2, 100, 1));
        assertTrue(resolver.process(first, second, 20, 2, 100, 1) > 0);
        assertTrue(resolver.process(second, first, 20, 2, 100, 1) > 0);
    }

    @Test
    void distinctSurfacesCanBeHitButShareOneWorldDestructionBudget() {
        DamageResolver resolver = new DamageResolver(first, second);
        assertTrue(resolver.process(first, second, "wheel", 1, 2, 0, 100, 1, true) > 0);
        assertTrue(resolver.process(first, second, "shaft", 1, 2, 0, 100, 1, true) > 0);
        assertEquals(3, resolver.claimDestruction(1, 3));
        assertEquals(1, resolver.claimDestruction(1, 1000));
        assertEquals(0, resolver.claimDestruction(1, 1));
        assertEquals(0, resolver.remainingDestructionBudget(1));
        assertEquals(4, resolver.claimDestruction(2, 1000));
        assertEquals(0, resolver.claimDestruction(1, 1), "Old ticks cannot reset the budget");
    }

    @Test
    void selfHitsOutsidersAndOutsideArenaHitsAreRejected() {
        DamageResolver resolver = new DamageResolver(first, second);
        assertEquals(0, resolver.process(first, first, 1, 2, 100, 1));
        assertEquals(0, resolver.process(UUID.randomUUID(), second, 1, 2, 100, 1));
        assertEquals(0, resolver.process(first, second, "wheel", 1, 2, 0, 100, 1, false));
        assertEquals(0, resolver.trackedContactCount());
        assertTrue(resolver.process(first, second, "wheel", 1, 2, 0, 100, 1, true) > 0);
    }

    @Test
    void contactMemoryIsBoundedAndExpiredEntriesBecomeReusable() {
        DamageResolver resolver = new DamageResolver(first, second,
                new DamageResolver.Settings(100, 0.2, 40, 10, 4, 2, 1000, 100));
        assertTrue(resolver.process(first, second, "one", 1, 2, 0, 100, 1, true) > 0);
        assertTrue(resolver.process(first, second, "two", 1, 2, 0, 100, 1, true) > 0);
        assertEquals(0, resolver.process(first, second, "three", 1, 2, 0, 100, 1, true));
        assertEquals(2, resolver.trackedContactCount());
        assertTrue(resolver.process(first, second, "three", 11, 2, 0, 100, 1, true) > 0);
        assertEquals(1, resolver.trackedContactCount());
    }

    @Test
    void malformedSettingsAndNegativeBudgetsFailBeforeMutation() {
        assertThrows(IllegalArgumentException.class,
                () -> new DamageResolver.Settings(100, 0.2, 40, 0, 4, 2, 1000, 100));
        assertThrows(IllegalArgumentException.class,
                () -> new DamageResolver.Settings(100, 0.2, Double.POSITIVE_INFINITY, 10, 4, 2, 1000, 100));
        DamageResolver resolver = new DamageResolver(first, second);
        assertThrows(IllegalArgumentException.class, () -> resolver.claimDestruction(1, -1));
        assertEquals(4, resolver.claimDestruction(1, 4));
    }
}
