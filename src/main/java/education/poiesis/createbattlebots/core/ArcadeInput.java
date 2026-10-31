package education.poiesis.createbattlebots.core;

/** Five buttons, never client-supplied force, pose, entity ID or speed. */
public record ArcadeInput(double throttle, double steering, boolean weapon) {
    public static final ArcadeInput IDLE = new ArcadeInput(0, 0, false);
    public ArcadeInput {
        if (!Double.isFinite(throttle) || !Double.isFinite(steering)
                || Math.abs(throttle) > 1 || Math.abs(steering) > 1)
            throw new IllegalArgumentException("Drive input must be finite and normalized");
    }
    public static ArcadeInput buttons(int bits) {
        if (bits < 0 || bits > 31) throw new IllegalArgumentException("Unknown drive buttons");
        return new ArcadeInput(((bits & 1) != 0 ? 1 : 0) - ((bits & 2) != 0 ? 1 : 0),
                ((bits & 4) != 0 ? 1 : 0) - ((bits & 8) != 0 ? 1 : 0), (bits & 16) != 0);
    }
    public static ArcadeInput fresh(ArcadeInput input, long received, long now) {
        return received >= 0 && now >= received && now - received <= 10 ? input : IDLE;
    }
}
