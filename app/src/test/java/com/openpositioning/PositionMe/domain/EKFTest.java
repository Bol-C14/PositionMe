package com.openpositioning.PositionMe.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class EKFTest {
    private static final double POSITION_TOLERANCE_METERS = 1e-12;

    @Test
    public void predictMovesNorthAtZeroHeadingAndEastAtQuarterTurn() {
        EKF northbound = new EKF(0.0, 0.0, 0.0);
        northbound.predict(2.0, 0.0);

        double[] northPosition = northbound.getEstimatedPositionENU();
        assertEquals(0.0, northPosition[0], POSITION_TOLERANCE_METERS);
        assertEquals(2.0, northPosition[1], POSITION_TOLERANCE_METERS);

        EKF eastbound = new EKF(0.0, 0.0, Math.PI / 2.0);
        eastbound.predict(3.0, 0.0);

        double[] eastPosition = eastbound.getEstimatedPositionENU();
        assertEquals(3.0, eastPosition[0], POSITION_TOLERANCE_METERS);
        assertEquals(0.0, eastPosition[1], POSITION_TOLERANCE_METERS);
    }

    @Test
    public void predictWrapsHeadingAcrossPositivePiBoundary() {
        EKF filter = new EKF(0.0, 0.0, Math.PI - 0.1);

        filter.predict(0.0, 0.2);

        assertEquals(-Math.PI + 0.1, filter.getStateVector()[3], 1e-12);
    }

    @Test
    public void stateRemainsFiniteAfterGnssWifiAndBarometerCorrections() {
        EKF filter = new EKF(0.0, 0.0, 1.0, 0.3);
        filter.predict(1.5, 0.1);
        filter.updateGNSS(4.0, 6.0, 2.5, 1.0);
        filter.update(4.5, 5.5, 1.5);
        filter.updateZ(3.0);

        for (double value : filter.getStateVector()) {
            assertTrue("EKF state should remain finite", Double.isFinite(value));
        }
        for (double value : filter.getEstimatedPositionENU()) {
            assertTrue("ENU estimate should remain finite", Double.isFinite(value));
        }
    }
}
