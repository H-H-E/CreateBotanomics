package education.poiesis.createbattlebots.practice;

import education.poiesis.createbattlebots.core.SnapshotDigest;
import education.poiesis.createbattlebots.repair.SnapshotStore;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Bounded, checksummed, atomic checkpoints; bad data never means an empty workshop. */
final class PracticeStore {
    private static final int LIMIT=16*1024*1024;
    final Map<String,CompoundTag> slots=new LinkedHashMap<>();
    private final Path path;
    PracticeStore(ServerLevel level) {
        path=SnapshotStore.storageRoot(level).resolve("practice.nbt");
        if(!Files.exists(path))return;
        try {
            if(Files.size(path)>LIMIT)throw new IOException("Practice registry exceeds its size limit");
            byte[] envelope;
            try(var gzip=new GZIPInputStream(Files.newInputStream(path))) {
                envelope=gzip.readNBytes(LIMIT+1);
                if(envelope.length>LIMIT)throw new IOException("Practice registry exceeds its decode limit");
            }
            CompoundTag root=NbtIo.read(new DataInputStream(new ByteArrayInputStream(envelope)),NbtAccounter.create(LIMIT));
            byte[] bytes=root.getByteArray("Payload");
            if(!SnapshotDigest.matches(bytes,root.getString("SHA256")))throw new IOException("Practice checksum mismatch");
            CompoundTag payload=NbtIo.readCompressed(new ByteArrayInputStream(bytes),NbtAccounter.create(LIMIT));
            if(payload.getInt("Version")!=1)throw new IOException("Unsupported practice schema");
            Set<Integer> cells=new HashSet<>();
            for(Tag entry:payload.getList("Slots",Tag.TAG_COMPOUND)) {
                CompoundTag slot=(CompoundTag)entry;
                if(!slot.hasUUID("Owner") || slot.getInt("Cell")<0 || slot.getInt("Cell")>=16 || !cells.add(slot.getInt("Cell")))
                    throw new IOException("Invalid practice ownership or cell");
                if(slots.put(key(slot.getUUID("Owner"),slot.getBoolean("Walking")),slot.copy())!=null)
                    throw new IOException("Duplicate practice owner");
            }
        }catch(IOException|RuntimeException e){throw new IllegalStateException("Practice registry cannot be read. Preserve practice.nbt and its backup; no robots were replaced.",e);}
    }
    static String key(UUID owner,boolean walking){return owner+":"+(walking?"walker":"spinner");}
    void flush() {
        Path temporary=path.resolveSibling("practice-"+UUID.randomUUID()+".tmp");
        try {
            Files.createDirectories(path.getParent());
            CompoundTag payload=new CompoundTag();payload.putInt("Version",1);
            ListTag list=new ListTag();slots.values().forEach(s->list.add(s.copy()));payload.put("Slots",list);
            ByteArrayOutputStream output=new ByteArrayOutputStream();NbtIo.writeCompressed(payload,output);
            byte[] bytes=output.toByteArray();if(bytes.length>LIMIT)throw new IOException("Practice registry is too large");
            CompoundTag envelope=new CompoundTag();envelope.putByteArray("Payload",bytes);envelope.putString("SHA256",SnapshotDigest.sha256(bytes));
            NbtIo.writeCompressed(envelope,temporary);
            try(var file=FileChannel.open(temporary,StandardOpenOption.WRITE)){file.force(true);}
            if(Files.exists(path))Files.copy(path,path.resolveSibling("practice.nbt.bak"),StandardCopyOption.REPLACE_EXISTING);
            try{Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException ignored){Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING);}
        }catch(IOException e){throw new IllegalStateException("Could not checkpoint practice. Combat is paused; check disk space.",e);}
        finally{try{Files.deleteIfExists(temporary);}catch(IOException ignored){}}
    }
}
