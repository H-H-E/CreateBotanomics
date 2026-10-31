package education.poiesis.createbattlebots.client;

import education.poiesis.createbattlebots.core.MatchState;
import education.poiesis.createbattlebots.menu.GarageMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import java.util.ArrayList;
import java.util.List;

/** A compact garage panel using native controls and fonts; no external texture dependencies. */
public final class GarageScreen extends AbstractContainerScreen<GarageMenu> {
    private static final int BACKGROUND = 0xff161d22;
    private static final int PANEL = 0xff222f35;
    private static final int COPPER = 0xffd4a96b;
    private static final int TEXT = 0xffe7eeee;
    private static final int MUTED = 0xffadbec5;
    private final List<Button> refereeButtons = new ArrayList<>();
    private boolean compact;

    public GarageScreen(GarageMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 300;
        imageHeight = 220;
    }

    @Override protected void init() {
        compact = height < 230;
        imageHeight = compact ? 176 : 220;
        super.init();
        refereeButtons.clear();
        int row = compact ? 64 : 87;
        int gap = compact ? 23 : 26;
        addAction("human", GarageMenu.START_HUMAN, 18, row, 127, true);
        addAction("autonomous", GarageMenu.START_AUTONOMOUS, 155, row, 127, true);
        addAction("stop", GarageMenu.STOP, 18, row + gap, 127, true);
        addAction("recover", GarageMenu.RECOVER, 155, row + gap, 127, true);
        addAction("status", GarageMenu.STATUS, 18, row + 2 * gap, 127, false);
        addAction("help", GarageMenu.HELP, 155, row + 2 * gap, 127, false);
        addAction("surrender", GarageMenu.SURRENDER, 18, row + 3 * gap, 264, false);
    }

    private void addAction(String key, int action, int x, int y, int width, boolean refereeOnly) {
        Button button = addRenderableWidget(Button.builder(Component.translatable("screen.createbattlebots." + key), ignored -> {
            if (minecraft != null && minecraft.gameMode != null) {
                minecraft.gameMode.handleInventoryButtonClick(menu.containerId, action);
            }
        }).bounds(leftPos + x, topPos + y, width, compact ? 18 : 20)
                .tooltip(Tooltip.create(Component.translatable("screen.createbattlebots." + key + ".hint"))).build());
        if (refereeOnly) {
            refereeButtons.add(button);
            button.active = menu.canReferee() && menu.hasArena();
        }
    }

    @Override protected void containerTick() {
        super.containerTick();
        refereeButtons.forEach(button -> button.active = menu.canReferee() && menu.hasArena());
    }

    @Override protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(leftPos - 1, topPos - 1, leftPos + imageWidth + 1, topPos + imageHeight + 1, COPPER);
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, BACKGROUND);
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + 3, COPPER);
        graphics.fill(leftPos + 18, topPos + (compact ? 30 : 40), leftPos + imageWidth - 18, topPos + (compact ? 57 : 77), PANEL);
        int divider = compact ? 155 : 195;
        graphics.fill(leftPos + 18, topPos + divider, leftPos + imageWidth - 18, topPos + divider + 1, 0xff35434a);
    }

    @Override protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, Component.translatable("screen.createbattlebots.brand"), 18, compact ? 7 : 12, COPPER, false);
        graphics.drawString(font, title, 18, compact ? 18 : 26, TEXT, false);
        graphics.drawString(font, Component.translatable("screen.createbattlebots.phase", phaseText()), 26, compact ? 35 : 48, TEXT, false);
        graphics.drawString(font, Component.translatable("screen.createbattlebots.roster", menu.occupiedSlots()), 26, compact ? 47 : 64, MUTED, false);
        String time = String.format(java.util.Locale.ROOT, "%d:%02d", menu.secondsRemaining() / 60, menu.secondsRemaining() % 60);
        graphics.drawString(font, time, imageWidth - 26 - font.width(time), compact ? 35 : 48, COPPER, false);
        Component footer = Component.translatable(menu.canReferee()
                ? "screen.createbattlebots.footer" : "screen.createbattlebots.operator_hint");
        graphics.drawCenteredString(font, footer, imageWidth / 2, compact ? 163 : 205, MUTED);
    }

    private Component phaseText() {
        if (!menu.hasArena()) return Component.translatable("screen.createbattlebots.no_arena");
        if (menu.phase() == 0) return Component.translatable("screen.createbattlebots.idle");
        int index = menu.phase() - 1;
        if (index < 0 || index >= MatchState.values().length) return Component.literal("?");
        return Component.translatable("match.createbattlebots." + MatchState.values()[index].name().toLowerCase(java.util.Locale.ROOT));
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
    }
}
