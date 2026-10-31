package education.poiesis.createbattlebots.practice;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;

/** Practice prefabs are not a source of free survival items or an editable public dimension. */
public final class PracticeEvents {
    public static void register() {
        var bus=NeoForge.EVENT_BUS;
        bus.addListener((PlayerEvent.PlayerLoggedInEvent e)-> {
            if(e.getEntity() instanceof ServerPlayer player) PracticeService.restorePlayer(player);
        });
        bus.addListener((BlockEvent.BreakEvent e)-> { if(e.getLevel() instanceof net.minecraft.world.level.Level l && PracticeService.isWorkshop(l)) e.setCanceled(true); });
        bus.addListener((BlockEvent.EntityPlaceEvent e)-> { if(e.getLevel() instanceof net.minecraft.world.level.Level l && PracticeService.isWorkshop(l)) e.setCanceled(true); });
        bus.addListener((PlayerInteractEvent.RightClickItem e)-> { if(PracticeService.isWorkshop(e.getLevel())) e.setCanceled(true); });
        bus.addListener((PlayerInteractEvent.RightClickBlock e)-> { if(PracticeService.isWorkshop(e.getLevel())) e.setCanceled(true); });
        bus.addListener((ExplosionEvent.Detonate e)-> {
            if(PracticeService.isWorkshop(e.getLevel())) { e.getAffectedBlocks().clear(); e.getAffectedEntities().clear(); }
        });
    }
    private PracticeEvents() {}
}
