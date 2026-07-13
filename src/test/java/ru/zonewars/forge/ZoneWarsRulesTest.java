package ru.zonewars.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZoneWarsRulesTest {
    @Test
    void playerInsideHorizontalRadiusIsAccepted() {
        assertTrue(ZoneWarsRules.insideCaptureCylinder(3.0, 0.0, 4.0, 5.0));
    }

    @Test
    void boundaryIsIncluded() {
        assertTrue(ZoneWarsRules.insideCaptureCylinder(5.0, 6.0, 0.0, 5.0));
    }

    @Test
    void playerOutsideHorizontalRadiusIsRejected() {
        assertFalse(ZoneWarsRules.insideCaptureCylinder(5.1, 0.0, 0.0, 5.0));
    }

    @Test
    void moderateTerrainHeightDifferenceIsAccepted() {
        assertTrue(ZoneWarsRules.insideCaptureCylinder(0.0, 5.5, 0.0, 3.0));
    }

    @Test
    void excessiveVerticalDifferenceIsRejected() {
        assertFalse(ZoneWarsRules.insideCaptureCylinder(0.0, 6.1, 0.0, 3.0));
    }

    @Test
    void invalidNumbersAndRadiusAreRejected() {
        assertFalse(ZoneWarsRules.insideCaptureCylinder(Double.NaN, 0.0, 0.0, 5.0));
        assertFalse(ZoneWarsRules.insideCaptureCylinder(0.0, 0.0, 0.0, Double.POSITIVE_INFINITY));
        assertFalse(ZoneWarsRules.insideCaptureCylinder(0.0, 0.0, 0.0, 0.0));
        assertFalse(ZoneWarsRules.insideCaptureCylinder(0.0, 0.0, 0.0, -1.0));
    }
    @Test
    void moneyRulesClampAndIgnoreInvalidValues() {
        assertTrue(ZoneWarsRules.addMoney(100, -50) == 100);
        assertTrue(ZoneWarsRules.addMoney(999_990, 50) == ZoneWarsRules.MAX_MONEY_BALANCE);
        assertTrue(ZoneWarsRules.addMoney(-500, 25) == 25);
    }

    @Test
    void captureRadiusIsClampedAndNaNFallsBack() {
        assertTrue(ZoneWarsRules.captureRadius(0.1) == 1.0);
        assertTrue(ZoneWarsRules.captureRadius(999.0) == 128.0);
        assertTrue(ZoneWarsRules.captureRadius(Double.NaN) == 9.0);
    }

    @Test
    void arenaTimersAreClamped() {
        assertTrue(ZoneWarsRules.preparationSeconds(-1) == 0);
        assertTrue(ZoneWarsRules.preparationSeconds(999) == 600);
        assertTrue(ZoneWarsRules.matchSeconds(1) == 60);
        assertTrue(ZoneWarsRules.matchSeconds(99_999) == 21_600);
        assertTrue(ZoneWarsRules.overtimeSeconds(-5) == 0);
        assertTrue(ZoneWarsRules.overtimeSeconds(99_999) == 3_600);
        assertTrue(ZoneWarsRules.captureSeconds(0) == 1);
        assertTrue(ZoneWarsRules.captureSeconds(999) == 600);
    }

    @Test
    void playerLimitsAreClampedAndMinDoesNotExceedMax() {
        assertTrue(ZoneWarsRules.maxPlayersPerTeam(0) == 1);
        assertTrue(ZoneWarsRules.maxPlayersPerTeam(999) == 100);
        assertTrue(ZoneWarsRules.minPlayersPerTeam(-1, 10) == 0);
        assertTrue(ZoneWarsRules.minPlayersPerTeam(20, 10) == 10);
    }

    @Test
    void locationValuesRejectNaNAndClampRanges() {
        assertTrue(ZoneWarsRules.coordinate(Double.NaN, 42.0) == 42.0);
        assertTrue(ZoneWarsRules.coordinate(99_999_999.0, 0.0) == 30_000_000.0);
        assertTrue(ZoneWarsRules.yCoordinate(-99_999.0, 0.0) == -2_048.0);
        assertTrue(ZoneWarsRules.pitch(-999.0, 0.0) == -90.0);
        assertTrue(ZoneWarsRules.yaw(999.0, 0.0) == 360.0);
    }

}
