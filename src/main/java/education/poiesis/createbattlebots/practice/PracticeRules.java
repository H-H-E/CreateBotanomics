package education.poiesis.createbattlebots.practice;

/** Pure, bounded rules shared by keyboard input, AI, and regression tests. */
public final class PracticeRules {
    public static final int FORWARD = 1, BACK = 2, LEFT = 4, RIGHT = 8, WEAPON = 16, BRAKE = 32;
    public static final int INPUT_TIMEOUT = 8, ROUND_TICKS = 1800, COUNTDOWN_TICKS = 60;
    public record Input(double throttle, double turn, boolean weapon, boolean brake) {
        public static final Input STOP = new Input(0, 0, false, true);
        public Input {
            if (!Double.isFinite(throttle) || !Double.isFinite(turn)) throw new IllegalArgumentException("Non-finite control");
            throttle = clamp(throttle, -1, 1); turn = clamp(turn, -1, 1);
        }
    }
    public static Input decode(int bits) {
        if ((bits & ~63) != 0) throw new IllegalArgumentException("Invalid control flags");
        return new Input(((bits & FORWARD) != 0 ? 1 : 0) - ((bits & BACK) != 0 ? 1 : 0),
                ((bits & RIGHT) != 0 ? 1 : 0) - ((bits & LEFT) != 0 ? 1 : 0),
                (bits & WEAPON) != 0, (bits & BRAKE) != 0);
    }
    public static double clamp(double x, double min, double max) { return Math.max(min, Math.min(max, x)); }
    public static double angle(double angle) { return Math.atan2(Math.sin(angle), Math.cos(angle)); }
    public static double damage(double speed) {
        if (!Double.isFinite(speed) || speed < 1.6) return 0;
        return Math.min(22, (speed - 1.2) * 2.1);
    }
    /** The AI uses the exact same drive input as the player; it cannot warp or aim damage. */
    public static final class Brain {
        private double lastX, lastZ;
        private int still, reverse;
        public void reset() { still = reverse = 0; }
        public Input tick(double x, double z, double yaw, double targetX, double targetZ,
                          double centerX, double centerZ, boolean easy) {
            if (!Double.isFinite(x + z + yaw + targetX + targetZ + centerX + centerZ)) return Input.STOP;
            double motion = Math.hypot(x - lastX, z - lastZ);
            lastX = x; lastZ = z;
            if (reverse > 0) { reverse--; return new Input(-0.75, 0.8, true, false); }
            still = motion < 0.025 ? still + 1 : 0;
            if (still >= 50) { still = 0; reverse = 24; return new Input(-0.75, 0.8, true, false); }
            // Steer away from the edge, not through it. A ring-out remains possible after an impact.
            if (Math.max(Math.abs(x - centerX), Math.abs(z - centerZ)) > 19) {
                targetX = centerX; targetZ = centerZ;
            }
            double error = angle(Math.atan2(targetX - x, targetZ - z) - yaw);
            return new Input((easy ? 0.65 : 1) * (Math.abs(error) > 1.2 ? 0.25 : 1),
                    clamp(error * 1.4, -1, 1), true, false);
        }
    }
    private PracticeRules() {}
}
