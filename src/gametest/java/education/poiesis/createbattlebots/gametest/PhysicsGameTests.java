package education.poiesis.createbattlebots.gametest;

import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.physics.constraint.RotaryConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.RotaryConstraintHandle;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import education.poiesis.createbattlebots.block.BattleBlocks;
import education.poiesis.createbattlebots.block.BattleNodeBlock;
import education.poiesis.createbattlebots.compat.sable.SablePhysicsAdapter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Unit;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@GameTestHolder("createbattlebots_test")
@PrefixGameTestTemplate(false)
public final class PhysicsGameTests {
    private static final TicketType<UUID> FIXTURE_TICKET = TicketType.create("battlebots_physics_test", UUID::compareTo);
    private static final SubLevelLoadingTicketType<UUID> FIXTURE_NATIVE_TICKET = SubLevelLoadingTicketType.create(
            ResourceLocation.fromNamespaceAndPath("createbattlebots_test", "fixture"), UUIDUtil.CODEC);
    private static final Map<UUID, ChunkPos> FIXTURE_CHUNKS = new HashMap<>();
    // The collision batch runs separately. The test-only post-physics probe observes
    // native callbacks before the ordinary service consumes them at the game tick.
    static volatile ContactProbe contactProbe;

    @GameTest(template = "empty", batch = "battlebots_pose", timeoutTicks = 100)
    public static void twentyPoseResetsPreserveIdentityBlocksAndInventory(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        SablePhysicsAdapter adapter = new SablePhysicsAdapter();
        BlockPos armorWorld = new BlockPos(67, 84, 67);
        BlockPos chestWorld = armorWorld.east();
        level.setBlockAndUpdate(armorWorld, BattleBlocks.ARMOR.get().defaultBlockState());
        level.setBlockAndUpdate(chestWorld, Blocks.CHEST.defaultBlockState());
        ((ChestBlockEntity) level.getBlockEntity(chestWorld)).setItem(0, new ItemStack(Items.DIAMOND, 17));
        ServerSubLevel body = assemble(level, armorWorld, List.of(armorWorld, chestWorld));
        UUID id = body.getUniqueId();
        BlockPos armorPlot = body.getPlot().getCenterBlock();
        BlockPos chestPlot = armorPlot.east();
        check(helper, adapter.resolve(level, armorPlot).orElseThrow().equals(id), "Resolution must use persistent body UUID");
        check(helper, adapter.plotBlocks(level, id, 16).size() == 2, "Snapshot must include exactly the two registered plot blocks");
        check(helper, adapter.worldPosition(level, armorPlot).distanceTo(Vec3.atCenterOf(armorWorld)) < 0.001,
                "Plot coordinates must transform to the actual world block center");
        check(helper, adapter.freeze(level, Set.of(id)), "Registered body must freeze");
        CompoundTag initialPose = adapter.capturePose(level, id);
        Vec3 initialPosition = adapter.worldPosition(level, armorPlot);
        int bodyCount = SubLevelContainer.getContainer(level).getAllSubLevels().size();
        var originalNode = level.getBlockEntity(armorPlot);

        for (int cycle = 1; cycle <= 20; cycle++) {
            final int currentCycle = cycle;
            helper.runAtTickTime(cycle * 2L, () -> {
                CompoundTag displaced = initialPose.copy();
                displaced.putDouble("x", displaced.getDouble("x") + 3);
                displaced.putDouble("y", displaced.getDouble("y") + 1);
                check(helper, adapter.restorePose(level, id, displaced), "Displacement should succeed");
                check(helper, adapter.worldPosition(level, armorPlot).distanceTo(initialPosition) > 2.9, "Body must actually move before reset");
                level.setBlockAndUpdate(armorPlot, BattleBlocks.ARMOR.get().defaultBlockState().setValue(BattleNodeBlock.DISABLED, true));
                checkCollapsedArmor(helper, level, armorPlot);
                check(helper, level.getBlockEntity(armorPlot) == originalNode, "Disabling armor must preserve its native block entity");
            });
            helper.runAtTickTime(cycle * 2L + 1, () -> {
                check(helper, adapter.resolve(level, armorPlot).orElseThrow().equals(id),
                        "Disabling shielding through a physics tick must not split the body");
                level.setBlockAndUpdate(armorPlot, BattleBlocks.ARMOR.get().defaultBlockState());
                check(helper, !level.getBlockState(armorPlot).getCollisionShape(level, armorPlot).isEmpty(),
                        "Automatic repair must restore armor shielding");
                check(helper, adapter.restorePose(level, id, initialPose), "Pose restore must succeed on cycle " + currentCycle);
                check(helper, adapter.worldPosition(level, armorPlot).distanceTo(initialPosition) < 0.001, "Pose drift after reset " + currentCycle);
                check(helper, adapter.plotBlocks(level, id, 16).size() == 2, "Block count changed after reset");
                ItemStack stack = ((ChestBlockEntity) level.getBlockEntity(chestPlot)).getItem(0);
                check(helper, stack.is(Items.DIAMOND) && stack.getCount() == 17, "Inventory changed or duplicated on reset");
                check(helper, SubLevelContainer.getContainer(level).getAllSubLevels().size() == bodyCount, "Reset created an orphan or duplicate body");
                check(helper, adapter.bodyInfo(level, id).orElseThrow().linearVelocity().lengthSqr() < 0.0001, "Recovery must clear momentum");
                if (currentCycle == 20) {
                    CompoundTag corrupt = initialPose.copy();
                    corrupt.putDouble("qw", Double.NaN);
                    check(helper, !adapter.restorePose(level, id, corrupt), "Corrupt snapshot must fail closed");
                    CompoundTag wrongOwner = initialPose.copy();
                    wrongOwner.putUUID("body", UUID.randomUUID());
                    check(helper, !adapter.restorePose(level, id, wrongOwner), "Snapshot must not teleport a different body");
                    UUID staleMatch = UUID.randomUUID();
                    UUID pendingMatch = UUID.randomUUID();
                    check(helper, adapter.holdLoaded(level, Set.of(id), staleMatch), "Stale-ticket fixture must hold the native body");
                    check(helper, adapter.holdLoaded(level, Set.of(id), pendingMatch), "Pending match must hold the native body");
                    var container = SubLevelContainer.getContainer(level);
                    container.addForceLoadTicket(body, SubLevelLoadingTicketType.COMMAND_FORCED, Unit.INSTANCE);
                    check(helper, adapter.reconcileLoadedTickets(level, Set.of(pendingMatch)) == 1,
                            "Crash reconciliation must release exactly the obsolete match ticket");
                    check(helper, container.getAllTickets().get(id).tickets().stream()
                                    .anyMatch(ticket -> ticket.type().equals(SubLevelLoadingTicketType.COMMAND_FORCED)),
                            "Recovery must preserve operator/native tickets owned outside this mod");
                    container.removeForceLoadTicket(body, SubLevelLoadingTicketType.COMMAND_FORCED, Unit.INSTANCE);
                    adapter.releaseLoaded(level, Set.of(id), staleMatch);
                    adapter.releaseLoaded(level, Set.of(id), pendingMatch);
                    check(helper, adapter.verifiedSave(level, Set.of(id)),
                            "Restored body, armor state, and all 17 inventory items must survive native disk readback");
                    adapter.unfreeze(level, Set.of(id));
                    remove(level, body);
                    helper.succeed();
                }
            });
        }
    }

    @GameTest(template = "empty", batch = "battlebots_native_contact", timeoutTicks = 100)
    public static void movingBodiesProduceNativeAttributedContact(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        SablePhysicsAdapter adapter = new SablePhysicsAdapter();
        // Vanilla starts GameTest structures millions of blocks from origin. Sable's pinned
        // native solver uses floats, so isolate motion fixtures near origin for sub-block precision.
        BlockPos fixtureOrigin = new BlockPos(96, 80, 64);
        BlockPos leftWorld = fixtureOrigin.offset(4, 4, 4);
        BlockPos rightWorld = fixtureOrigin.offset(8, 4, 4);
        for (BlockPos floor : BlockPos.betweenClosed(fixtureOrigin.offset(1, 0, 1),
                fixtureOrigin.offset(13, 0, 8))) {
            level.setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
        }
        level.setBlockAndUpdate(leftWorld, BattleBlocks.ARMOR.get().defaultBlockState());
        level.setBlockAndUpdate(rightWorld, BattleBlocks.ARMOR.get().defaultBlockState());
        ServerSubLevel left = assemble(level, leftWorld, List.of(leftWorld));
        ServerSubLevel right = assemble(level, rightWorld, List.of(rightWorld));
        ContactProbe probe = new ContactProbe(level, adapter, left.getPlot().getCenterBlock(),
                right.getPlot().getCenterBlock(), new ArrayList<>());
        adapter.discardContacts(level);
        contactProbe = probe;
        SubLevelPhysicsSystem physics = SubLevelPhysicsSystem.require(level);
        check(helper, adapter.freeze(level, Set.of(left.getUniqueId(), right.getUniqueId())), "Contact fixture must freeze during native setup");
        helper.runAtTickTime(10, () -> {
            adapter.unfreeze(level, Set.of(left.getUniqueId(), right.getUniqueId()));
            physics.getPipeline().addLinearAndAngularVelocity(left, new Vector3d(7, 0, 0), new Vector3d());
            physics.getPipeline().addLinearAndAngularVelocity(right, new Vector3d(-7, 0, 0), new Vector3d());
        });
        helper.runAtTickTime(40, () -> {
            contactProbe = null;
            check(helper, !probe.contacts().isEmpty(), "Real Sable collision failed to produce an attributed hit-zone contact. Left: "
                    + adapter.bodyInfo(level, left.getUniqueId()) + "; right: " + adapter.bodyInfo(level, right.getUniqueId()));
            check(helper, probe.contacts().stream().anyMatch(hit -> hit.speed() > 1 && Double.isFinite(hit.speed())),
                    "Native impact velocity must be meaningful and finite");
            for (SablePhysicsAdapter.ImpactContact hit : probe.contacts()) {
                check(helper, adapter.resolve(level, hit.self()).isPresent() && adapter.resolve(level, hit.other()).isPresent(),
                        "Both native hit coordinates must resolve to actual physics bodies");
            }
            check(helper, level.getBlockState(fixtureOrigin.offset(6, 0, 4)).is(Blocks.STONE),
                    "Contact observation must not destroy normal terrain");
            remove(level, left);
            remove(level, right);
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = "battlebots_linked_pose", timeoutTicks = 120)
    public static void twentyGroupResetsKeepBothRotaryJointsAlive(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        SablePhysicsAdapter adapter = new SablePhysicsAdapter();
        BlockPos parentWorld = new BlockPos(133, 85, 69);
        BlockPos firstWorld = parentWorld.east(2);
        BlockPos secondWorld = parentWorld.south(2);
        for (BlockPos position : List.of(parentWorld, firstWorld, secondWorld)) {
            level.setBlockAndUpdate(position, BattleBlocks.ARMOR.get().defaultBlockState());
        }
        ServerSubLevel parent = assemble(level, parentWorld, List.of(parentWorld));
        ServerSubLevel first = assemble(level, firstWorld, List.of(firstWorld));
        ServerSubLevel second = assemble(level, secondWorld, List.of(secondWorld));
        SubLevelPhysicsSystem physics = SubLevelPhysicsSystem.require(level);
        Vector3d parentCenter = center(parent.getPlot().getCenterBlock());
        RotaryConstraintHandle firstJoint = physics.getPipeline().addConstraint(parent, first,
                new RotaryConstraintConfiguration(new Vector3d(parentCenter).add(1, 0, 0),
                        center(first.getPlot().getCenterBlock()).add(-1, 0, 0),
                        new Vector3d(0, 1, 0), new Vector3d(0, 1, 0)));
        RotaryConstraintHandle secondJoint = physics.getPipeline().addConstraint(parent, second,
                new RotaryConstraintConfiguration(new Vector3d(parentCenter).add(0, 0, 1),
                        center(second.getPlot().getCenterBlock()).add(0, 0, -1),
                        new Vector3d(0, 1, 0), new Vector3d(0, 1, 0)));
        check(helper, firstJoint != null && secondJoint != null, "Native rotary constraints must be created");
        firstJoint.setContactsEnabled(false);
        secondJoint.setContactsEnabled(false);
        physics.getPipeline().addLinearAndAngularVelocity(first, new Vector3d(), new Vector3d(0, 3, 0));
        physics.getPipeline().addLinearAndAngularVelocity(second, new Vector3d(), new Vector3d(0, -3, 0));
        Set<UUID> ids = Set.of(parent.getUniqueId(), first.getUniqueId(), second.getUniqueId());
        Map<UUID, CompoundTag> poses = new HashMap<>();
        int[] originalCount = {0};
        helper.runAtTickTime(5, () -> {
            check(helper, firstJoint.isValid() && secondJoint.isValid(), "Both joints must survive live motion");
            check(helper, adapter.freeze(level, ids), "Linked group must freeze atomically; body states: "
                    + ids.stream().map(id -> adapter.bodyInfo(level, id).toString()).toList());
            for (UUID id : ids) poses.put(id, adapter.capturePose(level, id));
            originalCount[0] = SubLevelContainer.getContainer(level).getAllSubLevels().size();
        });
        // Register every scheduled callback before the first tick. Mutating GameTestInfo's
        // fastutil schedule while it is being iterated can corrupt the vanilla test runner.
        for (int cycle = 1; cycle <= 20; cycle++) {
                final int current = cycle;
                helper.runAtTickTime(5 + cycle * 2L, () -> {
                    for (UUID id : ids) {
                        CompoundTag moved = poses.get(id).copy();
                        moved.putDouble("x", moved.getDouble("x") + 2);
                        check(helper, adapter.restorePose(level, id, moved), "Linked body displacement failed");
                    }
                    BlockPos firstArmor = first.getPlot().getCenterBlock();
                    level.setBlockAndUpdate(firstArmor, BattleBlocks.ARMOR.get().defaultBlockState().setValue(BattleNodeBlock.DISABLED, true));
                    checkCollapsedArmor(helper, level, firstArmor);
                });
                helper.runAtTickTime(6 + cycle * 2L, () -> {
                    BlockPos firstArmor = first.getPlot().getCenterBlock();
                    check(helper, adapter.loaded(level, first.getUniqueId()), "A disabled child must keep its body identity through physics");
                    level.setBlockAndUpdate(firstArmor, BattleBlocks.ARMOR.get().defaultBlockState());
                    for (UUID id : ids) {
                        check(helper, adapter.restorePose(level, id, poses.get(id)), "Linked body reset failed");
                        check(helper, adapter.plotBlocks(level, id, 4).size() == 1, "Linked body blocks changed");
                    }
                    check(helper, firstJoint.isValid() && secondJoint.isValid(), "A rotary joint was lost on reset " + current);
                    check(helper, SubLevelContainer.getContainer(level).getAllSubLevels().size() == originalCount[0],
                            "Linked group reset duplicated or orphaned a physics body");
                    if (current == 20) {
                        adapter.unfreeze(level, ids);
                        firstJoint.remove();
                        secondJoint.remove();
                        remove(level, parent);
                        remove(level, first);
                        remove(level, second);
                        helper.succeed();
                    }
                });
        }
    }

    private static ServerSubLevel assemble(ServerLevel level, BlockPos anchor, List<BlockPos> positions) {
        ServerSubLevel body = SubLevelAssemblyHelper.assembleBlocks(level, anchor, positions, BoundingBox3i.from(positions));
        ChunkPos chunk = new ChunkPos(anchor);
        level.getChunkSource().addRegionTicket(FIXTURE_TICKET, chunk, 2, body.getUniqueId());
        FIXTURE_CHUNKS.put(body.getUniqueId(), chunk);
        body.updateBoundingBox();
        if (!SubLevelContainer.getContainer(level).addForceLoadTicket(body, FIXTURE_NATIVE_TICKET, body.getUniqueId())) {
            throw new IllegalStateException("Could not keep test body resident");
        }
        return body;
    }

    private static void checkCollapsedArmor(GameTestHelper helper, ServerLevel level, BlockPos position) {
        var shape = level.getBlockState(position).getCollisionShape(level, position);
        check(helper, !shape.isEmpty() && shape.bounds().getYsize() <= 1.0 / 16.0,
                "Disabled armor must collapse to a thin remnant without invalidating the native body's mass");
    }

    private static Vector3d center(BlockPos position) {
        return new Vector3d(position.getX() + 0.5, position.getY() + 0.5, position.getZ() + 0.5);
    }

    private static void remove(ServerLevel level, ServerSubLevel body) {
        ChunkPos chunk = FIXTURE_CHUNKS.remove(body.getUniqueId());
        if (chunk != null) level.getChunkSource().removeRegionTicket(FIXTURE_TICKET, chunk, 2, body.getUniqueId());
        SubLevelContainer.getContainer(level).removeForceLoadTicket(body, FIXTURE_NATIVE_TICKET, body.getUniqueId());
        SubLevelContainer.getContainer(level).removeSubLevel(body, SubLevelRemovalReason.REMOVED);
        SubLevelContainer.getContainer(level).processSubLevelRemovals();
    }

    private static void check(GameTestHelper helper, boolean condition, String message) {
        if (!condition) helper.fail(message);
    }

    record ContactProbe(ServerLevel level, SablePhysicsAdapter adapter, BlockPos left, BlockPos right,
                        List<SablePhysicsAdapter.ImpactContact> contacts) { }
}
