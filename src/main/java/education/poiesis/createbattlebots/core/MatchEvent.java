package education.poiesis.createbattlebots.core;

import java.util.Optional;
import java.util.UUID;

/** Drain these into the match's persisted audit log after every service tick. */
public record MatchEvent(long sequence, long tick, Type type, Optional<UUID> botId,
                         double amount, String detail) {
    public enum Type {
        COUNTDOWN, STARTED, COMPONENT_FAILURE, CONTROL, ENGAGEMENT,
        DISCONNECTED, RECONNECTED, ENDED, RESTORED, RECOVERY_REQUIRED, RESTORE_RETRY
    }
}
