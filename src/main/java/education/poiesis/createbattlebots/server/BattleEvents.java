package education.poiesis.createbattlebots.server;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityMountEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.PistonEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/** Protection is enforced on the server, including creative players and vanilla automation. */
public final class BattleEvents {
    public static void register() {
        var bus = NeoForge.EVENT_BUS;
        bus.addListener(BattleEvents::tick);
        bus.addListener(BattleEvents::breakBlock);
        bus.addListener(BattleEvents::placeBlock);
        bus.addListener(BattleEvents::fluid);
        bus.addListener(BattleEvents::piston);
        bus.addListener(BattleEvents::explode);
        bus.addListener(BattleEvents::rightClickBlock);
        bus.addListener(BattleEvents::rightClickItem);
        bus.addListener(BattleEvents::interactEntity);
        bus.addListener(BattleEvents::interactEntitySpecific);
        bus.addListener(BattleEvents::attack);
        bus.addListener(BattleEvents::mount);
        bus.addListener(BattleEvents::login);
    }
    private static void tick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level) {
            if (education.poiesis.createbattlebots.practice.PracticeService.isWorkshop(level))
                education.poiesis.createbattlebots.practice.PracticeService.get(level.getServer()).tick();
            else BattleService.get(level).tick();
        }
    }
    private static void breakBlock(BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof ServerLevel level && BattleService.get(level).isProtected(event.getPos())) event.setCanceled(true);
    }
    private static void placeBlock(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel() instanceof ServerLevel level && BattleService.get(level).isProtected(event.getPos())) event.setCanceled(true);
        if (event instanceof BlockEvent.EntityMultiPlaceEvent multi && event.getLevel() instanceof ServerLevel level
                && multi.getReplacedBlockSnapshots().stream().anyMatch(snapshot -> BattleService.get(level).isProtected(snapshot.getPos()))) event.setCanceled(true);
    }
    private static void fluid(BlockEvent.FluidPlaceBlockEvent event) {
        if (event.getLevel() instanceof ServerLevel level && BattleService.get(level).isProtected(event.getPos())) event.setCanceled(true);
    }
    private static void piston(PistonEvent.Pre event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        var service = BattleService.get(level);
        if (service.isProtected(event.getPos()) || service.isProtected(event.getFaceOffsetPos())) { event.setCanceled(true); return; }
        var helper = event.getStructureHelper();
        if (helper != null && helper.resolve() && (helper.getToDestroy().stream().anyMatch(service::isProtected)
                || helper.getToPush().stream().anyMatch(pos -> service.isProtected(pos) || service.isProtected(pos.relative(event.getDirection()))))) event.setCanceled(true);
    }
    private static void explode(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        var service = BattleService.get(level);
        event.getAffectedBlocks().removeIf(service::isProtected);
        event.getAffectedEntities().removeIf(entity -> service.isProtected(entity.blockPosition()));
    }
    private static void rightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getEntity() instanceof ServerPlayer player && !BattleService.get(player.serverLevel()).canInteract(player, event.getPos())) {
            event.setCancellationResult(InteractionResult.FAIL); event.setCanceled(true);
        }
    }
    private static void rightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getEntity() instanceof ServerPlayer player && BattleService.get(player.serverLevel()).isAutonomousPilot(player)) {
            event.setCancellationResult(InteractionResult.FAIL); event.setCanceled(true);
        }
    }
    private static void attack(AttackEntityEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && (BattleService.get(player.serverLevel()).isAutonomousPilot(player)
                || BattleService.get(player.serverLevel()).isProtected(event.getTarget().blockPosition()))) event.setCanceled(true);
    }
    private static void mount(EntityMountEvent event) {
        if (event.isMounting() && event.getEntityMounting() instanceof ServerPlayer player && event.getEntityBeingMounted() != null
                && !BattleService.get(player.serverLevel()).canUseEntity(player, event.getEntityBeingMounted())) event.setCanceled(true);
    }
    private static void interactEntity(PlayerInteractEvent.EntityInteract event) {
        if (event.getEntity() instanceof ServerPlayer player && !BattleService.get(player.serverLevel()).canUseEntity(player, event.getTarget())) {
            event.setCancellationResult(InteractionResult.FAIL); event.setCanceled(true);
        }
    }
    private static void interactEntitySpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (event.getEntity() instanceof ServerPlayer player && !BattleService.get(player.serverLevel()).canUseEntity(player, event.getTarget())) {
            event.setCancellationResult(InteractionResult.FAIL); event.setCanceled(true);
        }
    }
    private static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player)
            for (ServerLevel level : player.server.getAllLevels()) BattleService.get(level).playerJoined(player);
    }
    private BattleEvents() {}
}
