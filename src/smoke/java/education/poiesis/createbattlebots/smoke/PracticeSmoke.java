package education.poiesis.createbattlebots.smoke;

import education.poiesis.createbattlebots.client.PracticeScreen;
import education.poiesis.createbattlebots.practice.PracticeService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;
import java.nio.file.*;

/** Drives the shipped overlay and packets against the packaged JAR, not direct server methods. */
@Mod(value="createbattlebots_smoke",dist=Dist.CLIENT)
public final class PracticeSmoke {
    private int ticks, stage, age, fights;
    private boolean paused, resumed, sawLoss;
    private String originalDimension;
    public PracticeSmoke() { NeoForge.EVENT_BUS.addListener(this::tick); }
    private void tick(ClientTickEvent.Post event) {
        var mc=Minecraft.getInstance();
        if (++ticks > 6200) { fail("Timed out at stage "+stage+" phase "+PracticeScreen.state.phase());return; }
        if(mc.player==null || mc.level==null) return;
        try {
            age++;
            if(stage==0 && age>40) {
                originalDimension=mc.level.dimension().location().toString();
                GLFW.glfwFocusWindow(mc.getWindow().getWindow());
                mc.setScreen(new PracticeScreen());stage=1;age=0;
            } else if(stage==1 && age>15) { capture("01-entry");click("Enter tutorial");stage=2;age=0; }
            else if(stage==2) {
                if(PracticeScreen.state.phase()==PracticeService.ERROR) throw new IllegalStateException(PracticeScreen.state.feedback());
                if(age==30) capture("02-tutorial");
                if(PracticeScreen.state.phase()==PracticeService.TUTORIAL && mc.screen instanceof PracticeScreen screen) {
                    release();
                    String objective=PracticeScreen.state.objective();
                    press(objective.startsWith("1")?GLFW.GLFW_KEY_W:objective.startsWith("2")?GLFW.GLFW_KEY_A:GLFW.GLFW_KEY_SPACE);
                } else if(PracticeScreen.state.phase()==PracticeService.GARAGE) {
                    release();capture("03-tutorial-completed");click("Fight AI");stage=3;age=0;
                }
            } else if(stage==3) {
                int phase=PracticeScreen.state.phase();
                if(phase==PracticeService.ERROR) throw new IllegalStateException(PracticeScreen.state.feedback());
                sawLoss |= PracticeScreen.state.lost()>0 || PracticeScreen.state.enemyLost()>0;
                if(phase==PracticeService.ACTIVE) {
                    if(age==120 && !paused) {release();click("Pause");paused=true;}
                    if(!paused || resumed) { press(GLFW.GLFW_KEY_W); press(GLFW.GLFW_KEY_SPACE); }
                    if(age==160) capture("04-live-fight");
                    if(age>1200) { release();click("Surrender"); }
                } else if(phase==PracticeService.PAUSED && paused && age>150) {capture("05-paused");click("Resume");resumed=true;}
                else if(phase==PracticeService.RESULTS) {
                    release();capture("06-result-"+fights);fights++;
                    if(fights==1) {click("Rematch");age=0;}
                    else {click("Design:");stage=4;age=0;}
                }
            } else if(stage==4 && age>20) {
                click("Design:");stage=5;age=0;
            } else if(stage==5 && age>15) {
                if(PracticeScreen.state.design()!=2) throw new IllegalStateException("Crawler selection did not reach the server");
                press(GLFW.GLFW_KEY_W);press(GLFW.GLFW_KEY_SPACE);
                if(age==100) capture("07-crawler");
                if(age>120) {release();click("Return to world");stage=6;age=0;}
            } else if(stage==6 && age>30) {
                if(!mc.level.dimension().location().toString().equals(originalDimension)) throw new IllegalStateException("Player did not return to original dimension");
                if(PracticeScreen.state.phase()!=0) throw new IllegalStateException("Practice HUD did not close");
                capture("08-returned");
                Files.writeString(Path.of("smoke-success.txt"),"PASS: real packaged client entered tutorial, drove and spun weapon, finished tutorial, fought AI, completed two result/repair cycles, rematched, selected and drove crawler, and returned to original world.\nObserved part loss: "+sawLoss+"\nPause exercised: "+paused+" / resume: "+resumed+"\n");
                stage=7;age=0;
            } else if(stage==7 && age>30) {mc.stop();stage=8;}
        } catch(Exception e) {fail(e.toString());}
    }
    private void click(String prefix) {
        var screen=Minecraft.getInstance().screen;
        if(screen==null) throw new IllegalStateException("No screen for "+prefix);
        for(var child:screen.children()) if(child instanceof Button b && b.getMessage().getString().startsWith(prefix)) {
            if(!b.active) throw new IllegalStateException("Disabled button "+prefix);
            b.onPress();return;
        }
        throw new IllegalStateException("Button not found: "+prefix);
    }
    private void press(int key) { var screen=Minecraft.getInstance().screen;if(screen instanceof PracticeScreen) screen.keyPressed(key,0,0); }
    private void release() {var screen=Minecraft.getInstance().screen;if(screen!=null)for(int k:new int[]{GLFW.GLFW_KEY_W,GLFW.GLFW_KEY_A,GLFW.GLFW_KEY_D,GLFW.GLFW_KEY_SPACE})screen.keyReleased(k,0,0);}
    private void capture(String name) {var mc=Minecraft.getInstance();Screenshot.grab(mc.gameDirectory,name+".png",mc.getMainRenderTarget(),ignored->{});}
    private void fail(String reason) {try{Files.writeString(Path.of("smoke-failure.txt"),reason);}catch(Exception ignored){}Minecraft.getInstance().stop();stage=8;}
}
