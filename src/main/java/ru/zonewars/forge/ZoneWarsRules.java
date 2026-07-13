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

    public static final int MAX_MONEY_BALANCE = 1_000_000;

    public static int addMoney(int currentBalance, int amount) {
        long next = Math.max(0L, currentBalance) + Math.max(0L, amount);
        return (int) Math.min(MAX_MONEY_BALANCE, next);
    }

    public static int clampInt(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public static double clampFinite(double value, double fallback, double minimum, double maximum) {
        if (!Double.isFinite(value)) return fallback;
        return Math.max(minimum, Math.min(maximum, value));
    }

    public static double captureRadius(double value) { return clampFinite(value, 9.0, 1.0, 128.0); }
    public static int maxPlayersPerTeam(int value) { return clampInt(value, 1, 100); }
    public static int minPlayersPerTeam(int value, int maxPlayersPerTeam) { return Math.min(maxPlayersPerTeam, clampInt(value, 0, 100)); }
    public static int preparationSeconds(int value) { return clampInt(value, 0, 600); }
    public static int matchSeconds(int value) { return clampInt(value, 60, 21_600); }
    public static int overtimeSeconds(int value) { return clampInt(value, 0, 3_600); }
    public static int endScreenSeconds(int value) { return clampInt(value, 0, 300); }
    public static int captureSeconds(int value) { return clampInt(value, 1, 600); }
    public static int pointsPerSecond(int value) { return clampInt(value, 0, 1_000); }
    public static double coordinate(double value, double fallback) { return clampFinite(value, fallback, -30_000_000.0, 30_000_000.0); }
    public static double yCoordinate(double value, double fallback) { return clampFinite(value, fallback, -2_048.0, 2_048.0); }
    public static double yaw(double value, double fallback) { return clampFinite(value, fallback, -360.0, 360.0); }
    public static double pitch(double value, double fallback) { return clampFinite(value, fallback, -90.0, 90.0); }

}
