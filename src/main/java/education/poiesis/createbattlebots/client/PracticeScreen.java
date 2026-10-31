package education.poiesis.createbattlebots.client;

import education.poiesis.createbattlebots.practice.PracticeService;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;

/** No commands, registration dialogs or inventory requirements on the first-play path. */
public final class PracticeScreen extends Screen {
    private final List<Button> controls=new ArrayList<>();
    private int phase=-1,left,top,panelWidth;
    public PracticeScreen(){super(Component.literal("Create: Battle Bots"));}
    @Override protected void init(){
        clearWidgets();controls.clear();phase=PracticeClient.state().phase();panelWidth=Math.min(360,width-20);left=(width-panelWidth)/2;top=Math.max(8,(height-230)/2);
        int y=top+(height<235?48:84),half=(panelWidth-30)/2;
        if(phase==0){
            add("Spinner starter",0,left+10,y,half,true);add("Walking starter",1,left+20+half,y,half,true);
        }else{
            var s=PracticeClient.state();
            add("Fight Scrapbot",2,left+10,y,half,s.ready());add("Reset / rematch",3,left+20+half,y,half,phase!=PracticeService.LOADING&&phase!=PracticeService.RECOVERY);
            add("Test drive",8,left+10,y+22,half,s.ready());add("Edit in workshop",4,left+20+half,y+22,half,s.ready()||phase==PracticeService.TUTORIAL);
            add("Skip tutorial",6,left+10,y+44,half,phase==PracticeService.TUTORIAL);add("Retry recovery",7,left+20+half,y+44,half,phase==PracticeService.RECOVERY);
            add("Return to your world",5,left+10,y+66,panelWidth-20,true);
        }
        addRenderableWidget(Button.builder(Component.literal("Close"),b->onClose()).bounds(left+10,Math.min(height-26,y+(phase==0?26:88)),panelWidth-20,20).build());
    }
    private void add(String label,int id,int x,int y,int width,boolean enabled){
        Button button=addRenderableWidget(Button.builder(Component.literal(label),b->{PracticeClient.action(id);onClose();}).bounds(x,y,width,20).build());button.active=enabled;controls.add(button);
    }
    @Override public void tick(){
        if(phase!=PracticeClient.state().phase())init();
        else if(phase!=0){var s=PracticeClient.state();controls.get(0).active=s.ready();controls.get(2).active=s.ready();controls.get(3).active=s.ready()||phase==PracticeService.TUTORIAL;}
    }
    @Override public void render(GuiGraphics g,int mouseX,int mouseY,float partialTick){
        g.fill(0,0,width,height,0xcc0c1217);g.fill(left,top,left+panelWidth,top+3,0xffd4a96b);
        g.drawString(font,"CREATE: BATTLE BOTS",left+10,top+13,0xffe9c488);
        if(height>=235)g.drawString(font,phase==0?"Your first fight starts here.":"Build. Break. Repair. Repeat.",left+10,top+30,0xffeeeeee);
        String message=phase==0?"Choose a starter. Learn to drive, turn and spin a saw, then fight Scrapbot. Your world and inventory stay untouched. Each starter workshop saves separately.":PracticeClient.state().message();
        if(height<235) g.drawString(font,font.plainSubstrByWidth(message,panelWidth-20),left+10,top+32,0xffc5d0d6);
        else g.drawWordWrap(font,Component.literal(message),left+10,top+47,panelWidth-20,0xffc5d0d6);
        if(phase==0&&height>=235)g.drawWordWrap(font,Component.literal("WASD: drive and steer. Space: saw. R: reset (forfeits the round). Creative players can modify their starter between fights."),left+10,top+157,panelWidth-20,0xffaab9c1);
        super.render(g,mouseX,mouseY,partialTick);
    }
    @Override public boolean isPauseScreen(){return false;}
}
