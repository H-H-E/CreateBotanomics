package education.poiesis.createbattlebots.core;

/** World-space bounds. Do not pass Sable plot-local coordinates to this class. */
public record ArenaBounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
    public ArenaBounds {
        if (!finite(minX, minY, minZ, maxX, maxY, maxZ)
                || minX >= maxX || minY >= maxY || minZ >= maxZ) {
            throw new IllegalArgumentException("Arena must have finite positive dimensions");
        }
        if (Math.abs(minX) > 30_000_000 || Math.abs(maxX) > 30_000_000
                || Math.abs(minZ) > 30_000_000 || Math.abs(maxZ) > 30_000_000
                || minY < -2048 || maxY > 2048) {
            throw new IllegalArgumentException("Arena is outside supported world coordinates");
        }
    }

    public boolean contains(double x, double y, double z) {
        return finite(x, y, z) && x >= minX && x <= maxX && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    public boolean contains(ArenaBounds other) {
        return contains(other.minX, other.minY, other.minZ)
                && contains(other.maxX, other.maxY, other.maxZ);
    }

    /** Touching at an outer face is allowed; occupied volumes must not overlap. */
    public boolean overlaps(ArenaBounds other) {
        return maxX > other.minX && minX < other.maxX
                && maxY > other.minY && minY < other.maxY
                && maxZ > other.minZ && minZ < other.maxZ;
    }

    public double volume() {
        return (maxX - minX) * (maxY - minY) * (maxZ - minZ);
    }

    private static boolean finite(double... values) {
        for (double value : values) if (!Double.isFinite(value)) return false;
        return true;
    }
}
