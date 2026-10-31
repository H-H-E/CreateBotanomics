package education.poiesis.createbattlebots.data;

import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class ArenaRecord {
    public final String name;
    public final BlockPos min, max;
    @Nullable public UUID first, second;
    public String lastResult = "No matches yet.";
    public ArenaRecord(String name, BlockPos a, BlockPos b) {
        this.name = name;
        min = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
        max = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
    }
    public AABB bounds() { return new AABB(min.getX(), min.getY(), min.getZ(), max.getX() + 1, max.getY() + 1, max.getZ() + 1); }
    public boolean contains(Vec3 worldPosition) { return bounds().contains(worldPosition); }
    public CompoundTag save() {
        var tag = new CompoundTag(); tag.putString("Name", name); tag.putLong("Min", min.asLong()); tag.putLong("Max", max.asLong());
        if (first != null) tag.putUUID("First", first); if (second != null) tag.putUUID("Second", second);
        tag.putString("LastResult", lastResult); return tag;
    }
    public static ArenaRecord load(CompoundTag tag) {
        var arena = new ArenaRecord(tag.getString("Name"), BlockPos.of(tag.getLong("Min")), BlockPos.of(tag.getLong("Max")));
        arena.first = tag.hasUUID("First") ? tag.getUUID("First") : null;
        arena.second = tag.hasUUID("Second") ? tag.getUUID("Second") : null;
        arena.lastResult = tag.getString("LastResult"); return arena;
    }
}
