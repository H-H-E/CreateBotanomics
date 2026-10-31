package education.poiesis.createbattlebots.core;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotDigestTest {
    @Test
    void exactPersistedBytesSurviveTwentyVerificationsButAnyMutationFails() {
        byte[] original = "block palette and inventory NBT".getBytes(StandardCharsets.UTF_8);
        String digest = SnapshotDigest.sha256(original);
        for (int i = 0; i < 20; i++) assertTrue(SnapshotDigest.matches(original.clone(), digest));
        byte[] damaged = original.clone();
        damaged[damaged.length - 1] ^= 1;
        assertFalse(SnapshotDigest.matches(damaged, digest));
        assertFalse(SnapshotDigest.matches(new byte[0], digest));
    }

    @Test
    void malformedStoredDigestFailsClosed() {
        byte[] data = new byte[]{1, 2, 3};
        assertFalse(SnapshotDigest.matches(data, "bad"));
        assertFalse(SnapshotDigest.matches(data, "x".repeat(64)));
        assertFalse(SnapshotDigest.matches(data, null));
        assertFalse(SnapshotDigest.matches(null, SnapshotDigest.sha256(data)));
    }
}
