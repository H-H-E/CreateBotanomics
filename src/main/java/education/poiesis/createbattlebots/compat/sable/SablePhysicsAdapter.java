package education.poiesis.createbattlebots.compat.sable;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.physics.callback.BlockSubLevelCollisionCallback;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.platform.SableEventPlatform;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelSerializer;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Original integration against the pinned Sable 2.0.4 public API.
 *
 * <p>All positions accepted by resolve/worldPosition/plotBlocks are Minecraft plot
 * coordinates; all positions returned in BodyInfo are world coordinates. Body IDs
 * are persistent UUIDs, never runtime IDs. Gameplay and restoration remain on the
 * logical server. The only collision callback operation is a bounded immutable
 * handoff. Native actor connections are followed with a fixed safety budget;
 * arbitrary third-party joints must be explicitly registered before a match.</p>
 */
public final class SablePhysicsAdapter {
    public static final int CONTACT_QUEUE_CAPACITY = 1024;
    private static final long MAX_CONTACT_AGE_NANOS = 2_000_000_000L;
    private static final AtomicBoolean LISTENERS_INSTALLED = new AtomicBoolean();
    private static final Map<ResourceKey<Level>, BoundedContactBuffer<RawContact>> CONTACTS = new ConcurrentHashMap<>();
    private static final Map<ResourceKey<Level>, Map<UUID, HeldPose>> FROZEN = new ConcurrentHashMap<>();
    private static final SubLevelLoadingTicketType<UUID> MATCH_LOADING = SubLevelLoadingTicketType.create(
            ResourceLocation.fromNamespaceAndPath("createbattlebots", "match"), UUIDUtil.CODEC);
    private static final Map<ServerLevel, Map<UUID, Set<UUID>>> LOADING_HOLDS = new IdentityHashMap<>();

    private static final BlockSubLevelCollisionCallback CONTACT_CALLBACK = (self, other, impact, velocity) -> {
        // Called by the physics engine: never read blocks, mutate worlds, or run match logic here.
        SubLevelPhysicsSystem system = SubLevelPhysicsSystem.getCurrentlySteppingSystem();
        if (system == null || other == null || !Double.isFinite(velocity) || velocity <= 0.0
                || !finite(impact.x, impact.y, impact.z)) {
            return BlockSubLevelCollisionCallback.CollisionResult.NONE;
        }
        ResourceKey<Level> dimension = system.getLevel().dimension();
        CONTACTS.computeIfAbsent(dimension, ignored -> new BoundedContactBuffer<>(CONTACT_QUEUE_CAPACITY))
                .offer(new RawContact(self.immutable(), other.immutable(),
                        new Vec3(impact.x, impact.y, impact.z), velocity, System.nanoTime()));
        // Do not remove the native collision or add artificial tangential movement.
        return BlockSubLevelCollisionCallback.CollisionResult.NONE;
    };

    public SablePhysicsAdapter() {
        if (LISTENERS_INSTALLED.compareAndSet(false, true)) {
            SableEventPlatform.INSTANCE.onPhysicsTick((system, step) -> holdFrozenBodies(system));
            SableEventPlatform.INSTANCE.onPostPhysicsTick((system, step) -> holdFrozenBodies(system));
        }
    }

    public static BlockSubLevelCollisionCallback collisionCallback() {
        return CONTACT_CALLBACK;
    }

    public Optional<UUID> resolve(ServerLevel level, BlockPos plotPosition) {
        SubLevel body = Sable.HELPER.getContaining(level, plotPosition);
        return body instanceof ServerSubLevel && !body.isRemoved()
                ? Optional.of(body.getUniqueId()) : Optional.empty();
    }

    /** Resolves entities stored in the body's native plot, including Create seats; never guesses by proximity. */
    public Optional<UUID> resolveEntity(ServerLevel level, Entity entity) {
        if (entity.level() != level || entity.isRemoved()) return Optional.empty();
        SubLevel body = Sable.HELPER.getContaining(entity);
        return body instanceof ServerSubLevel && !body.isRemoved()
                ? Optional.of(body.getUniqueId()) : Optional.empty();
    }

    public boolean loaded(ServerLevel level, UUID id) {
        return body(level, id) != null;
    }

    public Vec3 worldPosition(ServerLevel level, BlockPos plotPosition) {
        SubLevel body = Sable.HELPER.getContaining(level, plotPosition);
        Vec3 local = Vec3.atCenterOf(plotPosition);
        return body == null ? local : body.logicalPose().transformPosition(local);
    }

    public Optional<BodyInfo> bodyInfo(ServerLevel level, UUID id) {
        ServerSubLevel body = body(level, id);
        if (body == null) return Optional.empty();
        RigidBodyHandle handle = RigidBodyHandle.of(body);
        if (handle == null || !handle.isValid()) return Optional.empty();
        Vector3d position = body.logicalPose().position();
        Vector3d linear = handle.getLinearVelocity(new Vector3d());
        Vector3d angular = handle.getAngularVelocity(new Vector3d());
        if (body.getPlot().getBoundingBox() == null) return Optional.empty();
        body.updateBoundingBox();
        BoundingBox3dc bounds = body.boundingBox();
        double mass = body.getMassTracker().getMass();
        if (!finite(position.x, position.y, position.z, linear.x, linear.y, linear.z,
                angular.x, angular.y, angular.z, mass,
                bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ())) {
            return Optional.empty();
        }
        return Optional.of(new BodyInfo(id, vec(position), vec(linear), vec(angular), mass,
                new AABB(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ())));
    }

    /** Returns all occupied blocks in the body's own plot, or fails without a partial snapshot. */
    public List<BlockPos> plotBlocks(ServerLevel level, UUID id, int maxBlocks) {
        if (maxBlocks < 1 || maxBlocks > 1_000_000) throw new IllegalArgumentException("Invalid snapshot block budget");
        ServerSubLevel body = body(level, id);
        if (body == null) throw new IllegalStateException("Registered Sable body is unloaded or missing: " + id);
        List<BlockPos> result = new ArrayList<>();
        long inspected = 0;
        long scanBudget = Math.max(65_536L, Math.min(4_194_304L, maxBlocks * 256L));
        for (PlotChunkHolder chunk : body.getPlot().getLoadedChunks()) {
            BoundingBox3ic bounds = chunk.getBoundingBox();
            if (bounds == null) continue;
            int minY = Math.max(level.getMinBuildHeight(), bounds.minY());
            int maxY = Math.min(level.getMaxBuildHeight() - 1, bounds.maxY());
            for (int x = Math.max(0, bounds.minX()); x <= Math.min(15, bounds.maxX()); x++) {
                for (int z = Math.max(0, bounds.minZ()); z <= Math.min(15, bounds.maxZ()); z++) {
                    for (int y = minY; y <= maxY; y++) {
                        if (++inspected > scanBudget) {
                            throw new IllegalArgumentException("Machine exceeds the server's bounded snapshot scan budget");
                        }
                        BlockPos position = new BlockPos(chunk.getChunk().getPos().getMinBlockX() + x,
                                y, chunk.getChunk().getPos().getMinBlockZ() + z);
                        if (!chunk.getChunk().getBlockState(position).isAir()) {
                            if (result.size() == maxBlocks) {
                                throw new IllegalArgumentException("Machine exceeds the server's snapshot block budget of " + maxBlocks);
                            }
                            result.add(position);
                        }
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    /** Follows declared native actor connections, never proximity or the broader loading chain. */
    public Set<UUID> connectedBodies(ServerLevel level, UUID rootId) {
        return connectedBodies(level, rootId, 128);
    }

    public Set<UUID> connectedBodies(ServerLevel level, UUID rootId, int maximumBodies) {
        if (maximumBodies < 1 || maximumBodies > 128) throw new IllegalArgumentException("Connection body budget must be 1..128");
        ServerSubLevel root = body(level, rootId);
        if (root == null) throw new IllegalStateException("Load the entire machine before registration");
        Set<UUID> seen = new LinkedHashSet<>();
        ArrayDeque<ServerSubLevel> pending = new ArrayDeque<>();
        seen.add(rootId);
        pending.add(root);
        int inspectedActors = 0;
        int inspectedConnections = 0;
        while (!pending.isEmpty()) {
            ServerSubLevel current = pending.removeFirst();
            for (BlockEntitySubLevelActor actor : current.getPlot().getBlockEntityActors()) {
                if (++inspectedActors > 131_072) throw new IllegalArgumentException("Machine exceeds the native-connection scan budget");
                Iterable<SubLevel> connections = actor.sable$getConnectionDependencies();
                if (connections == null) continue;
                for (SubLevel dependency : connections) {
                    if (++inspectedConnections > 131_072) throw new IllegalArgumentException("Machine exceeds the native-connection scan budget");
                    if (!(dependency instanceof ServerSubLevel child) || child.getLevel() != level || child.isRemoved()) {
                        throw new IllegalStateException("A connected native body is unloaded or unavailable");
                    }
                    if (seen.add(child.getUniqueId())) {
                        if (seen.size() > maximumBodies) throw new IllegalArgumentException("Machine exceeds the connected-body safety limit");
                        pending.addLast(child);
                    }
                }
            }
        }
        return Set.copyOf(seen);
    }

    /** Keeps every registered native body resident during unattended/autonomous matches. */
    public boolean holdLoaded(ServerLevel level, Set<UUID> ids, UUID matchId) {
        if (ids.size() > 256) throw new IllegalArgumentException("Native loading hold exceeds the body safety limit");
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return false;
        List<ServerSubLevel> bodies = new ArrayList<>();
        for (UUID id : ids) {
            ServerSubLevel body = body(level, id);
            if (body == null) return false;
            bodies.add(body);
        }
        Set<UUID> added = new LinkedHashSet<>();
        try {
            for (ServerSubLevel body : bodies) {
                body.updateBoundingBox();
                if (container.addForceLoadTicket(body, MATCH_LOADING, matchId)) added.add(body.getUniqueId());
            }
        } catch (RuntimeException error) {
            for (ServerSubLevel body : bodies) {
                if (added.contains(body.getUniqueId())) container.removeForceLoadTicket(body, MATCH_LOADING, matchId);
            }
            throw error;
        }
        LOADING_HOLDS.computeIfAbsent(level, ignored -> new HashMap<>())
                .computeIfAbsent(matchId, ignored -> new LinkedHashSet<>()).addAll(ids);
        return true;
    }

    /** Releases only this match's ticket type/key; other mods' and operator tickets are preserved. */
    public void releaseLoaded(ServerLevel level, Set<UUID> ids, UUID matchId) {
        Set<UUID> release = new LinkedHashSet<>(ids);
        Map<UUID, Set<UUID>> matches = LOADING_HOLDS.get(level);
        if (matches != null) {
            Set<UUID> tracked = matches.remove(matchId);
            if (tracked != null) release.addAll(tracked);
            if (matches.isEmpty()) LOADING_HOLDS.remove(level);
        }
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return;
        for (UUID id : release) {
            ServerSubLevel body = body(level, id);
            if (body != null) container.removeForceLoadTicket(body, MATCH_LOADING, matchId);
        }
    }

    /** Drops only this mod's obsolete persisted tickets after a crash between completion and the next world save. */
    public int reconcileLoadedTickets(ServerLevel level, Set<UUID> pendingMatchIds) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return 0;
        int released = 0;
        for (var info : new ArrayList<>(container.getAllTickets().entrySet())) {
            ServerSubLevel body = body(level, info.getKey());
            if (body == null) continue;
            for (var ticket : new ArrayList<>(info.getValue().tickets())) {
                if (ticket.type().equals(MATCH_LOADING) && ticket.key() instanceof UUID matchId
                        && !pendingMatchIds.contains(matchId)) {
                    if (container.removeForceLoadTicket(body, MATCH_LOADING, matchId)) released++;
                }
            }
        }
        return released;
    }

    /**
     * Saves the native world, forces its storage to disk, and reads the registered bodies back.
     * This must run on the game thread while the restored group is held resident and frozen.
     * It verifies original IDs, exact serialized poses, every block state and all block entities
     * before the caller may clear its durable recovery marker. It never loads or clones a body.
     */
    public boolean verifiedSave(ServerLevel level, Set<UUID> ids) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Native recovery save must run on the server thread");
        if (ids.isEmpty() || ids.size() > 256) throw new IllegalArgumentException("Invalid native recovery body count");
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) throw new IllegalStateException("Sable storage is unavailable during recovery");
        Map<UUID, CompoundTag> expected = new HashMap<>();
        for (UUID id : ids) {
            ServerSubLevel body = body(level, id);
            if (body == null) throw new IllegalStateException("Cannot save missing registered body " + id);
            body.updateBoundingBox();
            expected.put(id, durableContent(SubLevelSerializer.toData(body, List.of()).fullTag()));
        }
        try {
            // Sable hooks this normal save and serializes native plots before Minecraft level data.
            level.save(null, true, false);
            var storage = container.getHoldingChunkMap().getStorage();
            // saveAll logs IO failures internally; this public flush propagates them instead.
            storage.flush();
            for (UUID id : ids) {
                ServerSubLevel body = body(level, id);
                if (body == null || body.getLastSerializationPointer() == null) {
                    throw new IllegalStateException("Native recovery save produced no durable pointer for " + id);
                }
                var pointer = body.getLastSerializationPointer();
                var holding = storage.attemptLoadHoldingChunk(pointer.chunkPos());
                var saved = storage.attemptLoadSubLevel(pointer.chunkPos(), pointer.local());
                if (holding == null || !holding.getSubLevelPointers().contains(pointer.local())
                        || saved == null || !saved.uuid().equals(id)
                        || !expected.get(id).equals(durableContent(saved.fullTag()))) {
                    throw new IllegalStateException("Native recovery save verification failed for body " + id
                            + "; the recovery marker must be retained");
                }
            }
            return true;
        } catch (java.io.IOException error) {
            throw new IllegalStateException("Native recovery storage could not be flushed; check server disk space and permissions", error);
        }
    }

    /** Lighting and scheduled tick queues may progress during a save; recovered machine content may not. */
    private static CompoundTag durableContent(CompoundTag nativeTag) {
        CompoundTag content = new CompoundTag();
        content.putUUID("uuid", nativeTag.getUUID("uuid"));
        content.put("pose", nativeTag.getCompound("pose").copy());
        CompoundTag sourcePlot = nativeTag.getCompound("plot");
        CompoundTag plot = new CompoundTag();
        for (String key : List.of("plot_x", "plot_z", "log_size", "biome", "data_version")) {
            Tag value = sourcePlot.get(key);
            if (value != null) plot.put(key, value.copy());
        }
        CompoundTag chunks = new CompoundTag();
        CompoundTag sourceChunks = sourcePlot.getCompound("chunks");
        for (String chunkKey : sourceChunks.getAllKeys()) {
            CompoundTag sourceChunk = sourceChunks.getCompound(chunkKey);
            CompoundTag chunk = new CompoundTag();
            CompoundTag sections = new CompoundTag();
            CompoundTag sourceSections = sourceChunk.getCompound("sections");
            for (String sectionKey : sourceSections.getAllKeys()) {
                sections.put(sectionKey, sourceSections.getCompound(sectionKey).getCompound("block_states").copy());
            }
            chunk.put("sections", sections);
            List<CompoundTag> entities = new ArrayList<>();
            for (Tag entity : sourceChunk.getList("block_entities", Tag.TAG_COMPOUND)) entities.add(((CompoundTag) entity).copy());
            entities.sort(Comparator.comparingInt((CompoundTag entity) -> entity.getInt("x"))
                    .thenComparingInt(entity -> entity.getInt("y")).thenComparingInt(entity -> entity.getInt("z")));
            ListTag orderedEntities = new ListTag();
            orderedEntities.addAll(entities);
            chunk.put("block_entities", orderedEntities);
            chunks.put(chunkKey, chunk);
        }
        plot.put("chunks", chunks);
        content.put("plot", plot);
        return content;
    }

    /** Versioned recovery pose for the same persistent body; never a body clone. */
    public CompoundTag capturePose(ServerLevel level, UUID id) {
        ServerSubLevel body = body(level, id);
        if (body == null) throw new IllegalStateException("Cannot snapshot missing body " + id);
        HeldPose pose = HeldPose.capture(body);
        if (!pose.valid()) throw new IllegalStateException("Cannot snapshot an invalid physics pose for " + id);
        CompoundTag tag = new CompoundTag();
        tag.putInt("version", 1);
        tag.putUUID("body", id);
        tag.putString("dimension", level.dimension().location().toString());
        tag.putDouble("x", pose.x);
        tag.putDouble("y", pose.y);
        tag.putDouble("z", pose.z);
        tag.putDouble("qx", pose.qx);
        tag.putDouble("qy", pose.qy);
        tag.putDouble("qz", pose.qz);
        tag.putDouble("qw", pose.qw);
        return tag;
    }

    /** Restores pose and clears velocities. Fails closed for mismatched or corrupt recovery data. */
    public boolean restorePose(ServerLevel level, UUID id, CompoundTag tag) {
        ServerSubLevel body = body(level, id);
        if (body == null || tag.getInt("version") != 1 || !tag.hasUUID("body")
                || !id.equals(tag.getUUID("body"))
                || !level.dimension().location().toString().equals(tag.getString("dimension"))) return false;
        for (String field : List.of("x", "y", "z", "qx", "qy", "qz", "qw")) {
            if (!tag.contains(field, Tag.TAG_DOUBLE)) return false;
        }
        HeldPose pose = HeldPose.read(tag);
        if (!pose.valid()) return false;
        SubLevelPhysicsSystem system = SubLevelPhysicsSystem.get(level);
        if (system == null) return false;
        Map<UUID, HeldPose> held = FROZEN.get(level.dimension());
        if (held != null && held.containsKey(id)) held.put(id, pose);
        restore(system, body, pose);
        return true;
    }

    /**
     * Observes native split lineage without guessing proximity-based ownership.
     * Existing explicitly registered child IDs are retained; only descendants are added.
     * Sable clears this marker after networking, so call while processing the physics
     * tick. This is not a persistent joint graph; the controlled-damage implementation
     * deliberately never removes structure blocks or causes native splitting.
     */
    public Set<UUID> findSplitBodies(ServerLevel level, Set<UUID> registeredIds) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        Set<UUID> result = new LinkedHashSet<>(registeredIds);
        if (container == null) return Set.copyOf(result);
        boolean changed;
        do {
            changed = false;
            for (ServerSubLevel candidate : container.getAllSubLevels()) {
                UUID parent = candidate.getSplitFromSubLevel();
                if (parent != null && result.contains(parent) && !candidate.isRemoved()) {
                    changed |= result.add(candidate.getUniqueId());
                }
            }
        } while (changed);
        return Set.copyOf(result);
    }

    /** Atomically validates all bodies before freezing them. Calling twice preserves the original held pose. */
    public boolean freeze(ServerLevel level, Set<UUID> ids) {
        SubLevelPhysicsSystem system = SubLevelPhysicsSystem.get(level);
        if (system == null) return false;
        Map<UUID, HeldPose> captures = new HashMap<>();
        for (UUID id : ids) {
            ServerSubLevel body = body(level, id);
            if (body == null) return false;
            HeldPose pose = HeldPose.capture(body);
            if (!pose.valid()) return false;
            captures.put(id, pose);
        }
        Map<UUID, HeldPose> frozen = FROZEN.computeIfAbsent(level.dimension(), ignored -> new ConcurrentHashMap<>());
        captures.forEach(frozen::putIfAbsent);
        holdFrozenBodies(system);
        return true;
    }

    /** Releases only the given registered bodies. They restart at rest, never with pre-freeze momentum. */
    public void unfreeze(ServerLevel level, Set<UUID> ids) {
        Map<UUID, HeldPose> frozen = FROZEN.get(level.dimension());
        if (frozen == null) return;
        ids.forEach(frozen::remove);
        if (frozen.isEmpty()) FROZEN.remove(level.dimension(), frozen);
    }

    public List<ImpactContact> drainContacts(ServerLevel level, int maximum) {
        if (maximum < 0 || maximum > CONTACT_QUEUE_CAPACITY) {
            throw new IllegalArgumentException("Contact budget must be between 0 and " + CONTACT_QUEUE_CAPACITY);
        }
        BoundedContactBuffer<RawContact> queue = CONTACTS.get(level.dimension());
        if (queue == null) return List.of();
        long tick = level.getGameTime();
        long now = System.nanoTime();
        List<ImpactContact> result = new ArrayList<>();
        for (RawContact contact : queue.drain(maximum)) {
            if (now - contact.capturedAtNanos <= MAX_CONTACT_AGE_NANOS) {
                result.add(new ImpactContact(contact.self, contact.other, contact.localImpact, contact.speed, tick));
            }
        }
        return List.copyOf(result);
    }

    public void discardContacts(ServerLevel level) {
        BoundedContactBuffer<RawContact> buffer = CONTACTS.get(level.dimension());
        if (buffer != null) buffer.clear();
    }

    public long droppedContacts(ServerLevel level) {
        BoundedContactBuffer<RawContact> buffer = CONTACTS.get(level.dimension());
        return buffer == null ? 0 : buffer.dropped();
    }

    /** Called on server shutdown so another integrated world cannot inherit a dimension's state. */
    public void clear() {
        for (var levelEntry : new ArrayList<>(LOADING_HOLDS.entrySet())) {
            for (var matchEntry : new ArrayList<>(levelEntry.getValue().entrySet())) {
                releaseLoaded(levelEntry.getKey(), matchEntry.getValue(), matchEntry.getKey());
            }
        }
        LOADING_HOLDS.clear();
        CONTACTS.clear();
        FROZEN.clear();
    }

    private static ServerSubLevel body(ServerLevel level, UUID id) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return null;
        SubLevel candidate = container.getSubLevel(id);
        return candidate instanceof ServerSubLevel server && !server.isRemoved() ? server : null;
    }

    private static void holdFrozenBodies(SubLevelPhysicsSystem system) {
        Map<UUID, HeldPose> frozen = FROZEN.get(system.getLevel().dimension());
        if (frozen == null) return;
        for (Map.Entry<UUID, HeldPose> entry : frozen.entrySet()) {
            ServerSubLevel body = body(system.getLevel(), entry.getKey());
            if (body != null) restore(system, body, entry.getValue());
        }
    }

    private static void restore(SubLevelPhysicsSystem system, ServerSubLevel body, HeldPose pose) {
        system.getPipeline().teleport(body, new Vector3d(pose.x, pose.y, pose.z),
                new Quaterniond(pose.qx, pose.qy, pose.qz, pose.qw).normalize());
        system.getPipeline().resetVelocity(body);
        system.updatePose(body);
        body.updateLastPose();
        body.updateBoundingBox();
    }

    private static Vec3 vec(Vector3d vector) {
        return new Vec3(vector.x, vector.y, vector.z);
    }

    private static boolean finite(double... values) {
        for (double value : values) if (!Double.isFinite(value)) return false;
        return true;
    }

    public record BodyInfo(UUID id, Vec3 position, Vec3 linearVelocity, Vec3 angularVelocity,
                           double mass, AABB bounds) { }

    /**
     * speed is Sable's native normal-speed magnitude in m/s, including rotational
     * point velocity. The pinned native callback takes the absolute value before
     * crossing JNI; it does not expose a normal or signed closing velocity. Callers
     * must retain per-surface cooldowns and must not add rotational speed again.
     */
    public record ImpactContact(BlockPos self, BlockPos other, Vec3 localImpact, double speed, long tick) { }

    private record RawContact(BlockPos self, BlockPos other, Vec3 localImpact, double speed, long capturedAtNanos) { }

    private record HeldPose(double x, double y, double z, double qx, double qy, double qz, double qw) {
        private static HeldPose capture(ServerSubLevel body) {
            Vector3d position = body.logicalPose().position();
            Quaterniond orientation = body.logicalPose().orientation();
            return new HeldPose(position.x, position.y, position.z,
                    orientation.x, orientation.y, orientation.z, orientation.w);
        }

        private static HeldPose read(CompoundTag tag) {
            return new HeldPose(tag.getDouble("x"), tag.getDouble("y"), tag.getDouble("z"),
                    tag.getDouble("qx"), tag.getDouble("qy"), tag.getDouble("qz"), tag.getDouble("qw"));
        }

        private boolean valid() {
            double norm = qx * qx + qy * qy + qz * qz + qw * qw;
            return finite(x, y, z, qx, qy, qz, qw) && Math.abs(x) <= 30_000_000
                    && Math.abs(z) <= 30_000_000 && Math.abs(y) <= 30_000_000
                    && norm > 0.999 && norm < 1.001;
        }
    }
}
