package education.poiesis.createbattlebots.core;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record MatchResult(Optional<UUID> winner, EndReason reason, long tick,
                          Map<UUID, MatchScore> scores, String detail) {
    public enum EndReason {
        KNOCKOUT, RING_OUT, TECHNICAL_KNOCKOUT, DISCONNECT, IMMOBILIZED,
        SURRENDER, DECISION, DRAW, ABORTED
    }

    public MatchResult {
        Objects.requireNonNull(winner, "winner");
        Objects.requireNonNull(reason, "reason");
        scores = Map.copyOf(scores);
        detail = Objects.requireNonNull(detail, "detail");
        if (tick < 0) throw new IllegalArgumentException("tick cannot be negative");
        if (winner.isPresent() && !scores.containsKey(winner.get())) {
            throw new IllegalArgumentException("Winner must be a match participant");
        }
    }
}
