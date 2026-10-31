package education.poiesis.createbattlebots.menu;

import education.poiesis.createbattlebots.block.BattleBlocks;
import education.poiesis.createbattlebots.core.ControlMode;
import education.poiesis.createbattlebots.server.BattleService;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Vanilla container button packets supply a bounded action ID; all authority stays on the server. */
public final class GarageMenu extends AbstractContainerMenu {
    public static final int START_HUMAN = 0;
    public static final int START_AUTONOMOUS = 1;
    public static final int STOP = 2;
    public static final int RECOVER = 3;
    public static final int STATUS = 4;
    public static final int HELP = 5;
    public static final int SURRENDER = 6;
    private final BlockPos console;
    private final Level level;
    private final Player viewer;
    private final ContainerData data = new SimpleContainerData(5);
    private long lastActionTick = Long.MIN_VALUE;

    public GarageMenu(int windowId, Inventory inventory, BlockPos console) {
        super(BattleBlocks.GARAGE_MENU.get(), windowId);
        this.viewer = inventory.player;
        this.level = viewer.level();
        this.console = console.immutable();
        addDataSlots(data);
    }

    @Override public boolean stillValid(Player player) {
        if (player.level() != level || !level.hasChunkAt(console)
                || player.distanceToSqr(Vec3.atCenterOf(console)) > 4.5 * 4.5) return false;
        var state = level.getBlockState(console);
        return state.is(BattleBlocks.GARAGE.get()) || state.is(BattleBlocks.ARENA_CONTROLLER.get());
    }

    @Override public void broadcastChanges() {
        if (level instanceof ServerLevel server) {
            int[] state = BattleService.get(server).consoleData(console);
            for (int i = 0; i < 4; i++) data.set(i, Math.max(0, Math.min(32767, i < state.length ? state[i] : 0)));
            data.set(4, viewer.hasPermissions(2) ? 1 : 0);
        }
        super.broadcastChanges();
    }

    @Override public boolean clickMenuButton(Player player, int action) {
        if (!(player instanceof ServerPlayer serverPlayer) || !(level instanceof ServerLevel server)
                || !stillValid(player) || action < START_HUMAN || action > SURRENDER) return false;
        long tick = level.getGameTime();
        if (lastActionTick != Long.MIN_VALUE && tick - lastActionTick < 5) return false;
        lastActionTick = tick;
        BattleService service = BattleService.get(server);
        try {
            if (action <= RECOVER && !serverPlayer.hasPermissions(2)) {
                throw new IllegalArgumentException("Only an operator can start, stop or recover matches.");
            }
            if (action == HELP) {
                service.showHelp(serverPlayer);
                return true;
            }
            if (action == SURRENDER) {
                service.surrender(serverPlayer);
                return true;
            }
            String arena = service.arenaAt(Vec3.atCenterOf(console)).orElseThrow(() ->
                    new IllegalArgumentException("This console must be inside a registered arena. Use /battlebots help.")).name;
            switch (action) {
                case START_HUMAN -> service.start(serverPlayer, arena, ControlMode.HUMAN);
                case START_AUTONOMOUS -> service.start(serverPlayer, arena, ControlMode.AUTONOMOUS);
                case STOP -> service.stop(serverPlayer, arena);
                case RECOVER -> service.recover(serverPlayer, arena);
                case STATUS -> serverPlayer.sendSystemMessage(service.status(arena));
                default -> { return false; }
            }
            broadcastChanges();
            return true;
        } catch (IllegalArgumentException | IllegalStateException error) {
            serverPlayer.sendSystemMessage(Component.literal(error.getMessage() == null
                    ? "Action could not be completed." : error.getMessage()).withStyle(ChatFormatting.RED));
            return false;
        }
    }

    public int phase() { return data.get(0); }
    public int secondsRemaining() { return data.get(1); }
    public int occupiedSlots() { return data.get(2); }
    public boolean hasArena() { return data.get(3) != 0; }
    public boolean canReferee() { return data.get(4) != 0; }
    public BlockPos console() { return console; }

    @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
}
