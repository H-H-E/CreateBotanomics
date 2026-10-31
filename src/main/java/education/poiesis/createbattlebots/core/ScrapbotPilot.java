package education.poiesis.createbattlebots.core;

/** Deterministic pursuit and bounded reverse recovery. Uses the exact human drive interface. */
public final class ScrapbotPilot {
    private long checkpoint = -1, reverseUntil = -1;
    private double checkpointX, checkpointZ;
    private int escapes;
    public ArcadeInput decide(long tick, double x, double z, double forwardX, double forwardZ,
                              double targetX, double targetZ, double centerX, double centerZ) {
        for (double value : new double[]{x,z,forwardX,forwardZ,targetX,targetZ,centerX,centerZ})
            if (!Double.isFinite(value)) return ArcadeInput.IDLE;
        if (checkpoint < 0 || tick < checkpoint) {
            checkpoint = tick; checkpointX = x; checkpointZ = z; reverseUntil = -1;
        }
        if (tick < reverseUntil) return new ArcadeInput(-0.8, (escapes & 1) == 0 ? 0.8 : -0.8, true);
        if (tick - checkpoint >= 45) {
            if (Math.hypot(x - checkpointX, z - checkpointZ) < 0.8) {
                reverseUntil = tick + 24; escapes++;
            }
            checkpoint = tick; checkpointX = x; checkpointZ = z;
        }
        // Avoid driving off the platform while still allowing the opponent to push us out.
        if (Math.max(Math.abs(x-centerX), Math.abs(z-centerZ)) > 15) {
            targetX = centerX; targetZ = centerZ;
        }
        double dx = targetX-x, dz = targetZ-z;
        double angle = Math.atan2(forwardZ*dx-forwardX*dz, forwardX*dx+forwardZ*dz);
        return new ArcadeInput(Math.abs(angle) > 1.2 ? 0.15 : Math.hypot(dx,dz) < 3 ? 0.65 : 1,
                Math.max(-1, Math.min(1, angle*1.6)), true);
    }
}
