package education.poiesis.createbattlebots.server;

import com.google.gson.Gson;
import education.poiesis.createbattlebots.CreateBattleBots;
import education.poiesis.createbattlebots.block.BattleBlocks;
import education.poiesis.createbattlebots.block.BattleNodeBlock;
import education.poiesis.createbattlebots.block.BattleNodeEntity;
import education.poiesis.createbattlebots.compat.sable.SablePhysicsAdapter;
import education.poiesis.createbattlebots.config.BattleConfig;
import education.poiesis.createbattlebots.core.*;
import education.poiesis.createbattlebots.data.*;
import education.poiesis.createbattlebots.repair.SnapshotStore;
import java.io.IOException;
import java.util.*;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/** All gameplay mutations run on the logical server; physics threads only enqueue contacts. */
public final class BattleService {
    private static final Map<ServerLevel, BattleService> SERVICES = new IdentityHashMap<>();
    private static final Gson JSON = new Gson();
    public static final String PILOT_RESTORE_MARKER = "createbattlebots:restore_pilot_mode";
    private static final TicketType<UUID> MATCH_TICKET = TicketType.create("createbattlebots", UUID::compareTo, 60);
    private final ServerLevel level;
    private final BattleSavedData data;
    private final SnapshotStore snapshots;
    private final SablePhysicsAdapter physics = new SablePhysicsAdapter();
    private final Map<String, RunningMatch> running = new LinkedHashMap<>();
    private final Map<UUID, UUID> bodyOwners = new HashMap<>();
    private boolean bootRecovery = true;
    private long lastTickNanos;

    private BattleService(ServerLevel level) {
        this.level = level; data = BattleSavedData.get(level); snapshots = new SnapshotStore(level); rebuildIndex();
    }
    public static BattleService get(ServerLevel level) { return SERVICES.computeIfAbsent(level, BattleService::new); }
    public static void clear() { SERVICES.values().forEach(s -> s.physics.clear()); SERVICES.clear(); }
    public static void shutdown() {
        for (var service : List.copyOf(SERVICES.values())) {
            for (var match : List.copyOf(service.running.values())) {
                match.engine.abort("Server stopping", service.now());
                if (match.engine.state() == MatchState.RESTORING) service.restore(match);
                service.releaseTickets(match);
            }
        }
        // Release native loading tickets before Sable serializes its worlds.
        SERVICES.values().forEach(service -> service.physics.clear());
    }
    public boolean isPilotLocked(UUID player) { return data.bots.values().stream().anyMatch(b -> b.owner.equals(player) && isLocked(b)); }
    public Collection<ArenaRecord> arenas() { return List.copyOf(data.arenas.values()); }
    public Collection<BotRecord> bots() { return List.copyOf(data.bots.values()); }
    public Optional<ArenaRecord> arenaAt(Vec3 pos) { return data.arenas.values().stream().filter(a -> a.contains(pos)).findFirst(); }

    public void createArena(ServerPlayer player, String name, BlockPos first, BlockPos second) {
        admin(player); validName(name);
        require(!data.arenas.containsKey(name), "That arena name is already in use.");
        require(data.arenas.size() < BattleConfig.MAX_ARENAS.get(), "This dimension has reached its arena safety limit.");
        var arena = new ArenaRecord(name, first, second); var bounds = arena.bounds();
        require(bounds.getXsize() >= 8 && bounds.getYsize() >= 4 && bounds.getZsize() >= 8, "Use an arena at least 8 × 4 × 8 blocks.");
        require(bounds.getXsize() <= BattleConfig.MAX_ARENA_SIDE.get() && bounds.getYsize() <= BattleConfig.MAX_ARENA_SIDE.get() && bounds.getZsize() <= BattleConfig.MAX_ARENA_SIDE.get(), "Arena exceeds the configured safety size.");
        require(first.getY() >= level.getMinBuildHeight() && second.getY() >= level.getMinBuildHeight() && first.getY() < level.getMaxBuildHeight() && second.getY() < level.getMaxBuildHeight(), "Arena must be inside the build height.");
        require(level.getWorldBorder().isWithinBounds(first) && level.getWorldBorder().isWithinBounds(second), "Arena must be inside the world border.");
        require(data.arenas.values().stream().noneMatch(a -> a.bounds().intersects(bounds)), "Arenas may not overlap.");
        data.arenas.put(name, arena); data.flush(level);
    }
    public void removeArena(ServerPlayer player, String name) {
        admin(player); idle(name); arena(name); data.arenas.remove(name); data.flush(level);
    }
    public BotRecord registerBot(ServerPlayer player, String name, BlockPos transponder, String machineClass) {
        validName(name); require(data.bots.values().stream().noneMatch(b -> b.name.equals(name)), "That robot name is already registered.");
        require(Set.of("ground", "aerial", "walking", "hybrid").contains(machineClass), "Class must be ground, aerial, walking, or hybrid.");
        near(player, transponder);
        require(level.getBlockState(transponder).is(BattleBlocks.TRANSPONDER.get()), "Select a Battle Transponder on an assembled machine.");
        var node = node(transponder);
        require(node.owner() == null || node.owner().equals(player.getUUID()) || player.hasPermissions(2), "This transponder belongs to another player.");
        UUID body = physics.resolve(level, transponder).orElseThrow(() -> new IllegalArgumentException("Assemble this machine with Simulated first, then register its transponder."));
        require(!bodyOwners.containsKey(body), "This body is already registered. Use bot attach for related bodies.");
        require(node.bot() == null || !data.bots.containsKey(node.bot()), "This transponder is already registered.");
        require(data.bots.size() < 4096, "Robot registry safety limit reached.");
        var bot = new BotRecord(UUID.randomUUID(), player.getUUID(), name, machineClass, body, transponder);
        bot.bodies.addAll(physics.connectedBodies(level, body, BattleConfig.MAX_BODIES.get()));
        require(bot.bodies.stream().noneMatch(bodyOwners::containsKey), "A connected body already belongs to another robot.");
        validateBodies(bot, false);
        data.bots.put(bot.id, bot); node.setOwner(player.getUUID()); node.setBot(bot.id); rebuildIndex(); data.flush(level);
        return bot;
    }
    public void attachBody(ServerPlayer player, String botName, BlockPos anyBlock) {
        var bot = bot(botName); own(player, bot); idle(bot); near(player, anyBlock);
        UUID body = physics.resolve(level, anyBlock).orElseThrow(() -> new IllegalArgumentException("That block is not part of an assembled body."));
        require(!bodyOwners.containsKey(body), "That body already belongs to a registered robot.");
        require(bot.bodies.size() < BattleConfig.MAX_BODIES.get(), "Robot body safety limit reached.");
        // Ownership of a separate native child is explicit; never infer it from proximity or collision.
        for (BlockPos pos : physics.plotBlocks(level, body, BattleConfig.MAX_BOT_BLOCKS.get())) {
            if (level.getBlockEntity(pos) instanceof BattleNodeEntity node && node.owner() != null)
                require(node.owner().equals(player.getUUID()) || player.hasPermissions(2), "A child node belongs to another player.");
            require(!level.getBlockState(pos).is(BattleBlocks.TRANSPONDER.get()), "A child body must not contain another transponder.");
        }
        bot.bodies.add(body);
        try { validateBodies(bot, false); } catch (RuntimeException error) { bot.bodies.remove(body); throw error; }
        rebuildIndex(); data.flush(level);
    }
    public void unregisterBot(ServerPlayer player, String name) {
        var bot = bot(name); own(player, bot); idle(bot);
        if (level.getBlockEntity(bot.transponder) instanceof BattleNodeEntity node && bot.id.equals(node.bot())) node.setBot(null);
        for (var arena : data.arenas.values()) { if (bot.id.equals(arena.first)) arena.first = null; if (bot.id.equals(arena.second)) arena.second = null; }
        data.bots.remove(bot.id); rebuildIndex(); data.flush(level);
    }
    public void join(ServerPlayer player, String arenaName, String botName) {
        var arena = arena(arenaName); var bot = bot(botName); own(player, bot); idle(arenaName); idle(bot);
        require(arena.contains(physics.worldPosition(level, bot.transponder)), "Move the robot into this arena before joining.");
        require(data.arenas.values().stream().noneMatch(a -> bot.id.equals(a.first) || bot.id.equals(a.second)), "This robot already occupies an arena slot. Leave it first.");
        if (arena.first == null) arena.first = bot.id; else if (arena.second == null) arena.second = bot.id; else throw new IllegalArgumentException("Both arena slots are occupied.");
        data.flush(level);
    }
    public void leave(ServerPlayer player, String arenaName, String botName) {
        var arena = arena(arenaName); var bot = bot(botName); own(player, bot); idle(arenaName);
        if (bot.id.equals(arena.first)) arena.first = null; else if (bot.id.equals(arena.second)) arena.second = null; else throw new IllegalArgumentException("That robot is not in this arena.");
        data.flush(level);
    }

    public void start(ServerPlayer player, String arenaName, ControlMode mode) {
        admin(player); var arena = arena(arenaName); idle(arenaName);
        require(arena.first != null && arena.second != null, "Register and join two different robots first.");
        var a = bot(arena.first); var b = bot(arena.second); idle(a); idle(b);
        for (ServerLevel other : level.getServer().getAllLevels()) {
            var service = get(other);
            for (var pending : service.data.pending.values()) {
                var firstOwner = service.bot(pending.getUUID("First")).owner; var secondOwner = service.bot(pending.getUUID("Second")).owner;
                require(!(a.owner.equals(firstOwner) || b.owner.equals(firstOwner)) && !(a.owner.equals(secondOwner) || b.owner.equals(secondOwner)), "A pilot is already assigned to another match.");
            }
        }
        validateBodies(a, true); validateBodies(b, true);
        for (var bot : List.of(a, b)) for (UUID body : bot.bodies) {
            var info = physics.bodyInfo(level, body).orElseThrow(() -> new IllegalArgumentException("A robot body is not loaded."));
            require(arena.bounds().contains(info.bounds().minX, info.bounds().minY, info.bounds().minZ)
                    && arena.bounds().contains(info.bounds().maxX - 0.001, info.bounds().maxY - 0.001, info.bounds().maxZ - 0.001), "Every registered body must start inside the arena.");
        }
        if (mode == ControlMode.HUMAN) for (var bot : List.of(a, b)) require(level.getServer().getPlayerList().getPlayer(bot.owner) != null, "Both human pilots must be online.");
        for (UUID first : a.bodies) for (UUID second : b.bodies) require(!physics.bodyInfo(level, first).orElseThrow().bounds().deflate(0.02).intersects(physics.bodyInfo(level, second).orElseThrow().bounds().deflate(0.02)), "Separate the two robots before starting.");
        UUID matchId = UUID.randomUUID();
        CompoundTag snapshot = capture(matchId, arena, a, b, mode);
        String hash;
        try { hash = snapshots.write(matchId, snapshot); } catch (IOException error) { throw failure("Could not persist the pre-match snapshot", error); }
        var pending = new CompoundTag(); pending.putUUID("Match", matchId); pending.putString("Arena", arenaName); pending.putString("Hash", hash);
        pending.putUUID("First", a.id); pending.putUUID("Second", b.id); pending.putString("Mode", mode.name());
        data.pending.put(arenaName, pending); data.flush(level); // Durable checkpoint precedes any match mutation.
        var match = new RunningMatch(arena, a, b, mode, matchId, snapshot);
        running.put(arenaName, match);
        try {
            require(physics.holdLoaded(level, match.bodies(), matchId), "Could not retain every native body for the match.");
            require(physics.freeze(level, match.bodies()), "Could not freeze every registered body.");
            if (mode == ControlMode.AUTONOMOUS) lockPilots(snapshot);
            physics.discardContacts(level); match.engine.begin(now()); refreshTickets(match);
        } catch (RuntimeException error) {
            match.engine.abort("Preparation failed", now()); match.engine.recoveryRequired(error.getMessage(), now());
            throw error;
        }
        announce(arena, "Prepare to fight: " + a.name + " vs " + b.name + " · " + mode.name().toLowerCase(Locale.ROOT));
    }
    public void stop(ServerPlayer player, String arenaName) { admin(player); var match = match(arenaName); match.engine.abort("Stopped by referee", now()); }
    public void recover(ServerPlayer player, String arenaName) {
        admin(player); require(data.pending.containsKey(arenaName), "This arena has no recovery pending.");
        var match = running.get(arenaName); if (match == null) match = loadRecovery(arenaName);
        if (match.engine.state() == MatchState.RECOVERY) match.engine.retryRestoration(now());
        else if (match.engine.state() != MatchState.RESTORING) match.engine.abort("Recovery requested by referee", now());
        restore(match);
    }
    public void surrender(ServerPlayer player) {
        for (var match : running.values()) for (var bot : List.of(match.a, match.b)) if (bot.owner.equals(player.getUUID()) && match.engine.locksRequired()) {
            match.engine.surrender(bot.id, now()); return;
        }
        throw new IllegalArgumentException("You are not piloting a current match.");
    }
    public void repairBot(ServerPlayer player, String name) {
        var bot = bot(name); own(player, bot); idle(bot);
        for (UUID body : bot.bodies) for (BlockPos pos : physics.plotBlocks(level, body, BattleConfig.MAX_BOT_BLOCKS.get())) {
            if (level.getBlockEntity(pos) instanceof BattleNodeEntity node) node.restore(node.maximumHealth());
        }
    }

    public void tick() {
        long start = System.nanoTime();
        if (bootRecovery || now() % 200 == 0) {
            Set<UUID> validTickets = new HashSet<>(); data.pending.values().forEach(record -> validTickets.add(record.getUUID("Match")));
            physics.reconcileLoadedTickets(level, validTickets);
        }
        if (bootRecovery) {
            bootRecovery = false;
            for (String name : List.copyOf(data.pending.keySet())) try { if (!running.containsKey(name)) loadRecovery(name); }
            catch (RuntimeException error) { CreateBattleBots.LOGGER.error("Battle Bots arena {} retains recovery locks: {}", name, error.getMessage()); }
        }
        List<SablePhysicsAdapter.ImpactContact> contacts = running.values().stream().anyMatch(match -> match.engine.state() == MatchState.ACTIVE)
                ? physics.drainContacts(level, Math.min(BattleConfig.MAX_CONTACTS.get(), SablePhysicsAdapter.CONTACT_QUEUE_CAPACITY)) : List.of();
        for (var match : List.copyOf(running.values())) {
            try {
                if (now() % 20 == 0) refreshTickets(match);
                MatchState before = match.engine.state();
                if (before == MatchState.ACTIVE) {
                    for (var contact : contacts) impact(match, contact);
                    awardDisplacement(match);
                }
                if (before == MatchState.ACTIVE || before == MatchState.COUNTDOWN) {
                    match.engine.tick(now(), observe(match.a, match.arena), observe(match.b, match.arena));
                    if (before == MatchState.COUNTDOWN && match.engine.state() == MatchState.ACTIVE) {
                        physics.unfreeze(level, match.bodies()); physics.discardContacts(level); announce(match.arena, "FIGHT!");
                    }
                }
                if (match.engine.state() == MatchState.RESTORING && (now() >= match.waitForBodiesUntil || match.bodies().stream().allMatch(id -> physics.loaded(level, id)))) {
                    if (BattleConfig.AUTO_REPAIR.get()) restore(match);
                    else { physics.freeze(level, match.bodies()); match.engine.recoveryRequired("Automatic repair is disabled. Referee: /battlebots recover " + match.arena.name, now()); }
                }
                for (var event : match.engine.drainEvents()) audit(match, event);
                flushAudit(match);
                if (now() % 20 == 0) hud(match);
                if (match.engine.state() == MatchState.FINISHED) running.remove(match.arena.name);
            } catch (RuntimeException error) {
                CreateBattleBots.LOGGER.error("Battle Bots halted arena {} safely", match.arena.name, error);
                if (match.engine.state() != MatchState.RECOVERY && match.engine.state() != MatchState.FINISHED) {
                    match.engine.abort("Integration fault", now());
                    if (match.engine.state() == MatchState.RESTORING) match.engine.recoveryRequired(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(), now());
                }
                physics.freeze(level, match.bodies());
                announce(match.arena, "Match paused for recovery. /battlebots recover " + match.arena.name);
            }
        }
        lastTickNanos = System.nanoTime() - start;
    }

    private void impact(RunningMatch match, SablePhysicsAdapter.ImpactContact contact) {
        UUID victimId = ownerAt(contact.self()); UUID attackerId = ownerAt(contact.other());
        if (victimId == null || attackerId == null || victimId.equals(attackerId) || !match.contains(victimId) || !match.contains(attackerId)) return;
        if (!(level.getBlockEntity(contact.self()) instanceof BattleNodeEntity node) || !(node.getBlockState().getBlock() instanceof BattleNodeBlock block) || node.health() <= 0) return;
        Vec3 point = physics.worldPosition(level, contact.self());
        if (!match.arena.contains(point) || !match.arena.contains(physics.worldPosition(level, contact.other()))) return;
        var attackerBody = physics.resolve(level, contact.other()).flatMap(id -> physics.bodyInfo(level, id));
        var victimBody = physics.resolve(level, contact.self()).flatMap(id -> physics.bodyInfo(level, id));
        if (attackerBody.isEmpty() || victimBody.isEmpty()) return;
        double firstMass = attackerBody.get().mass(), secondMass = victimBody.get().mass();
        double effectiveMass = firstMass > 0 && secondMass > 0 ? 1.0 / (1.0 / firstMass + 1.0 / secondMass) : 0;
        String component = Long.toString(contact.self().asLong());
        double damage = match.damage.process(attackerId, victimId, component, now(), contact.speed(), 0, effectiveMass, block.toughness(), true);
        if (damage <= 0) return;
        if (damage >= node.health() && match.damage.claimDestruction(now(), 1) == 0) damage = Math.max(0, node.health() - 0.01);
        if (damage <= 0) return;
        boolean failed = node.hurt(damage);
        if (failed) match.engine.recordComponentFailure(attackerId, victimId, component, block.kind() == BattleNodeBlock.Kind.CORE ? 10 : block.kind() == BattleNodeBlock.Kind.RELAY ? 6 : 3, now());
        // Award engagement only when the attacker's observed point velocity aims toward its opponent.
        Vec3 toward = victimBody.get().position().subtract(attackerBody.get().position());
        if (attackerBody.get().linearVelocity().dot(toward) > 0.05 || attackerBody.get().angularVelocity().lengthSqr() > 0.25) match.engine.recordEngagement(attackerId, victimId, now());
        var rootTarget = physics.bodyInfo(level, bot(victimId).rootBody);
        var rootAttacker = physics.bodyInfo(level, bot(attackerId).rootBody);
        if (rootTarget.isPresent() && rootAttacker.isPresent()) {
            Vec3 pushDirection = rootTarget.get().position().subtract(rootAttacker.get().position());
            match.pushes.putIfAbsent(victimId, new PushEvidence(attackerId, rootTarget.get().position(), pushDirection.lengthSqr() > 0.001 ? pushDirection.normalize() : Vec3.ZERO, now()));
        }
        Map<String, Object> hit = Map.of("tick", now(), "attacker", attackerId.toString(), "target", victimId.toString(), "damage", damage, "failed", failed, "speed", contact.speed());
        match.hits.addLast(hit); while (match.hits.size() > 32) match.hits.removeFirst();
        level.sendParticles(failed ? ParticleTypes.SMOKE : ParticleTypes.ELECTRIC_SPARK, point.x, point.y, point.z, failed ? 12 : 5, 0.2, 0.2, 0.2, 0.03);
        level.playSound(null, point.x, point.y, point.z, failed ? SoundEvents.ANVIL_BREAK : SoundEvents.ANVIL_LAND, SoundSource.BLOCKS, 0.35f, failed ? 1.4f : 1.8f);
        match.auditLines.add(JSON.toJson(Map.of("type", "impact", "evidence", hit)));
    }
    private void awardDisplacement(RunningMatch match) {
        Iterator<Map.Entry<UUID, PushEvidence>> iterator = match.pushes.entrySet().iterator();
        while (iterator.hasNext()) {
            var item = iterator.next(); var push = item.getValue();
            if (now() - push.tick < 5) continue;
            var target = physics.bodyInfo(level, bot(item.getKey()).rootBody);
            if (target.isPresent() && now() - push.tick <= 20) {
                double displaced = target.get().position().subtract(push.position).dot(push.direction);
                if (displaced > 0.05) match.engine.recordControl(push.attacker, Math.min(0.05, displaced * 0.05), now());
            }
            iterator.remove();
        }
    }
    private BotStatus observe(BotRecord bot, ArenaRecord arena) {
        boolean core = level.getBlockEntity(bot.transponder) instanceof BattleNodeEntity node && bot.id.equals(node.bot()) && node.health() > 0;
        boolean loaded = bot.bodies.stream().allMatch(id -> physics.bodyInfo(level, id).isPresent());
        boolean topology = physics.findSplitBodies(level, bot.bodies).equals(bot.bodies);
        if (loaded) for (UUID body : bot.bodies) topology &= bot.bodies.containsAll(physics.connectedBodies(level, body, BattleConfig.MAX_BODIES.get()));
        boolean connected = level.getServer().getPlayerList().getPlayer(bot.owner) != null;
        // Generic mod controls expose no reliable "commanded to move" flag. Never infer it from low speed.
        return new BotStatus(core, arena.contains(physics.worldPosition(level, bot.transponder)), connected, false, true, loaded && topology);
    }

    private CompoundTag capture(UUID matchId, ArenaRecord arena, BotRecord a, BotRecord b, ControlMode mode) {
        var snapshot = new CompoundTag(); snapshot.putInt("Version", 1); snapshot.putUUID("Match", matchId); snapshot.putString("Arena", arena.name);
        snapshot.putString("Dimension", level.dimension().location().toString()); snapshot.putString("Mode", mode.name());
        var bodies = new ListTag(); var nodes = new ListTag(); var blueprint = new ListTag();
        for (var bot : List.of(a, b)) for (UUID body : bot.bodies) {
            bodies.add(physics.capturePose(level, body));
            for (BlockPos pos : physics.plotBlocks(level, body, BattleConfig.MAX_BOT_BLOCKS.get())) {
                var block = new CompoundTag(); block.putLong("Pos", pos.asLong()); block.putUUID("Body", body);
                block.putString("Block", BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString());
                block.put("State", NbtUtils.writeBlockState(level.getBlockState(pos)));
                if (level.getBlockEntity(pos) != null) block.put("Data", level.getBlockEntity(pos).saveWithFullMetadata(level.registryAccess()));
                blueprint.add(block);
                if (level.getBlockEntity(pos) instanceof BattleNodeEntity node) { var n = new CompoundTag(); n.putLong("Pos", pos.asLong()); n.putUUID("Body", body); n.putString("Block", block.getString("Block")); n.putDouble("Health", node.health()); nodes.add(n); }
            }
        }
        snapshot.put("Bodies", bodies); snapshot.put("Nodes", nodes); snapshot.put("Blueprint", blueprint);
        var pilots = new ListTag(); Set<UUID> seen = new HashSet<>();
        for (var bot : List.of(a, b)) {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(bot.owner);
            if (player != null && seen.add(bot.owner)) { var p = new CompoundTag(); p.putUUID("Player", bot.owner); p.putInt("Mode", player.gameMode.getGameModeForPlayer().getId()); pilots.add(p); }
        }
        snapshot.put("Pilots", pilots); return snapshot;
    }
    private RunningMatch loadRecovery(String arenaName) {
        CompoundTag pending = data.pending.get(arenaName); require(pending != null, "Recovery reference is missing.");
        CompoundTag snapshot;
        try { snapshot = snapshots.read(pending.getUUID("Match"), pending.getString("Hash")); }
        catch (IOException error) { throw failure("Recovery snapshot could not be verified", error); }
        require(snapshot.getString("Dimension").equals(level.dimension().location().toString()), "Recovery dimension mismatch.");
        var match = new RunningMatch(arena(arenaName), bot(pending.getUUID("First")), bot(pending.getUUID("Second")), ControlMode.valueOf(pending.getString("Mode")), pending.getUUID("Match"), snapshot);
        match.engine.abort("Server restarted; combat was cancelled for recovery", now());
        match.waitForBodiesUntil = now() + 200;
        running.put(arenaName, match); refreshTickets(match);
        physics.holdLoaded(level, match.bodies(), match.engine.matchId()); physics.freeze(level, match.bodies()); return match;
    }
    private void restore(RunningMatch match) {
        physics.freeze(level, match.bodies());
        CompoundTag pending = data.pending.get(match.arena.name);
        Map<UUID, Integer> savedModes = new LinkedHashMap<>(data.playerModes);
        try {
            require(pending != null, "Recovery reference is missing; preserving the machine.");
            require(physics.holdLoaded(level, match.bodies(), match.engine.matchId()), "A registered native body is not loaded. Visit the saved staging area, then retry recovery.");
            CompoundTag original = snapshots.read(match.engine.matchId(), pending.getString("Hash"));
            require(physics.findSplitBodies(level, match.bodies()).equals(match.bodies()), "A native body split. Preserve the blueprint and repair its structure before retrying.");
            Map<UUID, Integer> expectedCounts = new HashMap<>();
            for (Tag entry : original.getList("Blueprint", Tag.TAG_COMPOUND)) {
                var block = (CompoundTag) entry; BlockPos pos = BlockPos.of(block.getLong("Pos"));
                expectedCounts.merge(block.getUUID("Body"), 1, Integer::sum);
                require(physics.resolve(level, pos).filter(block.getUUID("Body")::equals).isPresent(), "A registered body is missing or was rebuilt; automatic recovery stopped.");
                require(BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString().equals(block.getString("Block")), "A structural block changed outside Battle Bots. Recovery will not overwrite it.");
                var originalProperties = block.getCompound("State").getCompound("Properties");
                var currentProperties = NbtUtils.writeBlockState(level.getBlockState(pos)).getCompound("Properties");
                for (String property : List.of("facing", "horizontal_facing", "vertical_direction", "axis", "horizontal_axis", "axis_along_first_coordinate", "rotation", "half", "hinge", "part", "attachment", "face", "orientation")) {
                    if (originalProperties.contains(property)) require(originalProperties.getString(property).equals(currentProperties.getString(property)), "A block's mounting orientation changed; recovery will not silently undo external edits.");
                }
            }
            for (UUID body : match.bodies()) require(physics.plotBlocks(level, body, BattleConfig.MAX_BOT_BLOCKS.get()).size() == expectedCounts.getOrDefault(body, -1), "Robot block count changed. Recovery will not erase added blocks.");
            for (ServerPlayer player : level.players()) if (player.getUUID().equals(match.a.owner) || player.getUUID().equals(match.b.owner)) player.stopRiding();
            for (Tag entry : original.getList("Nodes", Tag.TAG_COMPOUND)) {
                var saved = (CompoundTag) entry; var node = node(BlockPos.of(saved.getLong("Pos"))); node.restore(saved.getDouble("Health"));
                require(Math.abs(node.health() - saved.getDouble("Health")) < 0.0001, "Node health verification failed.");
            }
            // Rebuilding a collapsed collider changes native mass/center of mass. Restore original
            // node shapes before the final pose, so that mass updates cannot shift the repaired bot.
            for (Tag entry : original.getList("Bodies", Tag.TAG_COMPOUND)) {
                var pose = (CompoundTag) entry;
                require(physics.restorePose(level, pose.getUUID("body"), pose), "A body pose could not be restored; keep the recovery snapshot.");
            }
            restorePilots(original);
            level.getServer().getPlayerList().saveAll();
            require(physics.verifiedSave(level, match.bodies()), "Native world storage could not verify the repaired bodies.");
            match.arena.lastResult = match.engine.result().map(result -> result.winner().map(id -> bot(id).name + " wins").orElse("Draw / stopped") + " · " + result.reason() + " · " + scoreText(match)).orElse("Recovered");
            data.pending.remove(match.arena.name);
            try { data.flush(level); } catch (RuntimeException error) { data.pending.put(match.arena.name, pending); data.playerModes.clear(); data.playerModes.putAll(savedModes); throw error; }
            physics.unfreeze(level, match.bodies()); physics.releaseLoaded(level, match.bodies(), match.engine.matchId()); releaseTickets(match); match.engine.restored(now());
            announce(match.arena, match.arena.lastResult + ". Both robots repaired and returned.");
        } catch (IOException | RuntimeException error) {
            if (pending != null) data.pending.put(match.arena.name, pending);
            data.playerModes.clear(); data.playerModes.putAll(savedModes);
            if (match.engine.state() == MatchState.RESTORING) match.engine.recoveryRequired(error.getMessage() == null ? "Recovery verification failed" : error.getMessage(), now());
            announce(match.arena, "Recovery paused: " + match.engine.recoveryReason() + " Referee: /battlebots recover " + match.arena.name);
            CreateBattleBots.LOGGER.error("Recovery retained for {}: {}", match.arena.name, error.getMessage());
        }
    }
    private void lockPilots(CompoundTag snapshot) {
        for (Tag entry : snapshot.getList("Pilots", Tag.TAG_COMPOUND)) {
            var p = (CompoundTag) entry; data.playerModes.put(p.getUUID("Player"), p.getInt("Mode"));
        }
        data.flush(level);
        for (Tag entry : snapshot.getList("Pilots", Tag.TAG_COMPOUND)) {
            var pilot = (CompoundTag) entry; UUID id = pilot.getUUID("Player");
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(id);
            if (player != null) {
                var marker = new CompoundTag(); marker.putInt("Mode", pilot.getInt("Mode")); marker.putUUID("Match", snapshot.getUUID("Match"));
                player.getPersistentData().put(PILOT_RESTORE_MARKER, marker);
                player.closeContainer(); player.stopUsingItem(); player.stopRiding(); player.setGameMode(GameType.SPECTATOR);
            }
        }
    }
    private void restorePilots(CompoundTag snapshot) {
        if (!"AUTONOMOUS".equals(snapshot.getString("Mode"))) return;
        for (Tag entry : snapshot.getList("Pilots", Tag.TAG_COMPOUND)) {
            var p = (CompoundTag) entry; UUID id = p.getUUID("Player");
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(id);
            if (player != null) {
                player.setGameMode(GameType.byId(p.getInt("Mode")));
                player.getPersistentData().remove(PILOT_RESTORE_MARKER);
                data.playerModes.remove(id);
            }
        }
    }
    public void playerJoined(ServerPlayer player) {
        Integer previous = data.playerModes.get(player.getUUID());
        if (isAutonomousPilot(player)) { player.stopRiding(); player.setGameMode(GameType.SPECTATOR); return; }
        var marker = player.getPersistentData().getCompound(PILOT_RESTORE_MARKER);
        if (marker.contains("Mode", Tag.TAG_INT)) previous = marker.getInt("Mode");
        if (previous == null) return;
        require(previous >= 0 && previous <= 3, "Invalid saved pilot game mode; contact the server operator.");
        player.setGameMode(GameType.byId(previous)); player.getPersistentData().remove(PILOT_RESTORE_MARKER);
        data.playerModes.remove(player.getUUID()); data.flush(level);
    }

    public boolean isProtected(BlockPos pos) {
        UUID owner = ownerAt(pos);
        if (owner != null && isLocked(bot(owner))) return true;
        Vec3 world = physics.worldPosition(level, pos);
        return data.pending.keySet().stream().map(data.arenas::get).filter(Objects::nonNull).anyMatch(a -> a.contains(world));
    }
    public boolean canInteract(ServerPlayer player, BlockPos pos) {
        if (isAutonomousPilot(player)) return false;
        UUID id = ownerAt(pos);
        if (id == null || !isLocked(bot(id))) {
            if (!isProtected(pos)) return true;
            return level.getBlockState(pos).is(BattleBlocks.GARAGE.get()) || level.getBlockState(pos).is(BattleBlocks.ARENA_CONTROLLER.get());
        }
        var bot = bot(id);
        if (!bot.owner.equals(player.getUUID()) || isAutonomousPilot(player)) return false;
        var current = running.values().stream().filter(m -> m.contains(id)).findFirst().orElse(null);
        if (current == null || current.engine.state() != MatchState.ACTIVE) return false;
        var allowed = net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.BLOCK,
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(CreateBattleBots.MOD_ID, "allowed_controls"));
        if (!level.getBlockState(pos).is(allowed)) return false;
        if (!player.getMainHandItem().isEmpty() || !player.getOffhandItem().isEmpty()) return false;
        // Stock control blocks remain usable; inventories cannot be opened during a match.
        return !(level.getBlockEntity(pos) instanceof net.minecraft.world.Container)
                && level.getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, pos, null) == null;
    }
    public boolean canUseEntity(ServerPlayer player, net.minecraft.world.entity.Entity target) {
        if (isAutonomousPilot(player)) return false;
        UUID owner = physics.resolveEntity(level, target).map(bodyOwners::get).orElse(null);
        if (owner == null || !isLocked(bot(owner))) return !isProtected(target.blockPosition());
        var match = running.values().stream().filter(m -> m.contains(owner)).findFirst().orElse(null);
        return bot(owner).owner.equals(player.getUUID()) && match != null && match.engine.state() == MatchState.ACTIVE;
    }
    public boolean isAutonomousPilot(ServerPlayer player) {
        for (ServerLevel other : level.getServer().getAllLevels()) {
            var registry = get(other).data;
            for (var pending : registry.pending.values()) if ("AUTONOMOUS".equals(pending.getString("Mode"))) {
                BotRecord a = registry.bots.get(pending.getUUID("First")), b = registry.bots.get(pending.getUUID("Second"));
                if (a != null && a.owner.equals(player.getUUID()) || b != null && b.owner.equals(player.getUUID())) return true;
            }
        }
        return false;
    }
    public Component status(String arenaName) {
        var arena = arena(arenaName); var match = running.get(arenaName);
        String slots = label(arena.first) + " vs " + label(arena.second);
        if (match == null) return Component.literal(arena.name + " · " + (data.pending.containsKey(arenaName) ? "RECOVERY REQUIRED" : "ready") + "\n" + slots + "\n" + arena.lastResult);
        return Component.literal(arena.name + " · " + match.engine.state() + " · " + Math.ceil(match.engine.timeRemainingTicks(now()) / 20.0) + "s\n" + slots + "\n" + scoreText(match) + (match.engine.state() == MatchState.RECOVERY ? "\n" + match.engine.recoveryReason() : ""));
    }
    public int[] consoleData(BlockPos pos) {
        var arena = arenaAt(Vec3.atCenterOf(pos)); if (arena.isEmpty()) return new int[]{0, 0, 0, 0};
        var a = arena.get(); var match = running.get(a.name);
        return new int[]{match == null ? data.pending.containsKey(a.name) ? MatchState.RECOVERY.ordinal() + 1 : 0 : match.engine.state().ordinal() + 1,
                match == null ? 0 : (int) Math.ceil((match.engine.state() == MatchState.COUNTDOWN ? match.engine.countdownRemainingTicks(now()) : match.engine.timeRemainingTicks(now())) / 20.0),
                (a.first == null ? 0 : 1) + (a.second == null ? 0 : 1), 1};
    }
    public void inspectNode(ServerPlayer player, BlockPos pos) {
        var node = node(pos); player.sendSystemMessage(Component.literal(String.format(Locale.ROOT, "Battle node · %.0f / %.0f integrity%s", node.health(), node.maximumHealth(), node.health() <= 0 ? " · DISABLED" : "")));
        UUID owner = ownerAt(pos);
        if (owner != null) player.sendSystemMessage(Component.literal("Robot: " + bot(owner).name + " · " + bot(owner).machineClass));
        else if (level.getBlockState(pos).is(BattleBlocks.TRANSPONDER.get())) player.sendSystemMessage(Component.literal("[Register this assembled robot]").withStyle(style -> style.withColor(ChatFormatting.AQUA).withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND,
                "/battlebots bot register mybot " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " ground"))));
    }
    public void showHelp(ServerPlayer player) {
        player.sendSystemMessage(Component.literal("Create: Battle Bots\n1. Build with Create / Simulated and add one Battle Transponder. Add Combat Armor and Damage Relays as hit zones. A failed relay outputs 15: connect it to a clutch.\n2. Assemble, then /battlebots bot register <name> <x> <y> <z> <ground|aerial|walking|hybrid>. Attach every jointed child with bot attach.\n3. Referee: /battlebots arena create <name> <from> <to>. Both pilots: /battlebots join <arena> <bot>.\n4. Place a Garage Console inside the arena, open it and start a human or autonomous match.\n5. /battlebots surrender ends your match; both machines repair automatically. /battlebots diagnostics checks the setup.").withStyle(ChatFormatting.GRAY));
    }
    public String diagnostics() {
        return "Create: Battle Bots 0.2.0 · NeoForge 1.21.1 · Sable hit-zone API\n" + data.arenas.size() + " arenas, " + data.bots.size() + " robots, " + data.pending.size() + " active/recovery records\n"
                + String.format(Locale.ROOT, "Last gameplay tick %.3f ms; dropped collision observations %d\n", lastTickNanos / 1_000_000.0, physics.droppedContacts(level))
                + "Damage targets: transponders, combat armor and damage relays. Related bodies require explicit attach. Ordinary machinery, joints and inventories remain native.";
    }
    public Map<String, Object> telemetry(UUID botId) {
        BotRecord bot = data.bots.get(botId); if (bot == null) return Map.of("state", "unregistered", "timeRemaining", 0.0, "myDamage", Map.of(), "score", Map.of(), "recentHits", List.of(), "opponent", Map.of(), "arena", "");
        RunningMatch match = running.values().stream().filter(m -> m.contains(botId)).findFirst().orElse(null);
        double health = level.getBlockEntity(bot.transponder) instanceof BattleNodeEntity node ? node.health() : 0;
        if (match == null) return Map.of("state", "practice", "timeRemaining", 0.0, "myDamage", Map.of("coreIntegrity", health), "score", Map.of("damage", 0, "control", 0, "aggression", 0, "total", 0), "recentHits", List.of(), "opponent", Map.of(), "arena", "");
        var score = match.engine.score(botId); BotRecord opponent = match.a.id.equals(botId) ? match.b : match.a;
        return Map.of("state", match.engine.state().name().toLowerCase(Locale.ROOT), "timeRemaining", match.engine.timeRemainingTicks(now()) / 20.0,
                "myDamage", Map.of("coreIntegrity", health), "score", Map.of("damage", score.damage(), "control", score.control(), "aggression", score.aggression(), "total", score.total(match.engine.rules())),
                "recentHits", List.copyOf(match.hits), "opponent", Map.of("name", opponent.name, "id", opponent.id.toString()), "arena", match.arena.name);
    }

    private void validateBodies(BotRecord bot, boolean requireHealthy) {
        require(bot.bodies.size() <= BattleConfig.MAX_BODIES.get(), "Robot has too many registered bodies.");
        int blocks = 0, cores = 0;
        for (UUID body : bot.bodies) {
            require(physics.bodyInfo(level, body).isPresent(), "A registered body is unloaded or missing. Reassemble and register again if its identity changed.");
            require(bot.bodies.containsAll(physics.connectedBodies(level, body, BattleConfig.MAX_BODIES.get())), "A connected child body is not registered. Attach it before starting a match.");
            var positions = physics.plotBlocks(level, body, BattleConfig.MAX_BOT_BLOCKS.get()); blocks += positions.size();
            require(blocks <= BattleConfig.MAX_BOT_BLOCKS.get(), "Robot exceeds the total block safety budget.");
            for (BlockPos pos : positions) {
                if (level.getBlockState(pos).is(BattleBlocks.TRANSPONDER.get())) {
                    cores++; require(pos.equals(bot.transponder), "Use exactly one transponder per robot.");
                    if (data.bots.containsKey(bot.id)) {
                        var core = node(pos); require(bot.id.equals(core.bot()) && bot.owner.equals(core.owner()), "The registered core was replaced or changed owner; register the rebuilt robot again.");
                    }
                }
                if (requireHealthy && level.getBlockEntity(pos) instanceof BattleNodeEntity node) require(node.health() == node.maximumHealth(), "Repair all combat nodes before starting.");
            }
        }
        require(cores == 1, "A robot must contain exactly one Battle Transponder.");
    }
    private void refreshTickets(RunningMatch match) {
        Set<ChunkPos> needed = new HashSet<>();
        for (var bot : List.of(match.a, match.b)) physics.bodyInfo(level, bot.rootBody).ifPresent(info -> needed.add(new ChunkPos(BlockPos.containing(info.position()))));
        for (Tag entry : match.snapshot.getList("Bodies", Tag.TAG_COMPOUND)) { var p = (CompoundTag) entry; needed.add(new ChunkPos(BlockPos.containing(p.getDouble("x"), p.getDouble("y"), p.getDouble("z")))); }
        for (ChunkPos old : match.tickets) if (!needed.contains(old)) level.getChunkSource().removeRegionTicket(MATCH_TICKET, old, 2, match.engine.matchId());
        needed.forEach(pos -> level.getChunkSource().addRegionTicket(MATCH_TICKET, pos, 2, match.engine.matchId())); match.tickets = needed;
    }
    private void releaseTickets(RunningMatch match) { match.tickets.forEach(pos -> level.getChunkSource().removeRegionTicket(MATCH_TICKET, pos, 2, match.engine.matchId())); match.tickets.clear(); }
    private void audit(RunningMatch match, MatchEvent event) {
        match.auditLines.add(JSON.toJson(Map.of("sequence", event.sequence(), "tick", event.tick(), "type", event.type().name(), "bot", event.botId().map(UUID::toString).orElse(""), "amount", event.amount(), "detail", event.detail())));
    }
    private void flushAudit(RunningMatch match) {
        if (match.auditFailed || match.auditLines.isEmpty()) return;
        try { snapshots.audit(match.engine.matchId(), String.join("\n", match.auditLines)); match.auditLines.clear(); }
        catch (IOException error) {
            match.auditFailed = true;
            if (match.engine.state() != MatchState.FINISHED) throw failure("Match audit could not be saved", error);
            CreateBattleBots.LOGGER.error("Repaired match finished but final audit write failed", error);
        }
    }
    private void hud(RunningMatch match) {
        if (match.engine.state() == MatchState.FINISHED) return;
        String phase = match.engine.state() == MatchState.COUNTDOWN ? "Starts in " + (match.engine.countdownRemainingTicks(now()) + 19) / 20 : match.engine.state().name() + " · " + (match.engine.timeRemainingTicks(now()) + 19) / 20 + "s";
        Component text = Component.literal(shortName(match.a.name) + " " + Math.round(match.engine.score(match.a.id).total(match.engine.rules()))
                + " : " + Math.round(match.engine.score(match.b.id).total(match.engine.rules())) + " " + shortName(match.b.name) + "  |  " + phase).withStyle(ChatFormatting.GOLD);
        for (ServerPlayer player : level.players()) if (match.arena.bounds().inflate(24).contains(player.position()) || player.getUUID().equals(match.a.owner) || player.getUUID().equals(match.b.owner)) player.displayClientMessage(text, true);
    }
    private void announce(ArenaRecord arena, String text) {
        for (ServerPlayer player : level.players()) if (arena.bounds().inflate(64).contains(player.position()) || player.hasPermissions(2)) player.sendSystemMessage(Component.literal("[Battle Bots] " + text).withStyle(ChatFormatting.AQUA));
    }
    private String scoreText(RunningMatch match) {
        var a = match.engine.score(match.a.id); var b = match.engine.score(match.b.id);
        return String.format(Locale.ROOT, "D/C/A %.0f/%.2f/%.0f vs %.0f/%.2f/%.0f", a.damage(), a.control(), a.aggression(), b.damage(), b.control(), b.aggression());
    }
    private static String shortName(String name) { return name.length() <= 14 ? name : name.substring(0, 13) + "…"; }
    private void rebuildIndex() { bodyOwners.clear(); for (var bot : data.bots.values()) for (UUID body : bot.bodies) require(bodyOwners.put(body, bot.id) == null, "Duplicate body ownership in saved data; repair registry before running matches."); }
    private UUID ownerAt(BlockPos pos) { return physics.resolve(level, pos).map(bodyOwners::get).orElse(null); }
    private boolean isLocked(BotRecord bot) { return data.pending.values().stream().anyMatch(p -> bot.id.equals(p.getUUID("First")) || bot.id.equals(p.getUUID("Second"))); }
    private void idle(BotRecord bot) { require(!isLocked(bot), "That robot is locked to an active match or recovery."); }
    private void idle(String arenaName) { require(!data.pending.containsKey(arenaName), "This arena has an active match or pending recovery."); }
    private ArenaRecord arena(String name) { var a = data.arenas.get(name); require(a != null, "Unknown arena: " + name); return a; }
    private BotRecord bot(String name) { return data.bots.values().stream().filter(b -> b.name.equals(name)).findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown robot: " + name)); }
    private BotRecord bot(UUID id) { var bot = data.bots.get(id); require(bot != null, "Registered robot is missing: " + id); return bot; }
    private RunningMatch match(String arenaName) { var match = running.get(arenaName); require(match != null, "No running match in " + arenaName); return match; }
    private BattleNodeEntity node(BlockPos pos) { require(level.getBlockEntity(pos) instanceof BattleNodeEntity, "Battle node is missing."); return (BattleNodeEntity) level.getBlockEntity(pos); }
    private String label(UUID id) { return id == null ? "[empty]" : data.bots.containsKey(id) ? bot(id).name : "[missing robot]"; }
    private void near(ServerPlayer player, BlockPos pos) { require(player.serverLevel() == level && player.position().distanceToSqr(physics.worldPosition(level, pos)) <= 64, "Stand within 8 blocks of the selected part."); }
    private static void admin(ServerPlayer player) { require(player.hasPermissions(2), "Only an operator can referee matches or define arenas."); }
    private static void own(ServerPlayer player, BotRecord bot) { require(bot.owner.equals(player.getUUID()) || player.hasPermissions(2), "Only the owner or an operator can change this robot."); }
    private static void validName(String name) { require(name.matches("[a-z0-9_-]{1,32}"), "Use 1–32 lowercase letters, digits, underscores or hyphens."); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
    private static IllegalStateException failure(String message, Exception cause) { return new IllegalStateException(message + ": " + cause.getMessage(), cause); }
    private long now() { return level.getGameTime(); }
    private record PushEvidence(UUID attacker, Vec3 position, Vec3 direction, long tick) {}
    private static final class RunningMatch {
        final ArenaRecord arena; final BotRecord a, b; final MatchEngine engine; final DamageResolver damage; final CompoundTag snapshot;
        final Deque<Map<String, Object>> hits = new ArrayDeque<>(); final Map<UUID, PushEvidence> pushes = new HashMap<>(); Set<ChunkPos> tickets = new HashSet<>();
        final List<String> auditLines = new ArrayList<>(); boolean auditFailed; long waitForBodiesUntil;
        RunningMatch(ArenaRecord arena, BotRecord a, BotRecord b, ControlMode mode, UUID id, CompoundTag snapshot) {
            this.arena = arena; this.a = a; this.b = b; this.snapshot = snapshot;
            engine = new MatchEngine(id, a.id, b.id, mode, BattleConfig.rules());
            var d = DamageResolver.Settings.defaults();
            damage = new DamageResolver(a.id, b.id, new DamageResolver.Settings(BattleConfig.IMPACT_SCALE.get(), d.minimumClosingSpeed(), BattleConfig.MAX_HIT_DAMAGE.get(), d.hitCooldownTicks(), BattleConfig.MAX_NODE_FAILURES.get(), d.maxContactKeys(), d.maximumMass(), d.maximumSpeed()));
        }
        Set<UUID> bodies() { Set<UUID> ids = new LinkedHashSet<>(a.bodies); ids.addAll(b.bodies); return ids; }
        boolean contains(UUID id) { return a.id.equals(id) || b.id.equals(id); }
    }
}
