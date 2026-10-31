package education.poiesis.createbattlebots.core;

/** All durations are server ticks, so a lagging server never silently shortens a round. */
public record MatchRules(
        int countdownTicks,
        int roundTicks,
        int disconnectGraceTicks,
        int immobilizationTicks,
        int ringOutGraceTicks,
        boolean ringOutEnabled,
        int aggressionCooldownTicks,
        double maxDamageScorePerFailure,
        double maxControlScorePerTick,
        double damageWeight,
        double controlWeight,
        double aggressionWeight) {

    public MatchRules {
        range("countdownTicks", countdownTicks, 0, 20 * 60);
        range("roundTicks", roundTicks, 1, 20 * 60 * 60);
        range("disconnectGraceTicks", disconnectGraceTicks, 0, 20 * 60 * 10);
        range("immobilizationTicks", immobilizationTicks, 1, 20 * 60 * 10);
        range("ringOutGraceTicks", ringOutGraceTicks, 0, 20 * 60);
        range("aggressionCooldownTicks", aggressionCooldownTicks, 1, 20 * 60);
        positive("maxDamageScorePerFailure", maxDamageScorePerFailure);
        positive("maxControlScorePerTick", maxControlScorePerTick);
        positive("damageWeight", damageWeight);
        positive("controlWeight", controlWeight);
        positive("aggressionWeight", aggressionWeight);
    }

    public static MatchRules defaults() {
        return new MatchRules(100, 3600, 300, 200, 20, true, 20, 10, 0.05, 3, 2, 1);
    }

    public MatchRules withTiming(int countdownTicks, int roundTicks) {
        return new MatchRules(countdownTicks, roundTicks, disconnectGraceTicks, immobilizationTicks,
                ringOutGraceTicks, ringOutEnabled, aggressionCooldownTicks, maxDamageScorePerFailure,
                maxControlScorePerTick, damageWeight, controlWeight, aggressionWeight);
    }

    static void positive(String name, double value) {
        if (!Double.isFinite(value) || value <= 0 || value > 1_000_000) {
            throw new IllegalArgumentException(name + " must be finite, positive and at most 1000000");
        }
    }

    private static void range(String name, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
        }
    }
}
