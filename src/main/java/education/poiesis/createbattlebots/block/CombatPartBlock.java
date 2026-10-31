package education.poiesis.createbattlebots.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Solid, damage-sensing native modules. Their bodies persist when their joints detach. */
public final class CombatPartBlock extends BattleNodeBlock {
    private final boolean wheel;
    private static final VoxelShape WHEEL = Shapes.or(box(4,2,4,12,14,12),
            box(4,4,2,12,12,14), box(4,6,0,12,10,16), box(4,0,6,12,16,10));
    private static final VoxelShape SAW = Shapes.or(box(1,6,3,15,10,13),
            box(3,6,1,13,10,15), box(0,6,6,16,10,10), box(6,6,0,10,10,16));
    public CombatPartBlock(Properties properties, boolean wheel) {
        super(properties, wheel ? Kind.RELAY : Kind.ARMOR); this.wheel=wheel;
    }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return wheel ? WHEEL : SAW;
    }
    @Override protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return wheel ? WHEEL : SAW;
    }
}
