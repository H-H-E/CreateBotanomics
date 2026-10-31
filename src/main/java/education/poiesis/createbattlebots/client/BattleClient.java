package education.poiesis.createbattlebots.client;

import education.poiesis.createbattlebots.CreateBattleBots;
import education.poiesis.createbattlebots.block.BattleBlocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/** This class and every screen reference are loaded only on the physical client. */
@EventBusSubscriber(modid = CreateBattleBots.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class BattleClient {
    private BattleClient() {}

    @SubscribeEvent
    public static void keys(net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent event) {
        event.register(PracticeClient.GARAGE); event.register(PracticeClient.RESET); PracticeClient.init();
    }

    @SubscribeEvent
    public static void screens(RegisterMenuScreensEvent event) {
        event.register(BattleBlocks.GARAGE_MENU.get(), GarageScreen::new);
    }
}
