package education.poiesis.createbattlebots.client;

import education.poiesis.createbattlebots.CreateBattleBots;
import education.poiesis.createbattlebots.practice.PracticeNetwork;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid=CreateBattleBots.MOD_ID,bus=EventBusSubscriber.Bus.MOD,value=Dist.CLIENT)
public final class PracticeClient {
    private static final KeyMapping OPEN = new KeyMapping("key.createbattlebots.practice",GLFW.GLFW_KEY_F8,"key.categories.createbattlebots");
    @SubscribeEvent public static void keys(RegisterKeyMappingsEvent event) { event.register(OPEN); }
    @SubscribeEvent public static void setup(FMLClientSetupEvent event) {
        PracticeNetwork.clientState = state -> {
            var mc=Minecraft.getInstance();
            PracticeScreen.state=state;
            if(state.phase()==0) { if(mc.screen instanceof PracticeScreen) mc.setScreen(null); }
            else if(mc.player!=null && !(mc.screen instanceof PracticeScreen)) mc.setScreen(new PracticeScreen());
        };
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e)-> {
            var mc=Minecraft.getInstance();
            while(OPEN.consumeClick()) if(mc.player!=null && mc.getConnection()!=null) mc.setScreen(new PracticeScreen());
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e)->PracticeScreen.state=PracticeNetwork.State.closed());
        NeoForge.EVENT_BUS.addListener((ScreenEvent.Init.Post e)-> {
            if(e.getScreen() instanceof PauseScreen screen) {
                e.addListener(Button.builder(Component.literal("Battle Bots practice"),b->Minecraft.getInstance().setScreen(new PracticeScreen()))
                        .bounds(6,6,142,20).build());
            }
        });
    }
    private PracticeClient() {}
}
