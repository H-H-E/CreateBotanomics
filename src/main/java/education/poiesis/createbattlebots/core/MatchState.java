package education.poiesis.createbattlebots.core;

/** RESTORING and RECOVERY both retain the match's protection and ownership locks. */
public enum MatchState {
    PREPARING,
    COUNTDOWN,
    ACTIVE,
    RESTORING,
    RECOVERY,
    FINISHED
}
