package com.openpositioning.PositionMe.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class PositioningEngineTest {
    private static final GeoPosition ORIGIN = new GeoPosition(55.9228, -3.1746, 40.0);

    @Test
    public void fixedObservationReplayIsRepeatableForFixedSeed() {
        PositionEstimate first = replay(8128L);
        PositionEstimate second = replay(8128L);

        assertEquals(first.getPosition(), second.getPosition());
        assertEquals(first.getEastMeters(), second.getEastMeters(), 0.0);
        assertEquals(first.getNorthMeters(), second.getNorthMeters(), 0.0);
        assertEquals(5_000L, first.getElapsedRealtimeMillis());
        assertEquals(0.0, first.getEastMeters(), 1.0);
        assertEquals(3.0, first.getNorthMeters(), 1.0);
    }

    @Test
    public void differentParticleSeedsProduceDifferentHypotheses() {
        ParticleFilter first = particleFilter(1L);
        ParticleFilter second = particleFilter(2L);
        assertNotEquals(first.getParticles().get(0).x, second.getParticles().get(0).x, 0.0);
        assertEquals(first.getParticles().get(0).x, particleFilter(1L).getParticles().get(0).x, 0.0);
    }

    @Test
    public void geodesyRoundTripsLocalEastNorthUpCoordinates() {
        GeoPosition origin = new GeoPosition(0.0, 0.0, 0.0);
        GeoPosition oneDegreeEast = new GeoPosition(0.0, 0.001, 0.0);
        double[] enu = Geodesy.geodeticToEnu(oneDegreeEast, origin);
        assertEquals(6378137.0 * Math.sin(Math.toRadians(0.001)), enu[0], 1e-8);
        assertEquals(0.0, enu[1], 1e-8);
        assertEquals(6378137.0 * (Math.cos(Math.toRadians(0.001)) - 1.0), enu[2], 1e-8);

        GeoPosition roundTrip = Geodesy.enuToGeodetic(enu[0], enu[1], enu[2], origin);
        assertEquals(oneDegreeEast.getLatitudeDegrees(), roundTrip.getLatitudeDegrees(), 1e-9);
        assertEquals(oneDegreeEast.getLongitudeDegrees(), roundTrip.getLongitudeDegrees(), 1e-9);
    }

    @Test
    public void outOfOrderObservationDoesNotRewindEstimate() {
        EkfParticlePositioningEngine engine = new EkfParticlePositioningEngine(ORIGIN, 0.0, 42L);
        engine.onWifi(new WifiObservation(1_000L, ORIGIN, -60.0, 0));
        PositionEstimate current = engine.onStep(new StepObservation(2_000L, 1.0, 0.0, 1));

        PositionEstimate stale = engine.onWifi(new WifiObservation(1_500L,
                new GeoPosition(55.923, -3.1746, 40.0), -45.0, 0));

        assertEquals(current.getPosition(), stale.getPosition());
        assertEquals(2_000L, stale.getElapsedRealtimeMillis());
    }

    @Test
    public void engineWaitsForFirstPositionFixBeforeCreatingEstimate() {
        EkfParticlePositioningEngine engine = new EkfParticlePositioningEngine(null, 0.0, 42L);
        assertNull(engine.getEstimate());
        PositionEstimate initialized = engine.onGnss(new GnssObservation(10L, ORIGIN, 5.0));
        assertNotNull(initialized);
        assertEquals(0.0, initialized.getEastMeters(), 1e-9);
        assertEquals(0.0, initialized.getNorthMeters(), 1e-9);
    }

    private static PositionEstimate replay(long seed) {
        EkfParticlePositioningEngine engine = new EkfParticlePositioningEngine(ORIGIN, 0.0, seed);
        engine.onWifi(new WifiObservation(1_000L, ORIGIN, -60.0, 0));
        engine.onStep(new StepObservation(2_000L, 1.0, 0.0, 1));
        engine.onStep(new StepObservation(3_000L, 1.0, 0.0, 2));
        engine.onWifi(new WifiObservation(4_000L, new GeoPosition(55.92281, -3.1746, 40.0), -55.0, 0));
        return engine.onStep(new StepObservation(5_000L, 1.0, 0.0, 3));
    }

    private static ParticleFilter particleFilter(long seed) {
        ParticleFilter filter = new ParticleFilter(100, ORIGIN, seed);
        filter.initializeParticles(0.0, 0.0, 0.0, 1.0, 1.0, 0.1);
        return filter;
    }
}
