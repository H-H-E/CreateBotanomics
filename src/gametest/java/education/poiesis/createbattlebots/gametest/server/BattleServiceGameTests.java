package education.poiesis.createbattlebots.gametest.server;

import com.mojang.authlib.GameProfile;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import education.poiesis.createbattlebots.block.BattleBlocks;
import education.poiesis.createbattlebots.block.BattleNodeBlock;
import education.poiesis.createbattlebots.block.BattleNodeEntity;
import education.poiesis.createbattlebots.compat.sable.SablePhysicsAdapter;
import education.poiesis.createbattlebots.core.ControlMode;
import education.poiesis.createbattlebots.core.MatchState;
import education.poiesis.createbattlebots.data.BattleSavedData;
import education.poiesis.createbattlebots.data.BotRecord;
import education.poiesis.createbattlebots.menu.GarageMenu;
import education.poiesis.createbattlebots.repair.SnapshotStore;
import education.poiesis.createbattlebots.server.BattleService;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.util.Unit;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.joml.Vector3d;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Exercises actual native bodies, the real service, persistent files, and connected test players. */
@GameTestHolder("createbattlebots_test")
@PrefixGameTestTemplate(false)
public final class BattleServiceGameTests {
    private static final Map<UUID, EmbeddedChannel> TEST_CHANNELS = new java.util.HashMap<>();
    private static int fixtureIndex;
    @GameTest(template = "empty", batch = "battlebots_service_lifecycle", timeoutTicks = 240)
    public static void realMatchStartsCountsDownAndRestoresWithoutDuplicatingInventory(GameTestHelper helper) {
        Fixture f = fixture(helper, "lifecycle");
        int bodyCount = SubLevelContainer.getContainer(f.level).getAllSubLevels().size();
        f.service.start(f.firstPlayer, f.arena, ControlMode.HUMAN);
        check(helper, phase(f) == MatchState.COUNTDOWN.ordinal() + 1, "Start must enter countdown");
        check(helper, BattleSavedData.get(f.level).pending.containsKey(f.arena), "Pending checkpoint must already exist on disk");
        check(helper, f.service.isProtected(f.first.core), "Registered plot blocks must be protected during countdown");
        check(helper, !f.service.canInteract(f.firstPlayer, f.first.core), "Countdown cannot permit machine reconfiguration");
        helper.runAtTickTime(105, () -> {
            check(helper, phase(f) == MatchState.ACTIVE.ordinal() + 1, "Countdown must reach active through normal world ticks");
            node(f.level, f.first.armor).hurt(1000);
            node(f.level, f.second.relay).hurt(1000);
            check(helper, f.level.getBlockState(f.first.armor).getValue(BattleNodeBlock.DISABLED), "Actual node must disable");
            check(helper, f.level.getSignal(f.second.relay, net.minecraft.core.Direction.NORTH) == 15,
                    "A disabled relay must emit a real redstone signal for the connected clutch");
            f.service.stop(f.firstPlayer, f.arena);
            f.service.tick();
            SablePhysicsAdapter adapter = new SablePhysicsAdapter();
            Vec3 firstActual = adapter.worldPosition(f.level, f.first.core), firstExpected = Vec3.atCenterOf(f.first.world);
            Vec3 secondActual = adapter.worldPosition(f.level, f.second.core), secondExpected = Vec3.atCenterOf(f.second.world);
            check(helper, firstActual.distanceTo(firstExpected) < 0.001,
                    "First bot did not return to its saved world pose: actual=" + firstActual + " expected=" + firstExpected);
            check(helper, secondActual.distanceTo(secondExpected) < 0.001,
                    "Second bot did not return to its saved world pose: actual=" + secondActual + " expected=" + secondExpected);
        });
        helper.runAtTickTime(108, () -> {
            check(helper, !BattleSavedData.get(f.level).pending.containsKey(f.arena), "Verified restoration must clear durable recovery");
            check(helper, !f.service.isProtected(f.first.core), "Finished match must release ownership lock");
            check(helper, node(f.level, f.first.armor).health() == node(f.level, f.first.armor).maximumHealth(), "Armor must repair");
            check(helper, node(f.level, f.second.relay).health() == node(f.level, f.second.relay).maximumHealth(), "Relay must repair");
            check(helper, !f.level.getBlockState(f.second.relay).getValue(BattleNodeBlock.DISABLED), "Relay state must reset");
            check(helper, f.level.getSignal(f.second.relay, net.minecraft.core.Direction.NORTH) == 0,
                    "Repaired relay must release its clutch-control signal");
            assertInventories(helper, f);
            check(helper, SubLevelContainer.getContainer(f.level).getAllSubLevels().size() == bodyCount, "Match spawned orphan or duplicate bodies");
            cleanup(f);
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = "battlebots_service_permissions", timeoutTicks = 80)
    public static void ownershipAndConsolePacketsCannotBypassServerAuthorization(GameTestHelper helper) {
        Fixture f = fixture(helper, "permissions");
        ServerPlayer intruder = makePlayer(f.level);
        f.extraPlayers.add(intruder);
        intruder.setPos(Vec3.atCenterOf(f.first.world));
        denied(helper, () -> f.service.registerBot(intruder, "stolen_" + f.suffix, f.first.core, "ground"), "another player");
        denied(helper, () -> f.service.unregisterBot(intruder, f.first.record.name), "owner");
        denied(helper, () -> f.service.repairBot(intruder, f.first.record.name), "owner");
        denied(helper, () -> f.service.leave(intruder, f.arena, f.first.record.name), "owner");
        denied(helper, () -> f.service.start(intruder, f.arena, ControlMode.HUMAN), "operator");
        denied(helper, () -> f.service.removeArena(intruder, f.arena), "operator");

        intruder.setPos(Vec3.atCenterOf(f.console));
        GarageMenu intruderMenu = new GarageMenu(1, intruder.getInventory(), f.console);
        check(helper, intruderMenu.stillValid(intruder), "Fixture should be in reach of a real console");
        check(helper, !intruderMenu.clickMenuButton(intruder, GarageMenu.START_HUMAN), "Forged start button from non-operator must fail");
        check(helper, !BattleSavedData.get(f.level).pending.containsKey(f.arena), "Rejected start must not write a pending match");

        f.firstPlayer.setPos(Vec3.atCenterOf(f.console));
        GarageMenu adminMenu = new GarageMenu(2, f.firstPlayer.getInventory(), f.console);
        check(helper, !adminMenu.clickMenuButton(f.firstPlayer, Integer.MAX_VALUE), "Out-of-range action IDs must fail");
        f.firstPlayer.setPos(Vec3.atCenterOf(f.console).add(10, 0, 0));
        check(helper, !adminMenu.clickMenuButton(f.firstPlayer, GarageMenu.START_HUMAN), "Console action must fail outside 4.5 blocks");
        f.firstPlayer.setPos(Vec3.atCenterOf(f.console));
        f.level.setBlockAndUpdate(f.console, Blocks.AIR.defaultBlockState());
        check(helper, !adminMenu.clickMenuButton(f.firstPlayer, GarageMenu.START_HUMAN), "Destroyed console cannot accept stale packets");
        cleanup(f);
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "battlebots_service_protection", timeoutTicks = 100)
    public static void protectedArenaRejectsCreativeBreaksAndOpponentInteractions(GameTestHelper helper) {
        Fixture f = fixture(helper, "protection");
        f.service.start(f.firstPlayer, f.arena, ControlMode.HUMAN);
        BlockPos floor = f.console.below();
        BlockEvent.BreakEvent event = new BlockEvent.BreakEvent(f.level, floor, f.level.getBlockState(floor), f.firstPlayer);
        NeoForge.EVENT_BUS.post(event);
        check(helper, event.isCanceled(), "Operator creative breaking must not bypass arena protection");
        BlockEvent.BreakEvent robotEvent = new BlockEvent.BreakEvent(f.level, f.first.core, f.level.getBlockState(f.first.core), f.firstPlayer);
        NeoForge.EVENT_BUS.post(robotEvent);
        check(helper, robotEvent.isCanceled(), "Physics-plot coordinates must protect the bot");
        check(helper, !f.service.canInteract(f.secondPlayer, f.first.core), "Opponent must not operate another pilot's robot");
        check(helper, !f.service.canInteract(f.firstPlayer, f.first.chest), "Owner must not access captured inventory during combat");
        f.secondPlayer.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.WATER_BUCKET));
        check(helper, !f.service.canInteract(f.secondPlayer, floor), "Bucket interaction must not bypass protected arena terrain");
        f.secondPlayer.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        check(helper, !f.service.isProtected(f.console.offset(0, 0, 30)), "Protection must not leak outside the arena");
        f.service.stop(f.firstPlayer, f.arena);
        helper.runAtTickTime(3, () -> {
            check(helper, !f.service.isProtected(floor), "Restoration should release terrain protection");
            assertInventories(helper, f);
            cleanup(f);
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = "battlebots_service_restart", timeoutTicks = 100)
    public static void serviceReloadReadsDiskAndRecoversTheSameBodies(GameTestHelper helper) {
        Fixture f = fixture(helper, "restart");
        f.service.start(f.firstPlayer, f.arena, ControlMode.AUTONOMOUS);
        check(helper, f.firstPlayer.gameMode.getGameModeForPlayer() == GameType.SPECTATOR, "Autonomous pilot must be control-locked");
        CompoundTag stalePlayerSaveMarker = f.firstPlayer.getPersistentData().getCompound(BattleService.PILOT_RESTORE_MARKER).copy();
        check(helper, stalePlayerSaveMarker.getInt("Mode") == GameType.CREATIVE.getId(), "Crash marker must retain original mode before spectator lock");
        node(f.level, f.first.relay).hurt(1000);
        UUID firstId = f.first.body.getUniqueId();
        // Discard all in-memory controllers. The replacement service reads the checked registry file.
        BattleService.clear();
        f.service = BattleService.get(f.level);
        f.service.tick();
        check(helper, !BattleSavedData.get(f.level).pending.containsKey(f.arena), "Disk-backed recovery must finish after reload");
        check(helper, node(f.level, f.first.relay).health() == node(f.level, f.first.relay).maximumHealth(), "Restart must repair disabled relay");
        check(helper, new SablePhysicsAdapter().resolve(f.level, f.first.core).orElseThrow().equals(firstId), "Recovery must keep native UUID");
        check(helper, f.firstPlayer.gameMode.getGameModeForPlayer() == GameType.CREATIVE, "Original pilot mode must restore after reload");
        check(helper, f.secondPlayer.gameMode.getGameModeForPlayer() == GameType.SURVIVAL, "Each pilot's own mode must restore");
        check(helper, !f.firstPlayer.getPersistentData().contains(BattleService.PILOT_RESTORE_MARKER), "Normal restoration must clear pilot crash marker");
        // Simulate an older spectator player file surviving after registry recovery committed.
        f.firstPlayer.getPersistentData().put(BattleService.PILOT_RESTORE_MARKER, stalePlayerSaveMarker);
        f.firstPlayer.setGameMode(GameType.SPECTATOR);
        f.service.playerJoined(f.firstPlayer);
        check(helper, f.firstPlayer.gameMode.getGameModeForPlayer() == GameType.CREATIVE, "Login must restore stale spectator player data without a pending match");
        check(helper, !f.firstPlayer.getPersistentData().contains(BattleService.PILOT_RESTORE_MARKER), "Login recovery must clear stale marker exactly once");
        assertInventories(helper, f);
        cleanup(f);
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "battlebots_service_corruption", timeoutTicks = 100)
    public static void corruptSnapshotRetainsProtectionUntilVerifiedRecovery(GameTestHelper helper) {
        Fixture f = fixture(helper, "corruption");
        f.service.start(f.firstPlayer, f.arena, ControlMode.HUMAN);
        CompoundTag pending = BattleSavedData.get(f.level).pending.get(f.arena);
        Path snapshot = SnapshotStore.storageRoot(f.level).resolve(pending.getUUID("Match") + ".nbt");
        try {
            byte[] original = Files.readAllBytes(snapshot);
            byte[] corrupt = original.clone();
            corrupt[corrupt.length - 1] ^= 1;
            Files.write(snapshot, corrupt);
            f.service.stop(f.firstPlayer, f.arena);
            f.service.tick();
            check(helper, phase(f) == MatchState.RECOVERY.ordinal() + 1, "Bad checksum must enter recovery");
            check(helper, f.service.isProtected(f.first.core), "Checksum failure must retain robot locks");
            check(helper, BattleSavedData.get(f.level).pending.containsKey(f.arena), "Checksum failure must retain durable reference");
            Files.write(snapshot, original);
            f.service.recover(f.firstPlayer, f.arena);
            check(helper, !BattleSavedData.get(f.level).pending.containsKey(f.arena), "Verified original snapshot must permit recovery");
            assertInventories(helper, f);
        } catch (IOException error) {
            helper.fail("Snapshot fixture IO failed: " + error);
        }
        cleanup(f);
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "battlebots_service_registry", timeoutTicks = 100)
    public static void unreadableRegistryNeverBecomesAnEmptyUnlockedRegistry(GameTestHelper helper) {
        Fixture f = fixture(helper, "registry");
        f.service.start(f.firstPlayer, f.arena, ControlMode.HUMAN);
        Path registry = SnapshotStore.storageRoot(f.level).resolve("registry.nbt");
        try {
            byte[] original = Files.readAllBytes(registry);
            try {
                CompoundTag envelope = NbtIo.readCompressed(registry, NbtAccounter.create(32 * 1024 * 1024));
                byte[] payload = envelope.getByteArray("Payload");
                payload[payload.length / 2] ^= 1;
                envelope.putByteArray("Payload", payload);
                // Keep a valid outer gzip container and the original SHA256 to exercise content verification.
                NbtIo.writeCompressed(envelope, registry);
                byte[] corrupt = Files.readAllBytes(registry);
                boolean rejected = false;
                try { BattleSavedData.get(f.level); }
                catch (IllegalStateException expected) { rejected = true; }
                check(helper, rejected, "A corrupt existing registry must fail closed, never silently create a fresh registry");
                check(helper, java.util.Arrays.equals(corrupt, Files.readAllBytes(registry)), "Failed registry read must not replace the evidence");
            } finally {
                Files.write(registry, original);
            }
            check(helper, BattleSavedData.get(f.level).pending.containsKey(f.arena), "Restored registry must retain the pending recovery reference");
        } catch (IOException error) {
            helper.fail("Registry fixture IO failed: " + error);
        }
        f.service.stop(f.firstPlayer, f.arena);
        f.service.tick();
        cleanup(f);
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "battlebots_service_structure", timeoutTicks = 100)
    public static void unexpectedStructuralChangesFailBeforePartialNodeRepair(GameTestHelper helper) {
        Fixture f = fixture(helper, "structure");
        f.service.start(f.firstPlayer, f.arena, ControlMode.HUMAN);
        node(f.level, f.first.relay).hurt(1000);
        BlockPos unexpected = f.first.core.above();
        f.level.setBlockAndUpdate(unexpected, Blocks.GOLD_BLOCK.defaultBlockState());
        f.service.stop(f.firstPlayer, f.arena);
        f.service.tick();
        check(helper, phase(f) == MatchState.RECOVERY.ordinal() + 1, "New unsnapshotted block must prevent false restoration success");
        check(helper, node(f.level, f.first.relay).health() == 0, "All structural checks must pass before any node is repaired");
        check(helper, f.level.getBlockState(unexpected).is(Blocks.GOLD_BLOCK), "Recovery must not erase an unrelated added block");
        f.level.setBlockAndUpdate(unexpected, Blocks.AIR.defaultBlockState());
        f.service.recover(f.firstPlayer, f.arena);
        check(helper, !BattleSavedData.get(f.level).pending.containsKey(f.arena), "Corrected structure should recover");
        assertInventories(helper, f);
        cleanup(f);
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "battlebots_service_damage", timeoutTicks = 260)
    public static void realBotCollisionDamagesRegisteredTargetsAndLeavesTerrainIntact(GameTestHelper helper) {
        Fixture f = fixture(helper, "damage");
        f.service.start(f.firstPlayer, f.arena, ControlMode.HUMAN);
        helper.runAtTickTime(105, () -> {
            check(helper, phase(f) == MatchState.ACTIVE.ordinal() + 1, "Contact test must run during active combat");
            // Leave a measurable amount of integrity for the real physics contact to remove.
            node(f.level, f.first.armor).hurt(150);
            node(f.level, f.second.armor).hurt(150);
            SubLevelPhysicsSystem system = SubLevelPhysicsSystem.require(f.level);
            system.getPipeline().addLinearAndAngularVelocity(f.first.body, new Vector3d(7, 0, 0), new Vector3d());
            system.getPipeline().addLinearAndAngularVelocity(f.second.body, new Vector3d(-7, 0, 0), new Vector3d());
        });
        helper.runAtTickTime(130, () -> {
            double firstHealth = node(f.level, f.first.armor).health();
            double secondHealth = node(f.level, f.second.armor).health();
            check(helper, firstHealth < 10 || secondHealth < 10, "Native opposing-body contact did not reach authoritative damage resolver");
            Map<String, Object> telemetry = f.service.telemetry(f.first.record.id);
            check(helper, !((List<?>) telemetry.get("recentHits")).isEmpty(), "Real contact must produce combat telemetry");
            check(helper, f.level.getBlockState(f.console.below()).is(Blocks.STONE), "Arena terrain was damaged");
            assertInventories(helper, f);
            f.service.stop(f.firstPlayer, f.arena);
        });
        helper.runAtTickTime(133, () -> {
            check(helper, !BattleSavedData.get(f.level).pending.containsKey(f.arena), "Collision round must restore successfully");
            check(helper, node(f.level, f.first.armor).health() == 160 && node(f.level, f.second.armor).health() == 160,
                    "Both contact targets must return to full integrity");
            assertInventories(helper, f);
            cleanup(f);
            helper.succeed();
        });
    }

    private static Fixture fixture(GameTestHelper helper, String label) {
        ServerLevel level = helper.getLevel();
        // Vanilla places test structures millions of blocks out; native float physics is tested near origin.
        BlockPos base = new BlockPos(256 + 32 * fixtureIndex++, 80, 256);
        ChunkPos chunk = new ChunkPos(base);
        level.getChunkSource().addRegionTicket(TicketType.FORCED, chunk, 2, chunk);
        level.getChunk(chunk.x, chunk.z);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String arena = "test_" + label + "_" + suffix;
        ServerPlayer firstPlayer = makePlayer(level);
        ServerPlayer secondPlayer = makePlayer(level);
        firstPlayer.setGameMode(GameType.CREATIVE);
        secondPlayer.setGameMode(GameType.SURVIVAL);
        // GameTestServer's default operator level is zero; install an explicit referee permission entry.
        level.getServer().getPlayerList().getOps().add(new ServerOpListEntry(firstPlayer.getGameProfile(), 4, false));
        for (BlockPos pos : BlockPos.betweenClosed(base.offset(1, 1, 1), base.offset(14, 1, 14))) {
            level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
        }
        BlockPos console = base.offset(7, 2, 12);
        level.setBlockAndUpdate(console, BattleBlocks.GARAGE.get().defaultBlockState());
        BattleService service = BattleService.get(level);
        service.createArena(firstPlayer, arena, base.offset(1, 1, 1), base.offset(14, 14, 14));
        Bot first = buildBot(level, service, firstPlayer, base.offset(4, 5, 6), true, "left_" + suffix);
        Bot second = buildBot(level, service, secondPlayer, base.offset(11, 5, 6), false, "right_" + suffix);
        service.join(firstPlayer, arena, first.record.name);
        service.join(secondPlayer, arena, second.record.name);
        return new Fixture(level, service, arena, suffix, console, firstPlayer, secondPlayer, first, second);
    }

    private static Bot buildBot(ServerLevel level, BattleService service, ServerPlayer player, BlockPos world, boolean left, String name) {
        BlockPos armor = left ? world.east() : world.west();
        BlockPos relay = world.south();
        BlockPos chest = world.north();
        level.setBlockAndUpdate(world, BattleBlocks.TRANSPONDER.get().defaultBlockState());
        level.setBlockAndUpdate(armor, BattleBlocks.ARMOR.get().defaultBlockState());
        level.setBlockAndUpdate(relay, BattleBlocks.RELAY.get().defaultBlockState());
        level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
        for (BlockPos node : List.of(world, armor, relay)) node(level, node).setOwner(player.getUUID());
        ((ChestBlockEntity) level.getBlockEntity(chest)).setItem(0, new ItemStack(Items.DIAMOND, left ? 17 : 23));
        List<BlockPos> blocks = List.of(world, armor, relay, chest);
        ServerSubLevel body = SubLevelAssemblyHelper.assembleBlocks(level, world, blocks, BoundingBox3i.from(blocks));
        body.updateBoundingBox();
        SubLevelContainer.getContainer(level).addForceLoadTicket(body, SubLevelLoadingTicketType.COMMAND_FORCED, Unit.INSTANCE);
        BlockPos corePlot = body.getPlot().getCenterBlock();
        player.setPos(Vec3.atCenterOf(world).add(0, 1, 0));
        BotRecord record = service.registerBot(player, name, corePlot, "ground");
        return new Bot(body, record, world, corePlot, left ? corePlot.east() : corePlot.west(), corePlot.south(), corePlot.north());
    }

    private static int phase(Fixture fixture) { return fixture.service.consoleData(fixture.console)[0]; }
    private static BattleNodeEntity node(ServerLevel level, BlockPos pos) { return (BattleNodeEntity) level.getBlockEntity(pos); }

    private static ServerPlayer makePlayer(ServerLevel level) {
        UUID id = UUID.randomUUID();
        GameProfile profile = new GameProfile(id, "bb_" + id.toString().substring(0, 8));
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
        ServerPlayer player = new ServerPlayer(level.getServer(), level, profile, cookie.clientInformation());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel channel = new EmbeddedChannel(connection);
        // Vanilla's legacy helper omits NeoForge channel negotiation and rejects Simulated's sync packet.
        // This official NeoForge test API models a compatible modded connection without disabling validation.
        NetworkRegistry.configureMockConnection(connection);
        TEST_CHANNELS.put(id, channel);
        level.getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        return player;
    }

    private static void assertInventories(GameTestHelper helper, Fixture f) {
        ItemStack first = ((ChestBlockEntity) f.level.getBlockEntity(f.first.chest)).getItem(0);
        ItemStack second = ((ChestBlockEntity) f.level.getBlockEntity(f.second.chest)).getItem(0);
        check(helper, first.is(Items.DIAMOND) && first.getCount() == 17, "First inventory was changed, lost, or duplicated");
        check(helper, second.is(Items.DIAMOND) && second.getCount() == 23, "Second inventory was changed, lost, or duplicated");
    }

    private static void denied(GameTestHelper helper, Runnable action, String expectedMessage) {
        try {
            action.run();
        } catch (IllegalArgumentException | IllegalStateException expected) {
            check(helper, expected.getMessage().toLowerCase(java.util.Locale.ROOT).contains(expectedMessage),
                    "Rejected for the wrong reason: " + expected.getMessage());
            return;
        }
        helper.fail("Unauthorized operation unexpectedly succeeded");
    }

    private static void cleanup(Fixture f) {
        f.service.unregisterBot(f.firstPlayer, f.first.record.name);
        f.service.unregisterBot(f.secondPlayer, f.second.record.name);
        f.service.removeArena(f.firstPlayer, f.arena);
        SubLevelContainer.getContainer(f.level).removeForceLoadTicket(f.first.body, SubLevelLoadingTicketType.COMMAND_FORCED, Unit.INSTANCE);
        SubLevelContainer.getContainer(f.level).removeForceLoadTicket(f.second.body, SubLevelLoadingTicketType.COMMAND_FORCED, Unit.INSTANCE);
        SubLevelContainer.getContainer(f.level).removeSubLevel(f.first.body, SubLevelRemovalReason.REMOVED);
        SubLevelContainer.getContainer(f.level).removeSubLevel(f.second.body, SubLevelRemovalReason.REMOVED);
        SubLevelContainer.getContainer(f.level).processSubLevelRemovals();
        ChunkPos chunk = new ChunkPos(f.console);
        f.level.getChunkSource().removeRegionTicket(TicketType.FORCED, chunk, 2, chunk);
        var players = f.level.getServer().getPlayerList();
        players.deop(f.firstPlayer.getGameProfile());
        players.remove(f.firstPlayer);
        players.remove(f.secondPlayer);
        f.extraPlayers.forEach(players::remove);
        var allPlayers = new ArrayList<>(f.extraPlayers);
        allPlayers.add(f.firstPlayer); allPlayers.add(f.secondPlayer);
        for (ServerPlayer player : allPlayers) {
            EmbeddedChannel channel = TEST_CHANNELS.remove(player.getUUID());
            if (channel != null) channel.finishAndReleaseAll();
        }
    }

    private static void check(GameTestHelper helper, boolean condition, String message) {
        if (!condition) helper.fail(message);
    }

    private record Bot(ServerSubLevel body, BotRecord record, BlockPos world,
                       BlockPos core, BlockPos armor, BlockPos relay, BlockPos chest) {}

    private static final class Fixture {
        final ServerLevel level;
        BattleService service;
        final String arena, suffix;
        final BlockPos console;
        final ServerPlayer firstPlayer, secondPlayer;
        final Bot first, second;
        final List<ServerPlayer> extraPlayers = new ArrayList<>();
        Fixture(ServerLevel level, BattleService service, String arena, String suffix, BlockPos console,
                ServerPlayer firstPlayer, ServerPlayer secondPlayer, Bot first, Bot second) {
            this.level = level; this.service = service; this.arena = arena; this.suffix = suffix;
            this.console = console; this.firstPlayer = firstPlayer; this.secondPlayer = secondPlayer;
            this.first = first; this.second = second;
        }
    }
}
