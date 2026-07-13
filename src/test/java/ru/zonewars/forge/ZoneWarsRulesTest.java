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
}
