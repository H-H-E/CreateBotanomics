package education.poiesis.createbattlebots.compat.computercraft;

import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import education.poiesis.createbattlebots.block.BattleNodeEntity;
import education.poiesis.createbattlebots.server.BattleService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

/** Read-only match information. No Lua method can edit blocks or bypass a control lock. */
public final class BattleTransponderPeripheral implements IPeripheral {
    private final BattleNodeEntity node;
    private long cachedTick = Long.MIN_VALUE;
    private UUID cachedBot;
    private Map<String, Object> cached = Map.of();

    public BattleTransponderPeripheral(BattleNodeEntity node) {
        this.node = node;
    }

    @Override public String getType() { return "battle_transponder"; }
    @Override public Object getTarget() { return node; }
    @Override public boolean equals(IPeripheral other) {
        return other instanceof BattleTransponderPeripheral peripheral && peripheral.node == node;
    }

    private Map<String, Object> snapshot() {
        if (node.isRemoved() || !(node.getLevel() instanceof ServerLevel level) || node.bot() == null) {
            return Map.of("state", "practice", "timeRemaining", 0.0,
                    "myDamage", Map.of("health", node.health(), "maximumHealth", node.maximumHealth()),
                    "score", Map.of("damage", 0, "control", 0, "aggression", 0, "total", 0),
                    "recentHits", List.of(), "opponent", Map.of(), "arena", "");
        }
        // Every world read is main-thread scheduled. A script issuing several
        // methods in one tick receives one consistent, bounded-cost observation.
        long tick = level.getGameTime();
        if (tick != cachedTick || !node.bot().equals(cachedBot)) {
            cached = Map.copyOf(BattleService.get(level).telemetry(node.bot()));
            cachedTick = tick;
            cachedBot = node.bot();
        }
        return cached;
    }

    @LuaFunction(mainThread = true)
    public final String getState() { return String.valueOf(snapshot().getOrDefault("state", "practice")); }

    @LuaFunction(mainThread = true)
    public final double getTimeRemaining() {
        Object value = snapshot().get("timeRemaining");
        return value instanceof Number number ? Math.max(0, number.doubleValue()) : 0;
    }

    @LuaFunction(mainThread = true)
    public final Object getMyDamage() { return snapshot().getOrDefault("myDamage", Map.of()); }

    @LuaFunction(mainThread = true)
    public final Object getScore() { return snapshot().getOrDefault("score", Map.of()); }

    @LuaFunction(mainThread = true)
    public final Object getRecentHits() { return snapshot().getOrDefault("recentHits", List.of()); }

    @LuaFunction(mainThread = true)
    public final Object getOpponent() { return snapshot().getOrDefault("opponent", Map.of()); }

    @LuaFunction(mainThread = true)
    public final String getArena() { return String.valueOf(snapshot().getOrDefault("arena", "")); }
}
