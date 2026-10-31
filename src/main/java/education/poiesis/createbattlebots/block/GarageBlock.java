package education.poiesis.createbattlebots.block;

import education.poiesis.createbattlebots.menu.GarageMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public final class GarageBlock extends Block {
    public GarageBlock(Properties properties) { super(properties); }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer serverPlayer) serverPlayer.openMenu(new SimpleMenuProvider(
                (id, inventory, ignored) -> new GarageMenu(id, inventory, pos), Component.translatable("screen.createbattlebots.garage")), pos);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
