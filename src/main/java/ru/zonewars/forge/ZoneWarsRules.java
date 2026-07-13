package ru.zonewars.forge;

/** Pure gameplay calculations that can be tested without starting Minecraft. */
public final class ZoneWarsRules {
    private static final double MIN_CAPTURE_VERTICAL_RANGE = 6.0;

    private ZoneWarsRules() {
    }

    public static boolean insideCaptureCylinder(double dx, double dy, double dz, double radius) {
        if (!Double.isFinite(dx) || !Double.isFinite(dy) || !Double.isFinite(dz)
            || !Double.isFinite(radius) || radius <= 0.0) {
            return false;
        }
        double verticalLimit = Math.max(MIN_CAPTURE_VERTICAL_RANGE, radius);
        return dx * dx + dz * dz <= radius * radius && Math.abs(dy) <= verticalLimit;
    }
}
