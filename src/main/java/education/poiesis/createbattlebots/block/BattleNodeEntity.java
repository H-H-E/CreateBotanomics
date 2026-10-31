package education.poiesis.createbattlebots.block;

import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class BattleNodeEntity extends BlockEntity {
    private double health;
    @Nullable private UUID owner;
    @Nullable private UUID bot;
    public BattleNodeEntity(BlockPos pos, BlockState state) {
        super(BattleBlocks.NODE_ENTITY.get(), pos, state);
        health = maximumHealth();
    }
    public double maximumHealth() { return getBlockState().getBlock() instanceof BattleNodeBlock block ? block.maximumHealth() : 100; }
    public double health() { return health; }
    @Nullable public UUID owner() { return owner; }
    @Nullable public UUID bot() { return bot; }
    public void setOwner(UUID owner) { this.owner = owner; changed(); }
    public void setBot(@Nullable UUID bot) { this.bot = bot; changed(); }
    public boolean hurt(double damage) {
        if (level == null || level.isClientSide || !Double.isFinite(damage) || damage <= 0 || health <= 0) return false;
        health = Math.max(0, health - damage);
        if (health == 0) level.setBlock(worldPosition, getBlockState().setValue(BattleNodeBlock.DISABLED, true), 3);
        changed();
        return health == 0;
    }
    public void restore(double health) {
        this.health = Math.max(0, Math.min(maximumHealth(), health));
        if (level != null) level.setBlock(worldPosition, getBlockState().setValue(BattleNodeBlock.DISABLED, this.health <= 0), 3);
        changed();
    }
    private void changed() {
        setChanged();
        if (level != null && !level.isClientSide) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }
    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putDouble("Health", health);
        if (owner != null) tag.putUUID("Owner", owner);
        if (bot != null) tag.putUUID("Bot", bot);
    }
    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        double saved = tag.contains("Health") ? tag.getDouble("Health") : maximumHealth();
        health = Double.isFinite(saved) ? Math.max(0, Math.min(maximumHealth(), saved)) : maximumHealth();
        owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        bot = tag.hasUUID("Bot") ? tag.getUUID("Bot") : null;
    }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) { return saveWithoutMetadata(registries); }
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
}
