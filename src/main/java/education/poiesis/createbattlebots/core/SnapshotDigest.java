package education.poiesis.createbattlebots.core;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** SHA-256 of the exact persisted bytes, checked before any destructive restoration step. */
public final class SnapshotDigest {
    private SnapshotDigest() {}

    public static String sha256(byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        return HexFormat.of().formatHex(digest().digest(payload));
    }

    public static boolean matches(byte[] payload, String expectedHex) {
        if (payload == null || expectedHex == null || expectedHex.length() != 64) return false;
        try {
            return MessageDigest.isEqual(digest().digest(payload), HexFormat.of().parseHex(expectedHex));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossibleOnJava21) {
            throw new AssertionError(impossibleOnJava21);
        }
    }
}
