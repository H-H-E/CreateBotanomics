package education.poiesis.createbattlebots.client;

import education.poiesis.createbattlebots.practice.PracticeNetwork;
import education.poiesis.createbattlebots.practice.PracticeRules;
import education.poiesis.createbattlebots.practice.PracticeService;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;
import java.util.ArrayList;
import java.util.List;

/** Transparent remote-pilot overlay. Escape pauses the bay, never leaves held controls behind. */
public final class PracticeScreen extends Screen {
    public static PracticeNetwork.State state = PracticeNetwork.State.closed();
    private int flags, builtPhase = -1;
    private final List<Button> setup = new ArrayList<>();
    public PracticeScreen() { super(Component.literal("Create: Battle Bots — Solo Practice")); }
    @Override protected void init() { rebuild(); }
    private void action(int code) { flags = 0; PacketDistributor.sendToServer(new PracticeNetwork.Action(code)); }
    private Button button(String label, int code, int x, int y, int w) {
        return addRenderableWidget(Button.builder(Component.literal(label), ignored -> action(code)).bounds(x, y, w, 20).build());
    }
    private void rebuild() {
        clearWidgets(); setup.clear(); builtPhase = state.phase();
        int center = width / 2;
        if (state.phase() == 0) {
            button("Enter tutorial + AI arena", PracticeService.ENTER, center-120, height/2+22, 240);
            addRenderableWidget(Button.builder(Component.literal("Back to world"), ignored -> minecraft.setScreen(null))
                    .bounds(center-120, height/2+48, 240, 20).build());
            return;
        }
        int y = height - 27;
        button("Return to world", PracticeService.EXIT, width-120, y, 110);
        if (state.phase() == PracticeService.PAUSED) {
            button("Resume", PracticeService.RESUME, center-100, height/2, 200); return;
        }
        if (state.phase() == PracticeService.ACTIVE) {
            button("Surrender", PracticeService.SURRENDER, 10, y, 100);
            button("Pause", PracticeService.PAUSE, 115, y, 70); return;
        }
        if (state.phase() == PracticeService.COUNTDOWN || state.phase() == PracticeService.REPAIRING) return;
        if (state.phase() == PracticeService.TUTORIAL) {
            button("Skip tutorial", PracticeService.SKIP, 10, y, 115);
            button("Reset bot", PracticeService.REPAIR, 130, y, 85); return;
        }
        Button fight = button(state.phase() == PracticeService.RESULTS ? "Rematch" : "Fight AI", PracticeService.FIGHT, 10, y, 95);
        fight.active = state.phase() != PracticeService.ERROR;
        button("Test / repair", PracticeService.REPAIR, 110, y, 105);
        int row = height-53;
        setup.add(button("Design: " + design(), PracticeService.DESIGN, 10, row, 140));
        setup.add(button(state.easy() ? "AI: Rookie" : "AI: Rival", PracticeService.DIFFICULTY, 155, row, 100));
        setup.add(button("Setup: " + tune(), PracticeService.TUNE, 260, row, 135));
    }
    private String design() { return switch (state.design()) { case 1 -> "Buzzsaw"; case 2 -> "Crank crawler"; default -> "Spinner"; }; }
    private String tune() { return switch (state.tune()) { case 1 -> "Armored"; case 2 -> "Overclock"; default -> "Balanced"; }; }
    @Override public void tick() {
        if (minecraft == null || minecraft.player == null) return;
        if (builtPhase != state.phase()) rebuild();
        if (setup.size() == 3) {
            setup.get(0).setMessage(Component.literal("Design: " + design()));
            setup.get(1).setMessage(Component.literal(state.easy() ? "AI: Rookie" : "AI: Rival"));
            setup.get(2).setMessage(Component.literal("Setup: " + tune()));
        }
        if (GLFW.glfwGetWindowAttrib(minecraft.getWindow().getWindow(), GLFW.GLFW_FOCUSED) == 0) flags = 0;
        if (state.phase() != 0) PacketDistributor.sendToServer(new PracticeNetwork.Input(flags));
    }
    private int flag(int key, int scan) {
        var o = minecraft.options;
        if (o.keyUp.matches(key,scan)) return PracticeRules.FORWARD;
        if (o.keyDown.matches(key,scan)) return PracticeRules.BACK;
        if (o.keyLeft.matches(key,scan)) return PracticeRules.LEFT;
        if (o.keyRight.matches(key,scan)) return PracticeRules.RIGHT;
        if (o.keyJump.matches(key,scan)) return PracticeRules.WEAPON;
        if (o.keyShift.matches(key,scan)) return PracticeRules.BRAKE;
        return 0;
    }
    @Override public boolean keyPressed(int key, int scan, int mods) {
        if (state.phase() != 0) {
            if (key == GLFW.GLFW_KEY_ESCAPE) { action(state.phase() == PracticeService.PAUSED ? PracticeService.RESUME : PracticeService.PAUSE); return true; }
            int bit = flag(key,scan); if (bit != 0) { flags |= bit; return true; }
        }
        return super.keyPressed(key,scan,mods);
    }
    @Override public boolean keyReleased(int key, int scan, int mods) {
        flags &= ~flag(key,scan); return super.keyReleased(key,scan,mods);
    }
    @Override public void removed() {
        flags = 0;
        if (minecraft != null && minecraft.getConnection() != null && state.phase() != 0) {
            PacketDistributor.sendToServer(new PracticeNetwork.Input(0));
            PacketDistributor.sendToServer(new PracticeNetwork.Action(PracticeService.PAUSE));
        }
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partial) { }
    @Override public void render(GuiGraphics g, int mx, int my, float partial) {
        if (builtPhase != state.phase()) rebuild();
        if (state.phase() == 0) {
            g.fill(0, 0, width, height, 0xc9151e25);
            g.drawCenteredString(font, "CREATE: BATTLE BOTS", width/2, height/2-84, 0xffeac38b);
            g.drawCenteredString(font, "A short driving lesson. A real physics fight. A free rematch.", width/2, height/2-62, 0xffffffff);
            g.drawCenteredString(font, "Practice uses disposable starter robots in a separate arena.", width/2, height/2-42, 0xffb9c8ce);
            g.drawCenteredString(font, "Your world, inventory, and existing machines stay untouched.", width/2, height/2-28, 0xffb9c8ce);
            g.drawCenteredString(font, "No commands, coding, or second player needed.", width/2, height/2-9, 0xffeac38b);
        } else {
            g.fill(6,6,width-6,75,0xbb142029);
            g.drawString(font,"YOU (GREEN)",14,13,0xffb1ef8e);
            g.drawString(font,"SCRAPBOT (RED)",width-144,13,0xffff9b8d);
            bar(g,14,26,120,state.health(),0xff83d567);
            bar(g,width-144,26,120,state.enemyHealth(),0xffe27765);
            g.drawString(font,"Parts lost: " + state.lost(),14,39,0xffffffff);
            g.drawString(font,"Parts lost: " + state.enemyLost(),width-144,39,0xffffffff);
            g.drawCenteredString(font,String.format(java.util.Locale.ROOT,"%d:%02d",state.seconds()/60,state.seconds()%60),width/2,14,0xffeac38b);
            g.drawCenteredString(font,state.wins()+" wins / "+state.losses()+" losses  |  "+state.rpm()+" RPM",width/2,28,0xffdce6eb);
            g.drawCenteredString(font,state.objective(),width/2,58,0xffffffff);
            int bottom = height-94;
            g.fill(6,bottom-4,width-6,height-4,0xb3142029);
            g.drawWordWrap(font,Component.literal(state.feedback()),12,bottom,Math.max(160,width-24),0xffeac38b);
            var o=minecraft.options;
            String controls=o.keyUp.getTranslatedKeyMessage().getString()+"/"+o.keyDown.getTranslatedKeyMessage().getString()+" drive   "
                    +o.keyLeft.getTranslatedKeyMessage().getString()+"/"+o.keyRight.getTranslatedKeyMessage().getString()+" turn   "
                    +o.keyJump.getTranslatedKeyMessage().getString()+" weapon   "+o.keyShift.getTranslatedKeyMessage().getString()+" brake   Esc pause";
            g.drawCenteredString(font,controls,width/2,height-65,0xffd3dce2);
            if (state.phase()==PracticeService.COUNTDOWN || state.phase()==PracticeService.PAUSED || state.phase()==PracticeService.REPAIRING)
                g.drawCenteredString(font,state.phase()==PracticeService.PAUSED?"PAUSED":state.phase()==PracticeService.REPAIRING?"REATTACHING PARTS...":state.objective(),width/2,height/2-25,0xffeac38b);
        }
        super.render(g,mx,my,partial);
    }
    private void bar(GuiGraphics g,int x,int y,int w,int value,int color) {
        g.fill(x,y,x+w,y+7,0xff32414a); g.fill(x,y,x+Math.round(w*Math.max(0,Math.min(100,value))/100f),y+7,color);
    }
}
