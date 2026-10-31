package education.poiesis.createbattlebots.config;

import education.poiesis.createbattlebots.core.MatchRules;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class BattleConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue ROUND_SECONDS, COUNTDOWN_SECONDS, MAX_BOT_BLOCKS,
            MAX_BODIES, MAX_CONTACTS, MAX_NODE_FAILURES, MAX_ARENA_SIDE, MAX_ARENAS, MAX_PRACTICE_SESSIONS;
    public static final ModConfigSpec.DoubleValue IMPACT_SCALE, MAX_HIT_DAMAGE;
    public static final ModConfigSpec.BooleanValue RING_OUT, AUTO_REPAIR, PRACTICE_ENABLED;
    static {
        var b = new ModConfigSpec.Builder();
        b.comment("Server-authoritative tournament rules. Changes apply to the next match.").push("match");
        ROUND_SECONDS = b.defineInRange("roundSeconds", 180, 10, 3600);
        COUNTDOWN_SECONDS = b.defineInRange("countdownSeconds", 5, 1, 60);
        RING_OUT = b.define("ringOut", true);
        AUTO_REPAIR = b.comment("Automatically repair and return both robots after every match.").define("automaticRepair", true);
        b.pop().push("safety");
        MAX_BOT_BLOCKS = b.comment("Computational safety ceiling per registered robot, not a competitive class limit.").defineInRange("maxBotBlocks", 8192, 8, 65536);
        MAX_BODIES = b.defineInRange("maxBodiesPerBot", 32, 1, 128);
        MAX_CONTACTS = b.defineInRange("maxContactsPerTick", 512, 8, 4096);
        MAX_NODE_FAILURES = b.defineInRange("maxNodeFailuresPerTick", 4, 1, 32);
        MAX_ARENA_SIDE = b.defineInRange("maxArenaSide", 256, 16, 1024);
        MAX_ARENAS = b.defineInRange("maxArenasPerDimension", 16, 1, 64);
        b.pop().push("damage");
        IMPACT_SCALE = b.comment("Energy proxy divided by this number and node toughness.").defineInRange("energyPerDamagePoint", 100.0, 1, 1000000);
        MAX_HIT_DAMAGE = b.defineInRange("maximumDamagePerHit", 40.0, 1, 1000);
        b.pop();
        b.push("practice");
        PRACTICE_ENABLED = b.comment("Enable the isolated starter workshop and solo AI arena.").define("enabled", true);
        MAX_PRACTICE_SESSIONS = b.comment("Each active practice session simulates twelve native bodies.").defineInRange("maxActiveSessions", 4, 1, 8);
        b.pop();
        SPEC = b.build();
    }

    public static MatchRules rules() {
        var d = MatchRules.defaults();
        return new MatchRules(COUNTDOWN_SECONDS.get() * 20, ROUND_SECONDS.get() * 20,
                d.disconnectGraceTicks(), d.immobilizationTicks(), d.ringOutGraceTicks(), RING_OUT.get(),
                d.aggressionCooldownTicks(), d.maxDamageScorePerFailure(), d.maxControlScorePerTick(),
                d.damageWeight(), d.controlWeight(), d.aggressionWeight());
    }
    private BattleConfig() {}
}
