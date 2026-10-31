package education.poiesis.createbattlebots.practice;

import education.poiesis.createbattlebots.CreateBattleBots;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityMountEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.PistonEvent;

/** Practice is an isolated arena, not a creative-item or survival-health bypass. */
@EventBusSubscriber(modid=CreateBattleBots.MOD_ID)
public final class PracticeEvents {
    private PracticeEvents(){}
    @SubscribeEvent public static void breakBlock(BlockEvent.BreakEvent e) {
        if(e.getLevel() instanceof ServerLevel level && PracticeService.isPractice(level))
            if(!(e.getPlayer() instanceof ServerPlayer p) || !PracticeService.get(level).canBreak(p,e.getPos()))e.setCanceled(true);
    }
    @SubscribeEvent public static void place(BlockEvent.EntityPlaceEvent e) {
        if(e.getLevel() instanceof ServerLevel level && PracticeService.isPractice(level)) {
            if(!(e.getEntity() instanceof ServerPlayer p) || !PracticeService.get(level).canEdit(p,e.getPos()))e.setCanceled(true);
            else if(e instanceof BlockEvent.EntityMultiPlaceEvent multi && multi.getReplacedBlockSnapshots().stream()
                    .anyMatch(s->!PracticeService.get(level).canEdit(p,s.getPos())))e.setCanceled(true);
        }
    }
    @SubscribeEvent public static void rightBlock(PlayerInteractEvent.RightClickBlock e) {
        if(e.getEntity() instanceof ServerPlayer p && PracticeService.isPractice(p.level()) && !PracticeService.get(p.serverLevel()).canEdit(p,e.getPos())) {
            e.setCancellationResult(InteractionResult.FAIL);e.setCanceled(true);
        }
    }
    @SubscribeEvent public static void rightItem(PlayerInteractEvent.RightClickItem e) {
        if(e.getEntity() instanceof ServerPlayer p && PracticeService.isPractice(p.level()) && PracticeService.get(p.serverLevel()).isDriving(p)) {
            e.setCancellationResult(InteractionResult.FAIL);e.setCanceled(true);
        }
    }
    @SubscribeEvent public static void attack(AttackEntityEvent e) {
        if(PracticeService.isPractice(e.getEntity().level()))e.setCanceled(true);
    }
    @SubscribeEvent public static void mount(EntityMountEvent e) {
        if(e.isMounting() && e.getEntityMounting() instanceof ServerPlayer p && PracticeService.isPractice(p.level()))e.setCanceled(true);
    }
    @SubscribeEvent public static void interact(PlayerInteractEvent.EntityInteract e) {
        if(PracticeService.isPractice(e.getEntity().level())) {e.setCancellationResult(InteractionResult.FAIL);e.setCanceled(true);}
    }
    @SubscribeEvent public static void interactSpecific(PlayerInteractEvent.EntityInteractSpecific e) {
        if(PracticeService.isPractice(e.getEntity().level())) {e.setCancellationResult(InteractionResult.FAIL);e.setCanceled(true);}
    }
    @SubscribeEvent public static void hurt(LivingIncomingDamageEvent e) {
        if(e.getEntity() instanceof ServerPlayer p && PracticeService.isPractice(p.level()))e.setCanceled(true);
    }
    @SubscribeEvent public static void piston(PistonEvent.Pre e) {
        if(e.getLevel() instanceof ServerLevel level && PracticeService.isPractice(level))e.setCanceled(true);
    }
    @SubscribeEvent public static void fluid(BlockEvent.FluidPlaceBlockEvent e) {
        if(e.getLevel() instanceof ServerLevel level && PracticeService.isPractice(level))e.setCanceled(true);
    }
    @SubscribeEvent public static void explosion(ExplosionEvent.Detonate e) {
        if(PracticeService.isPractice(e.getLevel())){e.getAffectedBlocks().clear();e.getAffectedEntities().clear();}
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent e) {
        if(e.getEntity() instanceof ServerPlayer p)PracticeService.logout(p);
    }
    @SubscribeEvent public static void login(PlayerEvent.PlayerLoggedInEvent e) {
        if(e.getEntity() instanceof ServerPlayer p) {
            if(PracticeService.isPractice(p.level()))PracticeService.get(p.serverLevel()).leave(p);
            if(!p.getPersistentData().getBoolean("createbattlebots:practice_hint")) {
                p.sendSystemMessage(net.minecraft.network.chat.Component.literal("Create: Battle Bots - press B for a short driving tutorial and your first fight against Scrapbot."));
                p.getPersistentData().putBoolean("createbattlebots:practice_hint",true);
            }
        }
    }
}
