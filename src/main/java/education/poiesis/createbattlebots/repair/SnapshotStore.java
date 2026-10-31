package education.poiesis.createbattlebots.repair;

import education.poiesis.createbattlebots.core.SnapshotDigest;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

/** Immutable, checksummed snapshots. Never overwrite a previous match's backup. */
public final class SnapshotStore {
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    private final Path root;
    public SnapshotStore(ServerLevel level) {
        root = storageRoot(level);
    }
    public static Path storageRoot(ServerLevel level) {
        return level.getServer().getWorldPath(LevelResource.ROOT).resolve("battlebots")
                .resolve(safeSegment(level.dimension().location().getNamespace())).resolve(safeSegment(level.dimension().location().getPath()));
    }
    private static String safeSegment(String value) {
        return value.replace("%", "%25").replace("/", "%2F").replace(".", "%2E");
    }
    public String write(UUID matchId, CompoundTag snapshot) throws IOException {
        Files.createDirectories(root);
        var bytes = new ByteArrayOutputStream(); NbtIo.writeCompressed(snapshot, bytes);
        if (bytes.size() > MAX_BYTES) throw new IOException("Snapshot exceeds 64 MiB safety budget");
        try { NbtIo.readCompressed(new ByteArrayInputStream(bytes.toByteArray()), NbtAccounter.create(MAX_BYTES)); }
        catch (RuntimeException error) { throw new IOException("Snapshot exceeds the safe recovery decode budget", error); }
        Path destination = root.resolve(matchId + ".nbt");
        if (Files.exists(destination)) throw new IOException("Refusing to replace an immutable snapshot");
        Path temp = root.resolve(matchId + ".tmp");
        try {
            Files.write(temp, bytes.toByteArray(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try (var channel = FileChannel.open(temp, StandardOpenOption.WRITE)) { channel.force(true); }
            try { Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temp, destination); }
        } finally { Files.deleteIfExists(temp); }
        return SnapshotDigest.sha256(bytes.toByteArray());
    }
    public CompoundTag read(UUID matchId, String checksum) throws IOException {
        Path path = root.resolve(matchId + ".nbt");
        if (!Files.isRegularFile(path) || Files.size(path) > MAX_BYTES) throw new IOException("Missing or oversized recovery snapshot " + matchId);
        byte[] bytes = Files.readAllBytes(path);
        if (!SnapshotDigest.matches(bytes, checksum)) throw new IOException("Recovery snapshot checksum mismatch; preserving current bodies");
        var tag = NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.create(MAX_BYTES));
        if (tag.getInt("Version") != 1 || !tag.hasUUID("Match") || !matchId.equals(tag.getUUID("Match"))) throw new IOException("Recovery snapshot schema/identity mismatch");
        return tag;
    }
    public void audit(UUID matchId, String line) throws IOException {
        Files.createDirectories(root.resolve("matches"));
        Files.writeString(root.resolve("matches").resolve(matchId + ".jsonl"), line + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
