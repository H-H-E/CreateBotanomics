package education.poiesis.createbattlebots.block;

import education.poiesis.createbattlebots.compat.sable.BattleContactBlock;
import education.poiesis.createbattlebots.server.BattleService;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;

/** A physical collision sensor. Broken relays emit 15 to disengage an attached Create clutch. */
public class BattleNodeBlock extends BattleContactBlock implements EntityBlock {
    public enum Kind { CORE, ARMOR, RELAY }
    public static final BooleanProperty DISABLED = BooleanProperty.create("disabled");
    private final Kind kind;
    public BattleNodeBlock(Properties properties, Kind kind) {
        super(properties);
        this.kind = kind;
        registerDefaultState(stateDefinition.any().setValue(DISABLED, false));
    }
    public Kind kind() { return kind; }
    public double maximumHealth() { return kind == Kind.ARMOR ? 160 : kind == Kind.CORE ? 120 : 80; }
    public double toughness() { return kind == Kind.ARMOR ? 2 : kind == Kind.CORE ? 1.5 : 1; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) { builder.add(DISABLED); }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new BattleNodeEntity(pos, state); }
    @Override protected boolean isSignalSource(BlockState state) { return kind == Kind.RELAY; }
    @Override protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return kind == Kind.RELAY && state.getValue(DISABLED) ? 15 : 0;
    }
    @Override protected net.minecraft.world.phys.shapes.VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, net.minecraft.world.phys.shapes.CollisionContext context) {
        // Sable retires a body with no remaining collision shape. A thin remnant preserves its
        // identity and joints while removing almost all of this armor's shielding surface.
        return kind == Kind.ARMOR && state.getValue(DISABLED)
                ? net.minecraft.world.phys.shapes.Shapes.box(0, 0, 0, 1, 0.0625, 1)
                : super.getCollisionShape(state, level, pos, context);
    }
    @Override public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide && placer instanceof Player player && level.getBlockEntity(pos) instanceof BattleNodeEntity node) node.setOwner(player.getUUID());
    }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level instanceof ServerLevel server && player instanceof ServerPlayer sp) BattleService.get(server).inspectNode(sp, pos);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
    @Override public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("tooltip.createbattlebots." + kind.name().toLowerCase(java.util.Locale.ROOT)));
    }
}
