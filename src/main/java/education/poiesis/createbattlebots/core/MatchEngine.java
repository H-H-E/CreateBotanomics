package education.poiesis.createbattlebots.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Deterministic 1v1 rules, called exclusively on the server thread.
 *
 * <p>The Minecraft service owns snapshots, players, collision evidence and world changes. Call
 * {@link #begin(long)} only once the snapshots are durably stored. Submit BOTH robots' observations
 * in one {@link #tick(long, BotStatus, BotStatus)} call; deciding a winner separately for each bot
 * would make a simultaneous knockout depend on iteration order. Drain events each service tick.
 * Finishing combat always enters RESTORING; only verified restoration releases the match.</p>
 */
public final class MatchEngine {
    private static final int MAX_SCORED_COMPONENTS = 65_536;
    private final UUID matchId;
    private final UUID firstBot;
    private final UUID secondBot;
    private final ControlMode mode;
    private final MatchRules rules;
    private final Map<UUID, MatchScore> scores = new HashMap<>();
    private final Map<UUID, ObservationClock> clocks = new HashMap<>();
    private final Set<FailedComponent> scoredFailures = new HashSet<>();
    private final List<MatchEvent> pendingEvents = new ArrayList<>();
    private MatchState state = MatchState.PREPARING;
    private MatchResult result;
    private long lastTick = -1;
    private long activeFrom = -1;
    private long activeUntil = -1;
    private long eventSequence;
    private String recoveryReason = "";

    public MatchEngine(UUID matchId, UUID firstBot, UUID secondBot, ControlMode mode, MatchRules rules) {
        this.matchId = Objects.requireNonNull(matchId, "matchId");
        this.firstBot = Objects.requireNonNull(firstBot, "firstBot");
        this.secondBot = Objects.requireNonNull(secondBot, "secondBot");
        this.mode = Objects.requireNonNull(mode, "mode");
        this.rules = Objects.requireNonNull(rules, "rules");
        if (firstBot.equals(secondBot)) throw new IllegalArgumentException("A bot cannot fight itself");
        for (UUID bot : List.of(firstBot, secondBot)) {
            scores.put(bot, MatchScore.zero());
            clocks.put(bot, new ObservationClock());
        }
    }

    public void begin(long tick) {
        requireState(MatchState.PREPARING);
        clock(tick);
        activeFrom = Math.addExact(tick, rules.countdownTicks());
        activeUntil = Math.addExact(activeFrom, rules.roundTicks());
        state = MatchState.COUNTDOWN;
        emit(tick, MatchEvent.Type.COUNTDOWN, null, rules.countdownTicks(), mode.name());
        if (tick == activeFrom) start(tick);
    }

    public void tick(long tick, BotStatus first, BotStatus second) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        clock(tick);
        if (state != MatchState.COUNTDOWN && state != MatchState.ACTIVE) return;

        // Ownership and assembly must remain valid during countdown as well as during combat.
        if (state == MatchState.COUNTDOWN && tick >= activeFrom) start(activeFrom);
        Optional<MatchResult.EndReason> firstFailure = failure(firstBot, first, tick);
        Optional<MatchResult.EndReason> secondFailure = failure(secondBot, second, tick);
        if (firstFailure.isPresent() && secondFailure.isPresent()) {
            finish(Optional.empty(), MatchResult.EndReason.DRAW, tick,
                    "Both bots failed on the same observation: " + firstFailure.get() + "/" + secondFailure.get());
        } else if (firstFailure.isPresent()) {
            finish(Optional.of(secondBot), firstFailure.get(), tick, firstBot + " failed");
        } else if (secondFailure.isPresent()) {
            finish(Optional.of(firstBot), secondFailure.get(), tick, secondBot + " failed");
        } else if (state == MatchState.ACTIVE && tick >= activeUntil) {
            double firstTotal = scores.get(firstBot).total(rules);
            double secondTotal = scores.get(secondBot).total(rules);
            int comparison = Double.compare(firstTotal, secondTotal);
            Optional<UUID> winner = comparison == 0 ? Optional.empty()
                    : Optional.of(comparison > 0 ? firstBot : secondBot);
            finish(winner, comparison == 0 ? MatchResult.EndReason.DRAW : MatchResult.EndReason.DECISION,
                    tick, "Judge totals: " + firstTotal + " / " + secondTotal);
        }
    }

    /** Record an observed, opponent-caused failure once for the lifetime of this match. */
    public boolean recordComponentFailure(UUID attacker, UUID victim, String componentKey,
                                          double points, long tick) {
        requireParticipants(attacker, victim);
        requireEvidenceKey(componentKey);
        if (!positiveFinite(points)) return false;
        clock(tick);
        if (!combatAccepts(tick) || scoredFailures.size() >= MAX_SCORED_COMPONENTS) return false;
        if (!scoredFailures.add(new FailedComponent(victim, componentKey))) return false;
        double award = Math.min(points, rules.maxDamageScorePerFailure());
        MatchScore old = scores.get(attacker);
        scores.put(attacker, new MatchScore(old.damage() + award, old.control(), old.aggression()));
        emit(tick, MatchEvent.Type.COMPONENT_FAILURE, attacker, award, victim + ":" + componentKey);
        return true;
    }

    /** Only call with verified effective displacement/position control, never raw proximity. */
    public boolean recordControl(UUID bot, double points, long tick) {
        participant(bot);
        if (!positiveFinite(points)) return false;
        clock(tick);
        if (!combatAccepts(tick)) return false;
        ObservationClock observations = clocks.get(bot);
        if (observations.lastControlTick != tick) {
            observations.lastControlTick = tick;
            observations.controlAwardedThisTick = 0;
        }
        double award = Math.min(points, rules.maxControlScorePerTick() - observations.controlAwardedThisTick);
        if (award <= 0) return false;
        observations.controlAwardedThisTick += award;
        MatchScore old = scores.get(bot);
        scores.put(bot, new MatchScore(old.damage(), old.control() + award, old.aggression()));
        emit(tick, MatchEvent.Type.CONTROL, bot, award, "verified arena control");
        return true;
    }

    /** Only call for purposeful opponent engagement. Cooldown prevents contact farming. */
    public boolean recordEngagement(UUID attacker, UUID victim, long tick) {
        requireParticipants(attacker, victim);
        clock(tick);
        if (!combatAccepts(tick)) return false;
        ObservationClock observations = clocks.get(attacker);
        if (observations.lastAggressionTick >= 0
                && tick - observations.lastAggressionTick < rules.aggressionCooldownTicks()) return false;
        observations.lastAggressionTick = tick;
        MatchScore old = scores.get(attacker);
        scores.put(attacker, new MatchScore(old.damage(), old.control(), old.aggression() + 1));
        emit(tick, MatchEvent.Type.ENGAGEMENT, attacker, 1, victim.toString());
        return true;
    }

    public void surrender(UUID bot, long tick) {
        participant(bot);
        clock(tick);
        if (state != MatchState.COUNTDOWN && state != MatchState.ACTIVE) return;
        finish(Optional.of(opponent(bot)), MatchResult.EndReason.SURRENDER, tick, bot + " surrendered");
    }

    public void abort(String reason, long tick) {
        Objects.requireNonNull(reason, "reason");
        clock(tick);
        if (state == MatchState.FINISHED || state == MatchState.RESTORING || state == MatchState.RECOVERY) return;
        finish(Optional.empty(), MatchResult.EndReason.ABORTED, tick, reason);
    }

    public void restored(long tick) {
        requireState(MatchState.RESTORING);
        clock(tick);
        state = MatchState.FINISHED;
        recoveryReason = "";
        emit(tick, MatchEvent.Type.RESTORED, null, 0, "Both snapshots verified");
    }

    /** If world verification fails, never release ownership/protection locks by calling restored. */
    public void recoveryRequired(String reason, long tick) {
        requireState(MatchState.RESTORING);
        Objects.requireNonNull(reason, "reason");
        clock(tick);
        state = MatchState.RECOVERY;
        recoveryReason = reason;
        emit(tick, MatchEvent.Type.RECOVERY_REQUIRED, null, 0, reason);
    }

    public void retryRestoration(long tick) {
        requireState(MatchState.RECOVERY);
        clock(tick);
        state = MatchState.RESTORING;
        emit(tick, MatchEvent.Type.RESTORE_RETRY, null, 0, recoveryReason);
    }

    public UUID matchId() { return matchId; }
    public UUID firstBot() { return firstBot; }
    public UUID secondBot() { return secondBot; }
    public ControlMode mode() { return mode; }
    public MatchRules rules() { return rules; }
    public MatchState state() { return state; }
    public Optional<MatchResult> result() { return Optional.ofNullable(result); }
    public String recoveryReason() { return recoveryReason; }
    public Map<UUID, MatchScore> scores() { return Map.copyOf(scores); }
    public MatchScore score(UUID bot) { participant(bot); return scores.get(bot); }
    public boolean locksRequired() { return state != MatchState.PREPARING && state != MatchState.FINISHED; }
    public long countdownRemainingTicks(long tick) {
        return state == MatchState.COUNTDOWN ? Math.max(0, activeFrom - tick) : 0;
    }
    public long timeRemainingTicks(long tick) {
        return switch (state) {
            case PREPARING, COUNTDOWN -> rules.roundTicks();
            case ACTIVE -> Math.max(0, activeUntil - tick);
            default -> 0;
        };
    }

    public List<MatchEvent> drainEvents() {
        List<MatchEvent> result = List.copyOf(pendingEvents);
        pendingEvents.clear();
        return result;
    }

    private Optional<MatchResult.EndReason> failure(UUID bot, BotStatus status, long tick) {
        ObservationClock observations = clocks.get(bot);
        if (!status.assemblyValid()) return Optional.of(MatchResult.EndReason.TECHNICAL_KNOCKOUT);
        if (!status.coreIntact()) return Optional.of(MatchResult.EndReason.KNOCKOUT);

        // Autonomous robots run on their onboard computers; an owner logging out is not a failure.
        if (mode == ControlMode.HUMAN && !status.connected()) {
            if (observations.disconnectedSince < 0) {
                observations.disconnectedSince = tick;
                emit(tick, MatchEvent.Type.DISCONNECTED, bot, 0, "Reconnect grace began");
            }
            if (tick - observations.disconnectedSince >= rules.disconnectGraceTicks()) {
                return Optional.of(MatchResult.EndReason.DISCONNECT);
            }
        } else if (observations.disconnectedSince >= 0) {
            observations.disconnectedSince = -1;
            emit(tick, MatchEvent.Type.RECONNECTED, bot, 0, "Reconnect grace cleared");
        }

        if (state != MatchState.ACTIVE) return Optional.empty();
        if (rules.ringOutEnabled() && !status.insideArena()) {
            if (observations.outsideSince < 0) observations.outsideSince = tick;
            if (tick - observations.outsideSince >= rules.ringOutGraceTicks()) {
                return Optional.of(MatchResult.EndReason.RING_OUT);
            }
        } else {
            observations.outsideSince = -1;
        }

        if (status.movementCommanded() && !status.propulsionWorking()) {
            if (observations.immobileSince < 0) observations.immobileSince = tick;
            if (tick - observations.immobileSince >= rules.immobilizationTicks()) {
                return Optional.of(MatchResult.EndReason.IMMOBILIZED);
            }
        } else {
            observations.immobileSince = -1;
        }
        return Optional.empty();
    }

    private void start(long tick) {
        state = MatchState.ACTIVE;
        emit(tick, MatchEvent.Type.STARTED, null, 0, "Combat active");
    }

    private void finish(Optional<UUID> winner, MatchResult.EndReason reason, long tick, String detail) {
        result = new MatchResult(winner, reason, tick, scores, detail);
        state = MatchState.RESTORING;
        emit(tick, MatchEvent.Type.ENDED, winner.orElse(null), 0, reason + ": " + detail);
    }

    private boolean combatAccepts(long tick) {
        return state == MatchState.ACTIVE && tick >= activeFrom && tick < activeUntil;
    }

    private void clock(long tick) {
        if (tick < 0 || tick < lastTick) throw new IllegalArgumentException("Match time must be nonnegative and monotonic");
        lastTick = tick;
    }

    private void participant(UUID bot) {
        if (!firstBot.equals(bot) && !secondBot.equals(bot)) throw new IllegalArgumentException("Unknown match participant");
    }

    private UUID opponent(UUID bot) {
        return firstBot.equals(bot) ? secondBot : firstBot;
    }

    private void requireParticipants(UUID attacker, UUID victim) {
        participant(attacker);
        participant(victim);
        if (attacker.equals(victim)) throw new IllegalArgumentException("Self hits cannot score");
    }

    private void requireState(MatchState required) {
        if (state != required) throw new IllegalStateException("Expected " + required + ", was " + state);
    }

    private void emit(long tick, MatchEvent.Type type, UUID bot, double amount, String detail) {
        pendingEvents.add(new MatchEvent(++eventSequence, tick, type, Optional.ofNullable(bot), amount, detail));
    }

    private static boolean positiveFinite(double value) {
        return Double.isFinite(value) && value > 0;
    }

    private static void requireEvidenceKey(String key) {
        if (key == null || key.isBlank() || key.length() > 256) throw new IllegalArgumentException("Invalid component key");
    }

    private record FailedComponent(UUID victim, String componentKey) {}

    private static final class ObservationClock {
        private long disconnectedSince = -1;
        private long outsideSince = -1;
        private long immobileSince = -1;
        private long lastAggressionTick = -1;
        private long lastControlTick = -1;
        private double controlAwardedThisTick;
    }
}
