package education.poiesis.createbattlebots.core;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Server-side impact proxy with per-surface cooldown and a shared destruction budget.
 * Never interprets an impact as permission to mutate terrain: the caller must additionally verify
 * an active match, current body ownership, registered hit zone, and world-space arena containment.
 */
public final class DamageResolver {
    public record Settings(double energyPerDamage, double minimumClosingSpeed, double maxDamagePerHit,
                           int hitCooldownTicks, int maxBlocksPerTick, int maxContactKeys,
                           double maximumMass, double maximumSpeed) {
        public Settings {
            MatchRules.positive("energyPerDamage", energyPerDamage);
            MatchRules.positive("maxDamagePerHit", maxDamagePerHit);
            MatchRules.positive("maximumMass", maximumMass);
            MatchRules.positive("maximumSpeed", maximumSpeed);
            if (!Double.isFinite(minimumClosingSpeed) || minimumClosingSpeed < 0
                    || minimumClosingSpeed > maximumSpeed) {
                throw new IllegalArgumentException("Invalid minimumClosingSpeed");
            }
            if (hitCooldownTicks < 1 || hitCooldownTicks > 1200) {
                throw new IllegalArgumentException("hitCooldownTicks must be 1..1200");
            }
            if (maxBlocksPerTick < 1 || maxBlocksPerTick > 256) {
                throw new IllegalArgumentException("maxBlocksPerTick must be 1..256");
            }
            if (maxContactKeys < 2 || maxContactKeys > 65_536) {
                throw new IllegalArgumentException("maxContactKeys must be 2..65536");
            }
        }

        public static Settings defaults() {
            return new Settings(100, 0.2, 40, 10, 4, 4096, 1_000_000, 100);
        }
    }

    private final UUID firstBot;
    private final UUID secondBot;
    private final Settings settings;
    private final Map<ContactKey, Long> lastHit = new HashMap<>();
    private long lastTick = -1;
    private long budgetTick = -1;
    private int destroyedThisTick;
    private long lastPrune = -1;

    public DamageResolver(UUID firstBot, UUID secondBot, Settings settings) {
        this.firstBot = Objects.requireNonNull(firstBot, "firstBot");
        this.secondBot = Objects.requireNonNull(secondBot, "secondBot");
        this.settings = Objects.requireNonNull(settings, "settings");
        if (firstBot.equals(secondBot)) throw new IllegalArgumentException("Bots must differ");
    }

    public DamageResolver(UUID firstBot, UUID secondBot) {
        this(firstBot, secondBot, Settings.defaults());
    }

    /** Convenience for a bot with a single hit zone; normal speed is world metres per second. */
    public double process(UUID attacker, UUID target, long tick,
                          double normalSpeed, double mass, double toughness) {
        return process(attacker, target, "core", tick, normalSpeed, 0, mass, toughness, true);
    }

    /**
     * spinTipSpeed is the nonnegative approaching tip-speed contribution (omega times radius),
     * not an arbitrary signed angular velocity. Separating contact normals alone deal no damage.
     * Invalid/untrusted physics observations fail closed and do not consume cooldown or budget.
     */
    public double process(UUID attacker, UUID target, String componentKey, long tick,
                          double normalSpeed, double spinTipSpeed, double mass,
                          double toughness, boolean withinArena) {
        if (!opponents(attacker, target) || !withinArena || tick < 0 || tick < lastTick
                || componentKey == null || componentKey.isBlank() || componentKey.length() > 256) return 0;
        double damage = estimate(normalSpeed, spinTipSpeed, mass, toughness, settings);
        if (damage <= 0) return 0;
        lastTick = tick;
        prune(tick);
        ContactKey key = new ContactKey(attacker, target, componentKey);
        Long previous = lastHit.get(key);
        if (previous != null && tick - previous < settings.hitCooldownTicks()) return 0;
        if (previous == null && lastHit.size() >= settings.maxContactKeys()) return 0;
        lastHit.put(key, tick);
        return damage;
    }

    /** Reserve immediately before world edits. All victims and contacts share one tick budget. */
    public int claimDestruction(long tick, int requested) {
        if (requested < 0) throw new IllegalArgumentException("Requested count cannot be negative");
        if (tick < 0 || tick < lastTick) return 0;
        lastTick = tick;
        if (budgetTick != tick) {
            budgetTick = tick;
            destroyedThisTick = 0;
        }
        int allowed = Math.min(requested, settings.maxBlocksPerTick() - destroyedThisTick);
        destroyedThisTick += allowed;
        return allowed;
    }

    public int remainingDestructionBudget(long tick) {
        if (tick < 0 || tick < lastTick) return 0;
        return tick == budgetTick ? settings.maxBlocksPerTick() - destroyedThisTick : settings.maxBlocksPerTick();
    }

    public int trackedContactCount() { return lastHit.size(); }
    public Settings settings() { return settings; }

    /** Pure formula for tuning UI and tests; uses finite clamps before multiplication. */
    public static double estimate(double normalSpeed, double spinTipSpeed, double mass,
                                  double toughness, Settings settings) {
        Objects.requireNonNull(settings, "settings");
        if (!Double.isFinite(normalSpeed) || !Double.isFinite(spinTipSpeed)
                || !Double.isFinite(mass) || !Double.isFinite(toughness)
                || spinTipSpeed < 0 || mass <= 0 || toughness <= 0) return 0;
        double closing = Math.min(settings.maximumSpeed(), Math.max(0, normalSpeed));
        double spin = Math.min(settings.maximumSpeed(), spinTipSpeed);
        double speed = Math.min(settings.maximumSpeed(), closing + spin);
        if (speed < settings.minimumClosingSpeed() || speed == 0) return 0;
        double boundedMass = Math.min(settings.maximumMass(), mass);
        double energy = 0.5 * boundedMass * speed * speed;
        // Divide separately to avoid overflow from multiplying an extreme toughness value.
        double damage = (energy / settings.energyPerDamage()) / toughness;
        return Math.min(settings.maxDamagePerHit(), damage);
    }

    private boolean opponents(UUID attacker, UUID target) {
        return firstBot.equals(attacker) && secondBot.equals(target)
                || secondBot.equals(attacker) && firstBot.equals(target);
    }

    private void prune(long tick) {
        if (lastPrune >= 0 && tick - lastPrune < settings.hitCooldownTicks()
                && lastHit.size() < settings.maxContactKeys()) return;
        lastPrune = tick;
        lastHit.values().removeIf(previous -> tick - previous >= settings.hitCooldownTicks());
    }

    private record ContactKey(UUID attacker, UUID target, String component) {}
}
