package education.poiesis.createbattlebots.core;

/** Immutable judge totals; consumers can display every factor rather than only an opaque total. */
public record MatchScore(double damage, double control, double aggression) {
    public MatchScore {
        if (!finiteNonnegative(damage) || !finiteNonnegative(control) || !finiteNonnegative(aggression)) {
            throw new IllegalArgumentException("Score factors must be finite and nonnegative");
        }
    }

    public static MatchScore zero() {
        return new MatchScore(0, 0, 0);
    }

    public double total(MatchRules rules) {
        return damage * rules.damageWeight() + control * rules.controlWeight()
                + aggression * rules.aggressionWeight();
    }

    private static boolean finiteNonnegative(double value) {
        return Double.isFinite(value) && value >= 0;
    }
}
