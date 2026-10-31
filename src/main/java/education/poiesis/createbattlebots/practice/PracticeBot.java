package education.poiesis.createbattlebots.practice;

import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.physics.constraint.RotaryConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.RotaryConstraintHandle;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import education.poiesis.createbattlebots.block.BattleBlocks;
import education.poiesis.createbattlebots.compat.sable.SablePhysicsAdapter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import java.util.*;

/** Our starter machines only. We never sever or recreate an unknown third-party joint. */
public final class PracticeBot {
    public static final String OWNER_TAG = "createbattlebots_practice_owner";
    public static final SubLevelLoadingTicketType<UUID> TICKET = SubLevelLoadingTicketType.create(
            ResourceLocation.fromNamespaceAndPath("createbattlebots", "practice"), UUIDUtil.CODEC);
    public static final int SPINNER = 0, SAW = 1, CRAWLER = 2;
    public final ServerLevel level;
    public final UUID owner;
    public final int design;
    public final List<Part> parts = new ArrayList<>();
    public final SablePhysicsAdapter physics = new SablePhysicsAdapter();
    public double coreHealth = 100, weaponSpeed;
    private int tune;
    private long lastCoreHit = -100;

    public static final class Part {
        public final ServerSubLevel body;
        public final String name;
        public final Vector3d offset, axis;
        public final boolean weapon;
        public final int side;
        public final CompoundTag pose;
        public final int blockCount;
        public Map<BlockPos, net.minecraft.world.level.block.state.BlockState> structure = Map.of();
        public RotaryConstraintHandle joint;
        public double health = 65;
        public long lastHit = -100;
        Part(ServerSubLevel body, String name, Vector3d offset, Vector3d axis, boolean weapon, int side,
             CompoundTag pose, int blockCount) {
            this.body = body; this.name = name; this.offset = offset; this.axis = axis;
            this.weapon = weapon; this.side = side; this.pose = pose; this.blockCount = blockCount;
        }
        public boolean attached() { return joint != null && joint.isValid(); }
    }
    public PracticeBot(ServerLevel level, UUID owner, BlockPos anchor, boolean enemy, int design) {
        this.level = level; this.owner = owner; this.design = design;
        try {
            var hull = new LinkedHashSet<BlockPos>();
            for (int x = -1; x <= 1; x++) for (int z = -2; z <= 2; z++) {
                BlockPos p = anchor.offset(x, 0, z); hull.add(p);
                level.setBlockAndUpdate(p, BattleBlocks.ARMOR.get().defaultBlockState());
            }
            BlockPos core = anchor.above(); hull.add(core);
            level.setBlockAndUpdate(core, BattleBlocks.TRANSPONDER.get().defaultBlockState());
            for (int z : new int[]{-1, 1}) {
                BlockPos p = anchor.offset(0, 1, z); hull.add(p);
                level.setBlockAndUpdate(p, (enemy ? Blocks.RED_CONCRETE : Blocks.LIME_CONCRETE).defaultBlockState());
            }
            add("chassis", anchor, hull, new Vector3d(), new Vector3d(), false, 0);
            int[] zs = design == CRAWLER ? new int[]{-3, 0, 3} : new int[]{-2, 2};
            int index = 0;
            for (int side : new int[]{-1, 1}) for (int z : zs) {
                BlockPos pivot = anchor.offset(side * 3, -1, z);
                var blocks = new LinkedHashSet<BlockPos>();
                if (design == CRAWLER) {
                    blocks.add(pivot); blocks.add(pivot.below()); blocks.add(pivot.below().south());
                } else {
                    blocks.add(pivot); blocks.add(pivot.above()); blocks.add(pivot.below());
                    blocks.add(pivot.north()); blocks.add(pivot.south());
                }
                for (BlockPos p : blocks) level.setBlockAndUpdate(p, BattleBlocks.ARMOR.get().defaultBlockState());
                add((design == CRAWLER ? "crank leg " : "wheel ") + (++index), pivot, blocks,
                        new Vector3d(side * 3, -1, z), new Vector3d(1, 0, 0), false, side);
            }
            BlockPos pivot = anchor.offset(0, 1, 4);
            var blade = new LinkedHashSet<BlockPos>();
            for (int i = -2; i <= 2; i++) {
                blade.add(design == SAW ? pivot.offset(0, i, 0) : pivot.offset(i, 0, 0));
                blade.add(pivot.offset(0, 0, i));
            }
            for (BlockPos p : blade) level.setBlockAndUpdate(p, BattleBlocks.ARMOR.get().defaultBlockState());
            add(design == SAW ? "saw" : "spinner", pivot, blade, new Vector3d(0, 1, 4),
                    design == SAW ? new Vector3d(1, 0, 0) : new Vector3d(0, 1, 0), true, 0);
            if (!physics.freeze(level, ids())) throw new IllegalStateException("Physics bodies are not ready");
            if (enemy) {
                Vec3 center = Vec3.atCenterOf(anchor);
                for (Part p : parts) {
                    CompoundTag pose = p.pose;
                    pose.putDouble("x", 2 * center.x - pose.getDouble("x"));
                    pose.putDouble("z", 2 * center.z - pose.getDouble("z"));
                    pose.putDouble("qy", 1); pose.putDouble("qw", 0);
                    if (!physics.restorePose(level, p.body.getUniqueId(), pose)) throw new IllegalStateException("Opponent pose failed");
                }
            }
            connect();
        } catch (RuntimeException error) { remove(); throw error; }
    }
    private void add(String name, BlockPos anchor, Collection<BlockPos> blocks, Vector3d offset,
                     Vector3d axis, boolean weapon, int side) {
        ServerSubLevel body = SubLevelAssemblyHelper.assembleBlocks(level, anchor, blocks, BoundingBox3i.from(blocks));
        body.setName("Battle Bots practice: " + name);
        CompoundTag data = body.getUserDataTag() == null ? new CompoundTag() : body.getUserDataTag().copy(); data.putUUID(OWNER_TAG, owner); body.setUserDataTag(data);
        body.updateBoundingBox();
        if (!SubLevelContainer.getContainer(level).addForceLoadTicket(body, TICKET, owner)) {
            SubLevelContainer.getContainer(level).removeSubLevel(body, SubLevelRemovalReason.REMOVED);
            throw new IllegalStateException("Cannot keep practice part loaded");
        }
        Part part = new Part(body, name, offset, axis, weapon, side, physics.capturePose(level, body.getUniqueId()), blocks.size());
        parts.add(part);
        var states = new HashMap<BlockPos, net.minecraft.world.level.block.state.BlockState>();
        for (BlockPos pos : physics.plotBlocks(level, body.getUniqueId(), 512)) states.put(pos.immutable(), level.getBlockState(pos));
        part.structure = Map.copyOf(states);
    }
    public Set<UUID> ids() {
        var result = new LinkedHashSet<UUID>(); for (Part p : parts) result.add(p.body.getUniqueId()); return result;
    }
    public Vec3 position() { return physics.worldPosition(level, parts.getFirst().body.getPlot().getCenterBlock()); }
    public double yaw() {
        Vector3d f = parts.getFirst().body.logicalPose().orientation().transform(new Vector3d(0, 0, 1));
        return Math.atan2(f.x, f.z);
    }
    public boolean contains(UUID id) { return parts.stream().anyMatch(p -> p.body.getUniqueId().equals(id)); }
    public int lostParts() { return (int) parts.stream().skip(1).filter(p -> !p.attached()).count(); }
    public boolean healthy() { return !parts.isEmpty() && coreHealth > 0 && parts.stream().noneMatch(p -> p.body.isRemoved()); }
    public void tune(int value) { tune = Math.floorMod(value, 3); }
    public void connect() {
        Part root = parts.getFirst();
        Vector3d center = center(root.body.getPlot().getCenterBlock());
        for (Part p : parts.subList(1, parts.size())) {
            if (p.joint != null && p.joint.isValid()) p.joint.remove();
            p.joint = SubLevelPhysicsSystem.require(level).getPipeline().addConstraint(root.body, p.body,
                    new RotaryConstraintConfiguration(new Vector3d(center).add(p.offset),
                            center(p.body.getPlot().getCenterBlock()), p.axis, p.axis));
            if (p.joint == null || !p.joint.isValid()) throw new IllegalStateException("Failed to attach " + p.name);
            p.joint.setContactsEnabled(false);
        }
    }
    public void drive(PracticeRules.Input input) {
        if (!healthy()) return;
        Part root = parts.getFirst();
        RigidBodyHandle handle = RigidBodyHandle.of(root.body);
        if (handle == null || !handle.isValid()) return;
        Vector3d up = root.body.logicalPose().orientation().transform(new Vector3d(0, 1, 0));
        double targetWeapon = input.weapon() ? (tune == 2 ? 16 : tune == 1 ? 8 : 12) : 0;
        weaponSpeed += PracticeRules.clamp(targetWeapon - weaponSpeed, -0.5, 0.3);
        int left = 0, right = 0;
        for (Part p : parts.subList(1, parts.size())) {
            if (!p.attached()) continue;
            if (!p.weapon) { if (p.side < 0) left++; else right++; }
            // Bounded velocity-motor assistance. Collisions, gravity, and constraints remain native.
            RigidBodyHandle partHandle = RigidBodyHandle.of(p.body);
            if (partHandle == null || !partHandle.isValid()) continue;
            Vector3d axis = root.body.logicalPose().orientation().transform(new Vector3d(p.axis));
            double target = p.weapon ? weaponSpeed : (input.brake() ? 0 : input.throttle() * 4.5 + input.turn() * p.side * 1.8);
            double rate = partHandle.getAngularVelocity().dot(axis);
            double delta = PracticeRules.clamp(target - rate, -0.65, 0.65);
            partHandle.addLinearAndAngularVelocity(new Vector3d(), new Vector3d(axis).mul(delta));
        }
        int wheels = design == CRAWLER ? 6 : 4;
        double grip = (left + right) / (double) wheels;
        if (up.y < 0.25 || grip == 0) return; // No invisible driving after losing the running gear.
        double yaw = yaw();
        Vector3d forward = new Vector3d(Math.sin(yaw), 0, Math.cos(yaw));
        Vector3d side = new Vector3d(Math.cos(yaw), 0, -Math.sin(yaw));
        Vector3d velocity = handle.getLinearVelocity(new Vector3d());
        double limit = design == CRAWLER ? 4.2 : tune == 2 ? 6 : 7;
        double wanted = input.brake() ? 0 : input.throttle() * limit * grip;
        double acceleration = PracticeRules.clamp(wanted - velocity.dot(forward), -0.22, 0.22) * grip;
        // Only supply ground traction near the floor. Airborne robots keep their momentum.
        if (position().y > 69.5) return;
        Vector3d impulse = forward.mul(acceleration).add(side.mul(-velocity.dot(side) * 0.09 * grip));
        double yawRate = handle.getAngularVelocity().y();
        double desiredYaw = input.brake() ? 0 : input.turn() * 1.4 * grip + (right - left) * input.throttle() * 0.35;
        handle.addLinearAndAngularVelocity(impulse,
                new Vector3d(0, PracticeRules.clamp(desiredYaw - yawRate, -0.12, 0.12), 0));
    }
    /** Returns the visibly detached component, or an empty string for a non-terminal hit. */
    public String hit(UUID body, double damage, long tick) {
        for (int i = 0; i < parts.size(); i++) {
            Part p = parts.get(i);
            if (!p.body.getUniqueId().equals(body) || damage <= 0) continue;
            if (i == 0) {
                if (tick - lastCoreHit < 8) return "";
                lastCoreHit = tick; coreHealth = Math.max(0, coreHealth - damage * (tune == 1 ? 0.75 : 1));
            } else if (p.attached() && tick - p.lastHit >= 8) {
                p.lastHit = tick; p.health -= damage;
                if (p.health <= 0) { p.joint.remove(); return p.name; }
            }
        }
        return "";
    }
    public void freeze() {
        if (!physics.freeze(level, ids())) throw new IllegalStateException("A practice part is missing");
    }
    public void release() { physics.unfreeze(level, ids()); }
    /** Reuses the SAME bodies and blocks. Detached pieces are never duplicated as item drops. */
    public void repair() {
        for (Part p : parts) {
            if (p.body.isRemoved() || !physics.loaded(level, p.body.getUniqueId())
                    || physics.plotBlocks(level, p.body.getUniqueId(), 512).size() != p.blockCount
                    || p.structure.entrySet().stream().anyMatch(entry -> !level.getBlockState(entry.getKey()).equals(entry.getValue())))
                throw new IllegalStateException("Starter bot was modified externally. Choose a fresh design.");
        }
        freeze();
        for (Part p : parts) {
            if (p.joint != null && p.joint.isValid()) p.joint.remove();
            if (!physics.restorePose(level, p.body.getUniqueId(), p.pose)) throw new IllegalStateException("Practice repair failed");
            p.health = 65; p.lastHit = -100;
        }
        connect(); coreHealth = 100; weaponSpeed = 0; lastCoreHit = -100;
    }
    public void remove() {
        if (parts.isEmpty()) return;
        physics.unfreeze(level, ids());
        var container = SubLevelContainer.getContainer(level);
        for (Part p : parts) if (p.joint != null && p.joint.isValid()) p.joint.remove();
        for (Part p : parts) {
            container.removeForceLoadTicket(p.body, TICKET, owner);
            if (!p.body.isRemoved()) container.removeSubLevel(p.body, SubLevelRemovalReason.REMOVED);
        }
        container.processSubLevelRemovals(); parts.clear();
    }
    static Vector3d center(BlockPos p) { return new Vector3d(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5); }
}
