package education.poiesis.createbattlebots.data;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import education.poiesis.createbattlebots.core.SnapshotDigest;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;

/** Persistent ownership plus recovery references. Runtime matches intentionally recover after restart. */
public final class BattleSavedData extends SavedData {
    public final Map<String, ArenaRecord> arenas = new LinkedHashMap<>();
    public final Map<UUID, BotRecord> bots = new LinkedHashMap<>();
    public final Map<String, CompoundTag> pending = new LinkedHashMap<>();
    public final Map<UUID, Integer> playerModes = new LinkedHashMap<>();
    public static BattleSavedData get(ServerLevel level) {
        Path path = path(level);
        if (!Files.exists(path)) return new BattleSavedData();
        try {
            if (Files.size(path) > 16 * 1024 * 1024) throw new IOException("Registry exceeds safety size");
            byte[] envelopeBytes;
            // Consume the entire gzip stream, including its CRC/trailer. Reading just the NBT root
            // can otherwise accept a truncated or corrupt trailer without consulting the checksum.
            try (var gzip = new java.util.zip.GZIPInputStream(Files.newInputStream(path))) {
                envelopeBytes = gzip.readNBytes(32 * 1024 * 1024 + 1);
                if (envelopeBytes.length > 32 * 1024 * 1024) throw new IOException("Registry decode budget exceeded");
            }
            CompoundTag envelope;
            try (var input = new java.io.DataInputStream(new ByteArrayInputStream(envelopeBytes))) {
                envelope = NbtIo.read(input, NbtAccounter.create(32 * 1024 * 1024));
                if (input.available() != 0) throw new IOException("Unexpected trailing registry payload");
            }
            byte[] bytes = envelope.getByteArray("Payload");
            if (!SnapshotDigest.matches(bytes, envelope.getString("SHA256"))) throw new IOException("Registry checksum mismatch");
            return load(NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.create(32 * 1024 * 1024)), level.registryAccess());
        } catch (IOException | RuntimeException error) {
            throw new IllegalStateException("Battle Bots registry cannot be read safely: " + path + ". Preserve it and restore registry.nbt.bak from a world backup; no empty registry was created.", error);
        }
    }
    private static BattleSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        if (tag.getInt("Version") != 1) throw new IllegalStateException("Unsupported Battle Bots save version; preserve your world backup.");
        var data = new BattleSavedData();
        for (Tag entry : tag.getList("Arenas", Tag.TAG_COMPOUND)) { var arena = ArenaRecord.load((CompoundTag) entry); data.arenas.put(arena.name, arena); }
        for (Tag entry : tag.getList("Bots", Tag.TAG_COMPOUND)) { var bot = BotRecord.load((CompoundTag) entry); data.bots.put(bot.id, bot); }
        for (Tag entry : tag.getList("Pending", Tag.TAG_COMPOUND)) { var p = (CompoundTag) entry; data.pending.put(p.getString("Arena"), p.copy()); }
        for (Tag entry : tag.getList("PlayerModes", Tag.TAG_COMPOUND)) { var p = (CompoundTag) entry; data.playerModes.put(p.getUUID("Player"), p.getInt("Mode")); }
        return data;
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("Version", 1);
        var arenaTags = new ListTag(); arenas.values().forEach(a -> arenaTags.add(a.save())); tag.put("Arenas", arenaTags);
        var botTags = new ListTag(); bots.values().forEach(b -> botTags.add(b.save())); tag.put("Bots", botTags);
        var pendingTags = new ListTag(); pending.values().forEach(p -> pendingTags.add(p.copy())); tag.put("Pending", pendingTags);
        var modes = new ListTag(); playerModes.forEach((id, mode) -> { var p = new CompoundTag(); p.putUUID("Player", id); p.putInt("Mode", mode); modes.add(p); }); tag.put("PlayerModes", modes);
        return tag;
    }
    /** Minecraft's SavedData writer is asynchronous and swallows errors; match gates use this checked writer. */
    public void flush(ServerLevel level) {
        Path path = path(level), temporary = path.resolveSibling("registry-" + UUID.randomUUID() + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            var buffer = new ByteArrayOutputStream(); NbtIo.writeCompressed(save(new CompoundTag(), level.registryAccess()), buffer);
            if (buffer.size() > 16 * 1024 * 1024) throw new IOException("Registry exceeds safety size");
            byte[] bytes = buffer.toByteArray(); var envelope = new CompoundTag(); envelope.putByteArray("Payload", bytes); envelope.putString("SHA256", SnapshotDigest.sha256(bytes));
            NbtIo.writeCompressed(envelope, temporary);
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            if (Files.exists(path)) Files.copy(path, path.resolveSibling("registry.nbt.bak"), StandardCopyOption.REPLACE_EXISTING);
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            // Directory fsync is available on Unix. Windows providers do not permit opening a directory.
            try (var directory = FileChannel.open(path.getParent(), StandardOpenOption.READ)) { directory.force(true); }
            catch (java.nio.file.AccessDeniedException ignored) { /* Atomic replacement + file fsync remain intact. */ }
            setDirty(false);
        } catch (IOException error) {
            throw new IllegalStateException("Battle Bots could not durably save its registry; match changes are blocked: " + error.getMessage(), error);
        } finally { try { Files.deleteIfExists(temporary); } catch (IOException ignored) {} }
    }
    private static Path path(ServerLevel level) {
        return education.poiesis.createbattlebots.repair.SnapshotStore.storageRoot(level).resolve("registry.nbt");
    }
}
