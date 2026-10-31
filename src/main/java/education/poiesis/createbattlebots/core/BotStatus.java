package education.poiesis.createbattlebots.core;

/** A single authoritative observation, sampled for BOTH bots before adjudicating a tick. */
public record BotStatus(
        boolean coreIntact,
        boolean insideArena,
        boolean connected,
        boolean movementCommanded,
        boolean propulsionWorking,
        boolean assemblyValid) {

    public static BotStatus healthy() {
        return new BotStatus(true, true, true, false, true, true);
    }
}
