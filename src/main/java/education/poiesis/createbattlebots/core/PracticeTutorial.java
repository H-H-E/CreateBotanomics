package education.poiesis.createbattlebots.core;

/** Progress is earned from observed movement and weapon speed, not just button presses. */
public final class PracticeTutorial {
    private int step;
    private double x, z, yaw;
    private boolean initialized;
    public int step() { return step; }
    public void skip() { step = 3; }
    public void observe(double worldX, double worldZ, double heading, double weaponSpeed) {
        if (!Double.isFinite(worldX) || !Double.isFinite(worldZ) || !Double.isFinite(heading)
                || !Double.isFinite(weaponSpeed)) return;
        if (!initialized) { x=worldX; z=worldZ; yaw=heading; initialized=true; }
        if (step == 0 && Math.hypot(worldX-x, worldZ-z) >= 3) { step=1; yaw=heading; }
        else if (step == 1 && Math.abs(Math.IEEEremainder(heading-yaw, Math.PI*2)) >= 0.6) step=2;
        else if (step == 2 && Math.abs(weaponSpeed) >= 2) step=3;
    }
}
