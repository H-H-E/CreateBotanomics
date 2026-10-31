package education.poiesis.createbattlebots.data;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

public final class BotRecord {
    public final UUID id, owner, rootBody;
    public final String name, machineClass;
    public final BlockPos transponder;
    public final Set<UUID> bodies = new LinkedHashSet<>();
    public BotRecord(UUID id, UUID owner, String name, String machineClass, UUID rootBody, BlockPos transponder) {
        this.id = id; this.owner = owner; this.name = name; this.machineClass = machineClass;
        this.rootBody = rootBody; this.transponder = transponder.immutable(); bodies.add(rootBody);
    }
    public CompoundTag save() {
        var tag = new CompoundTag(); tag.putUUID("Id", id); tag.putUUID("Owner", owner); tag.putUUID("Root", rootBody);
        tag.putString("Name", name); tag.putString("Class", machineClass); tag.putLong("Transponder", transponder.asLong());
        var parts = new ListTag(); for (UUID body : bodies) { var part = new CompoundTag(); part.putUUID("Id", body); parts.add(part); }
        tag.put("Bodies", parts); return tag;
    }
    public static BotRecord load(CompoundTag tag) {
        var bot = new BotRecord(tag.getUUID("Id"), tag.getUUID("Owner"), tag.getString("Name"), tag.getString("Class"), tag.getUUID("Root"), BlockPos.of(tag.getLong("Transponder")));
        for (Tag part : tag.getList("Bodies", Tag.TAG_COMPOUND)) bot.bodies.add(((CompoundTag) part).getUUID("Id"));
        return bot;
    }
}
