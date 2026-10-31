package education.poiesis.createbattlebots.compat.sable;

import dev.ryanhcode.sable.api.block.BlockWithSubLevelCollisionCallback;
import dev.ryanhcode.sable.api.physics.callback.BlockSubLevelCollisionCallback;
import net.minecraft.world.level.block.Block;

/** Base for battle hit zones using Sable's native contact callback, without a mixin. */
public class BattleContactBlock extends Block implements BlockWithSubLevelCollisionCallback {
    public BattleContactBlock(Properties properties) {
        super(properties);
    }

    @Override
    public final BlockSubLevelCollisionCallback sable$getCallback() {
        return SablePhysicsAdapter.collisionCallback();
    }
}
