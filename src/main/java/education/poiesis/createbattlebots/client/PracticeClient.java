package education.poiesis.createbattlebots.client;

import com.mojang.blaze3d.platform.InputConstants;
import education.poiesis.createbattlebots.CreateBattleBots;
import education.poiesis.createbattlebots.network.PracticeNetwork;
import education.poiesis.createbattlebots.practice.PracticeService;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/** Client-only controls and a local camera: never creates a server entity or moves a native robot. */
@EventBusSubscriber(modid=CreateBattleBots.MOD_ID,value=Dist.CLIENT)
public final class PracticeClient {
    public static final KeyMapping GARAGE=new KeyMapping("key.createbattlebots.practice",InputConstants.Type.KEYSYM,GLFW.GLFW_KEY_B,"key.categories.createbattlebots");
    public static final KeyMapping RESET=new KeyMapping("key.createbattlebots.reset",InputConstants.Type.KEYSYM,GLFW.GLFW_KEY_R,"key.categories.createbattlebots");
    private static PracticeNetwork.State state=PracticeNetwork.State.closed();
    private static long ticks,received;
    private static int sent=-1;
    private static ArmorStand camera;
    private static CameraType oldCamera;
    private PracticeClient(){}
    public static void init(){PracticeNetwork.clientReceiver(PracticeClient::receive);}
    public static PracticeNetwork.State state(){return state;}
    public static void receive(PracticeNetwork.State packet){
        if(packet.phase()<0||packet.phase()>8||!Double.isFinite(packet.x())||!Double.isFinite(packet.y())||!Double.isFinite(packet.z())||!Double.isFinite(packet.heading()))return;
        state=packet;received=ticks;
    }
    public static void action(int id){PacketDistributor.sendToServer(new PracticeNetwork.Action(id));}
    private static boolean available(Minecraft mc){return mc.player!=null&&mc.level!=null&&PracticeService.isPractice(mc.level)&&state.phase()!=0&&ticks-received<60;}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){
        Minecraft mc=Minecraft.getInstance();ticks++;
        if(mc.player==null||mc.level==null){releaseCamera(mc);state=PracticeNetwork.State.closed();sent=-1;return;}
        while(GARAGE.consumeClick()) {
            if(mc.screen instanceof PracticeScreen)mc.setScreen(null);
            else if(mc.screen==null)mc.setScreen(new PracticeScreen());
        }
        boolean controlling=available(mc)&&state.driving();
        while(RESET.consumeClick())if(controlling&&mc.screen==null)action(3);
        int keys=0;
        if(controlling&&mc.screen==null&&mc.isWindowActive()) {
            if(mc.options.keyUp.isDown())keys|=1;if(mc.options.keyDown.isDown())keys|=2;
            if(mc.options.keyLeft.isDown())keys|=4;if(mc.options.keyRight.isDown())keys|=8;
            if(mc.options.keyJump.isDown())keys|=16;
        }
        if(available(mc)&&(keys!=sent||ticks%2==0)){PacketDistributor.sendToServer(new PracticeNetwork.Input(keys));sent=keys;}
        if(controlling) {
            if(camera==null||camera.level()!=mc.level) {
                releaseCamera(mc);camera=new ArmorStand(mc.level,0,0,0);oldCamera=mc.options.getCameraType();mc.options.setCameraType(CameraType.FIRST_PERSON);
                camera.setPos(state.x()+Math.sin(state.heading())*10,state.y()+8,state.z()+Math.cos(state.heading())*10);
            }
            camera.xo=camera.getX();camera.yo=camera.getY();camera.zo=camera.getZ();camera.yRotO=camera.getYRot();camera.xRotO=camera.getXRot();
            double x=state.x()+Math.sin(state.heading())*10,y=state.y()+8,z=state.z()+Math.cos(state.heading())*10;
            camera.setPos(Mth.lerp(.3,camera.getX(),x),Mth.lerp(.3,camera.getY(),y),Mth.lerp(.3,camera.getZ(),z));
            double dx=state.x()-camera.getX(),dy=state.y()-camera.getY(),dz=state.z()-camera.getZ();
            camera.setYRot((float)Math.toDegrees(Math.atan2(-dx,dz)));camera.setXRot((float)-Math.toDegrees(Math.atan2(dy,Math.hypot(dx,dz))));
            mc.setCameraEntity(camera);
        }else releaseCamera(mc);
    }
    private static void releaseCamera(Minecraft mc){
        if(camera!=null){if(mc.getCameraEntity()==camera)mc.setCameraEntity(mc.player);camera=null;if(oldCamera!=null)mc.options.setCameraType(oldCamera);oldCamera=null;}
    }
    @SubscribeEvent public static void movement(MovementInputUpdateEvent event){
        Minecraft mc=Minecraft.getInstance();
        if(available(mc)&&state.driving()) {event.getInput().forwardImpulse=0;event.getInput().leftImpulse=0;event.getInput().jumping=false;event.getInput().shiftKeyDown=false;}
    }
    @SubscribeEvent public static void interaction(InputEvent.InteractionKeyMappingTriggered event){
        if(available(Minecraft.getInstance())&&state.driving()){event.setCanceled(true);event.setSwingHand(false);}
    }
    @SubscribeEvent public static void hud(RenderGuiEvent.Post event){
        Minecraft mc=Minecraft.getInstance();if(!available(mc)||mc.options.hideGui||mc.screen!=null)return;
        var g=event.getGuiGraphics();int width=Math.min(430,g.guiWidth()-20),x=(g.guiWidth()-width)/2;
        g.fill(x,8,x+width,79,0xc0111a20);g.fill(x,8,x+width,10,0xffd4a96b);
        String phase=switch(state.phase()){case 1->"DRIVING SCHOOL";case 2->"WORKSHOP";case 3->"STARTING IN "+state.seconds();case 4->"FIGHT  "+state.seconds()+"s";case 5->"ROUND OVER";case 6->"RECOVERY PAUSED";case 7->"TEST DRIVE";default->"WORKSHOP LOADING";};
        g.drawCenteredString(mc.font,phase,g.guiWidth()/2,15,0xffe9c488);
        int bar=(width-36)/2;
        g.fill(x+10,30,x+10+bar,35,0xff35434a);g.fill(x+26+bar,30,x+26+bar*2,35,0xff35434a);
        g.fill(x+10,30,x+10+(int)(bar*Mth.clamp(state.health()/120f,0,1)),35,0xff6ecad6);
        g.fill(x+26+bar,30,x+26+bar+(int)(bar*Mth.clamp(state.enemyHealth()/120f,0,1)),35,0xffe5a060);
        g.drawString(mc.font,"YOUR CORE "+state.health()+"   Lost parts: "+state.detached(),x+10,39,0xffc9edf0);
        g.drawString(mc.font,"SCRAPBOT "+state.enemyHealth(),x+26+bar,39,0xffffcf9d);
        g.drawWordWrap(mc.font,net.minecraft.network.chat.Component.literal(state.message()),x+10,53,width-20,0xffeeeeee);
        g.drawCenteredString(mc.font,"WASD drive  |  Space saw  |  B garage  |  R reset (forfeits)",g.guiWidth()/2,g.guiHeight()-24,0xffdddddd);
    }
}
