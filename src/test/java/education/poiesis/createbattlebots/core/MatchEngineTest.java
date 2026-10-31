package education.poiesis.createbattlebots.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MatchEngineTest {
    private static final UUID FIRST = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID SECOND = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final BotStatus HEALTHY = BotStatus.healthy();

    private MatchEngine engine(ControlMode mode, MatchRules rules) {
        return new MatchEngine(UUID.randomUUID(), FIRST, SECOND, mode, rules);
    }

    private MatchEngine active() {
        MatchEngine engine = engine(ControlMode.HUMAN, MatchRules.defaults().withTiming(0, 3600));
        engine.begin(0);
        return engine;
    }

    @Test
    void countdownRejectsCombatAndPreservesEntireRoundDuration() {
        MatchEngine engine = engine(ControlMode.HUMAN, MatchRules.defaults().withTiming(100, 3600));
        engine.begin(40);
        assertEquals(MatchState.COUNTDOWN, engine.state());
        assertFalse(engine.recordComponentFailure(FIRST, SECOND, "wheel", 10, 139));
        assertFalse(engine.recordEngagement(FIRST, SECOND, 139));
        assertEquals(1, engine.countdownRemainingTicks(139));
        assertEquals(3600, engine.timeRemainingTicks(139));
        engine.tick(140, HEALTHY, HEALTHY);
        assertEquals(MatchState.ACTIVE, engine.state());
        assertEquals(3600, engine.timeRemainingTicks(140));
        assertTrue(engine.recordComponentFailure(FIRST, SECOND, "wheel", 10, 140));
        engine.tick(3739, HEALTHY, HEALTHY);
        assertEquals(MatchState.ACTIVE, engine.state());
        engine.tick(3740, HEALTHY, HEALTHY);
        assertEquals(FIRST, engine.result().orElseThrow().winner().orElseThrow());
        assertEquals(MatchResult.EndReason.DECISION, engine.result().orElseThrow().reason());
    }

    @Test
    void simultaneousCoreLossIsADrawInEitherParticipantOrder() {
        BotStatus destroyed = new BotStatus(false, true, true, false, false, true);
        for (boolean reverse : List.of(false, true)) {
            MatchEngine engine = reverse
                    ? new MatchEngine(UUID.randomUUID(), SECOND, FIRST, ControlMode.HUMAN,
                    MatchRules.defaults().withTiming(0, 100))
                    : engine(ControlMode.HUMAN, MatchRules.defaults().withTiming(0, 100));
            engine.begin(0);
            engine.tick(5, destroyed, destroyed);
            assertEquals(MatchState.RESTORING, engine.state());
            assertEquals(MatchResult.EndReason.DRAW, engine.result().orElseThrow().reason());
            assertTrue(engine.result().orElseThrow().winner().isEmpty());
        }
    }

    @Test
    void simultaneousDifferentFailuresStillDraw() {
        MatchEngine engine = active();
        engine.tick(1, new BotStatus(false, true, true, false, false, true),
                new BotStatus(true, true, true, false, true, false));
        assertEquals(MatchResult.EndReason.DRAW, engine.result().orElseThrow().reason());
        assertTrue(engine.result().orElseThrow().detail().contains("KNOCKOUT/TECHNICAL_KNOCKOUT"));
    }

    @Test
    void exactScoreTieAtTimeoutDraws() {
        MatchEngine engine = active();
        engine.recordComponentFailure(FIRST, SECOND, "wheel", 2, 5);
        engine.recordComponentFailure(SECOND, FIRST, "wheel", 2, 5);
        engine.tick(3600, HEALTHY, HEALTHY);
        assertEquals(MatchResult.EndReason.DRAW, engine.result().orElseThrow().reason());
        assertFalse(engine.recordComponentFailure(FIRST, SECOND, "another-wheel", 10, 3600));
    }

    @Test
    void scoreOnlyAwardsVerifiedComponentOnceAndCapsEachFailure() {
        MatchEngine engine = active();
        assertFalse(engine.recordComponentFailure(FIRST, SECOND, "wheel", Double.NaN, 1));
        assertTrue(engine.recordComponentFailure(FIRST, SECOND, "wheel", 1000, 1));
        assertFalse(engine.recordComponentFailure(FIRST, SECOND, "wheel", 10, 2));
        assertEquals(10, engine.score(FIRST).damage());
        assertTrue(engine.recordComponentFailure(SECOND, FIRST, "wheel", 3, 2));
        assertEquals(3, engine.score(SECOND).damage());
        assertThrows(IllegalArgumentException.class,
                () -> engine.recordComponentFailure(FIRST, FIRST, "wheel", 10, 2));
        assertThrows(IllegalArgumentException.class,
                () -> engine.recordComponentFailure(UUID.randomUUID(), FIRST, "wheel", 10, 2));
    }

    @Test
    void controlCannotBeInflatedByRepeatedCallsWithinOneTick() {
        MatchEngine engine = active();
        assertTrue(engine.recordControl(FIRST, 0.03, 10));
        assertTrue(engine.recordControl(FIRST, 100, 10));
        assertFalse(engine.recordControl(FIRST, 100, 10));
        assertEquals(0.05, engine.score(FIRST).control(), 1e-12);
        assertTrue(engine.recordControl(FIRST, 100, 11));
        assertEquals(0.10, engine.score(FIRST).control(), 1e-12);
    }

    @Test
    void aggressionUsesPerAttackerCooldown() {
        MatchEngine engine = active();
        assertTrue(engine.recordEngagement(FIRST, SECOND, 0));
        assertFalse(engine.recordEngagement(FIRST, SECOND, 19));
        assertTrue(engine.recordEngagement(SECOND, FIRST, 19));
        assertTrue(engine.recordEngagement(FIRST, SECOND, 20));
        assertEquals(2, engine.score(FIRST).aggression());
        assertEquals(1, engine.score(SECOND).aggression());
    }

    @Test
    void deliberateStationaryTacticsDoNotTriggerImmobilization() {
        MatchEngine engine = active();
        BotStatus stationary = new BotStatus(true, true, true, false, false, true);
        engine.tick(1, stationary, HEALTHY);
        engine.tick(2000, stationary, HEALTHY);
        assertEquals(MatchState.ACTIVE, engine.state());
        assertTrue(engine.result().isEmpty());
    }

    @Test
    void commandedImmobilizationRequiresAnUninterruptedTimeout() {
        MatchEngine engine = active();
        BotStatus stuck = new BotStatus(true, true, true, true, false, true);
        engine.tick(1, stuck, HEALTHY);
        engine.tick(200, stuck, HEALTHY);
        assertEquals(MatchState.ACTIVE, engine.state());
        engine.tick(201, HEALTHY, HEALTHY);
        engine.tick(202, stuck, HEALTHY);
        engine.tick(402, stuck, HEALTHY);
        assertEquals(MatchResult.EndReason.IMMOBILIZED, engine.result().orElseThrow().reason());
        assertEquals(SECOND, engine.result().orElseThrow().winner().orElseThrow());
    }

    @Test
    void reconnectWithinGraceResetsDisconnectTimer() {
        MatchEngine engine = active();
        BotStatus offline = new BotStatus(true, true, false, false, true, true);
        engine.tick(5, offline, HEALTHY);
        engine.tick(304, offline, HEALTHY);
        assertEquals(MatchState.ACTIVE, engine.state());
        engine.tick(305, HEALTHY, HEALTHY);
        engine.tick(306, offline, HEALTHY);
        engine.tick(606, offline, HEALTHY);
        assertEquals(MatchResult.EndReason.DISCONNECT, engine.result().orElseThrow().reason());
        assertEquals(SECOND, engine.result().orElseThrow().winner().orElseThrow());
        assertEquals(2, engine.drainEvents().stream().filter(e -> e.type() == MatchEvent.Type.DISCONNECTED).count());
    }

    @Test
    void autonomousOwnerCanDisconnectWithoutStoppingOnboardComputerMatch() {
        MatchEngine engine = engine(ControlMode.AUTONOMOUS, MatchRules.defaults().withTiming(0, 3600));
        engine.begin(0);
        BotStatus offline = new BotStatus(true, true, false, false, true, true);
        engine.tick(5, offline, offline);
        engine.tick(1000, offline, offline);
        assertEquals(MatchState.ACTIVE, engine.state());
        assertTrue(engine.drainEvents().stream().noneMatch(e -> e.type() == MatchEvent.Type.DISCONNECTED));
    }

    @Test
    void briefRingOutReturnsSafelyButSustainedOutsideEndsRound() {
        MatchEngine engine = active();
        BotStatus outside = new BotStatus(true, false, true, false, true, true);
        engine.tick(1, outside, HEALTHY);
        engine.tick(20, HEALTHY, HEALTHY);
        engine.tick(21, outside, HEALTHY);
        engine.tick(40, outside, HEALTHY);
        assertEquals(MatchState.ACTIVE, engine.state());
        engine.tick(41, outside, HEALTHY);
        assertEquals(MatchResult.EndReason.RING_OUT, engine.result().orElseThrow().reason());
    }

    @Test
    void restorationFailureKeepsLocksUntilExplicitVerifiedRetry() {
        MatchEngine engine = active();
        engine.surrender(FIRST, 20);
        assertEquals(MatchState.RESTORING, engine.state());
        assertTrue(engine.locksRequired());
        engine.recoveryRequired("Missing shaft NBT", 21);
        assertEquals(MatchState.RECOVERY, engine.state());
        assertTrue(engine.locksRequired());
        assertThrows(IllegalStateException.class, () -> engine.restored(22));
        engine.retryRestoration(22);
        engine.restored(23);
        assertEquals(MatchState.FINISHED, engine.state());
        assertFalse(engine.locksRequired());
        assertEquals(MatchResult.EndReason.SURRENDER, engine.result().orElseThrow().reason());
        assertThrows(IllegalStateException.class, () -> engine.restored(24));
    }

    @Test
    void abortHasNoWinnerAndStillRestores() {
        MatchEngine engine = active();
        engine.abort("Referee stopped event", 10);
        assertEquals(MatchState.RESTORING, engine.state());
        assertEquals(MatchResult.EndReason.ABORTED, engine.result().orElseThrow().reason());
        assertTrue(engine.result().orElseThrow().winner().isEmpty());
        assertFalse(engine.recordControl(FIRST, 100, 11));
        engine.tick(12, HEALTHY, HEALTHY);
        assertEquals(MatchState.RESTORING, engine.state());
    }

    @Test
    void auditEventsRemainOrderedAndResultScoresCannotBeModified() {
        MatchEngine engine = active();
        engine.recordEngagement(FIRST, SECOND, 10);
        engine.surrender(SECOND, 11);
        List<MatchEvent> events = engine.drainEvents();
        assertEquals(List.of(MatchEvent.Type.COUNTDOWN, MatchEvent.Type.STARTED,
                MatchEvent.Type.ENGAGEMENT, MatchEvent.Type.ENDED), events.stream().map(MatchEvent::type).toList());
        for (int i = 1; i < events.size(); i++) {
            assertTrue(events.get(i).sequence() > events.get(i - 1).sequence());
            assertTrue(events.get(i).tick() >= events.get(i - 1).tick());
        }
        assertTrue(engine.drainEvents().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> engine.result().orElseThrow().scores().clear());
        assertThrows(UnsupportedOperationException.class, () -> engine.scores().clear());
    }

    @Test
    void clockCannotRewindToFarmScoresOrExtendGrace() {
        MatchEngine engine = active();
        engine.tick(100, HEALTHY, HEALTHY);
        assertThrows(IllegalArgumentException.class, () -> engine.tick(99, HEALTHY, HEALTHY));
        assertThrows(IllegalArgumentException.class, () -> engine.recordEngagement(FIRST, SECOND, 99));
    }

    @Test
    void invalidRulesAreRejectedBeforeWorldStateChanges() {
        assertThrows(IllegalArgumentException.class, () -> MatchRules.defaults().withTiming(-1, 10));
        assertThrows(IllegalArgumentException.class, () -> MatchRules.defaults().withTiming(1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new MatchRules(0, 1, 0, 1, 0, true, 1, Double.NaN, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new MatchEngine(UUID.randomUUID(), FIRST, FIRST, ControlMode.HUMAN, MatchRules.defaults()));
    }
}
