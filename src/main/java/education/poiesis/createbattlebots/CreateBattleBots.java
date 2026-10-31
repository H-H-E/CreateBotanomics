package education.poiesis.createbattlebots;

import com.mojang.logging.LogUtils;
import education.poiesis.createbattlebots.block.BattleBlocks;
import education.poiesis.createbattlebots.command.BattleCommands;
import education.poiesis.createbattlebots.config.BattleConfig;
import education.poiesis.createbattlebots.server.BattleEvents;
import education.poiesis.createbattlebots.server.BattleService;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod(CreateBattleBots.MOD_ID)
public final class CreateBattleBots {
    public static final String MOD_ID = "createbattlebots";
    public static final Logger LOGGER = LogUtils.getLogger();

    public CreateBattleBots(IEventBus bus, ModContainer container) {
        BattleBlocks.register(bus);
        bus.addListener(education.poiesis.createbattlebots.network.PracticeNetwork::register);
        bus.addListener((net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent event) -> event.enqueueWork(() -> {
            // Native loading-ticket types must exist before a saved world's body tickets are read.
            new education.poiesis.createbattlebots.compat.sable.SablePhysicsAdapter();
            education.poiesis.createbattlebots.practice.PracticeRig.init();
        }));
        if (net.neoforged.fml.ModList.get().isLoaded("computercraft"))
            education.poiesis.createbattlebots.compat.computercraft.ComputerCraftCompat.register(bus);
        container.registerConfig(ModConfig.Type.SERVER, BattleConfig.SPEC);
        NeoForge.EVENT_BUS.addListener(BattleCommands::register);
        BattleEvents.register();
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppingEvent event) -> { education.poiesis.createbattlebots.practice.PracticeService.shutdown(); BattleService.shutdown(); });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppedEvent event) -> { education.poiesis.createbattlebots.practice.PracticeService.clear(); BattleService.clear(); });
    }
}
