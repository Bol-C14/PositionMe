package com.openpositioning.PositionMe.sensors;

import org.junit.Test;
import static org.junit.Assert.*;

public class SensorSnapshotTest {
    @Test public void snapshotOwnsItsVectorsAndDoesNotExposeThem() {
        SensorState state = new SensorState();
        state.acceleration[0] = 3.5f;
        state.rotation = new float[]{1f, 2f, 3f, 4f};
        SensorSnapshot snapshot = new SensorSnapshot(123L, state);
        state.acceleration[0] = 99f;
        state.rotation[0] = 99f;
        snapshot.getAcceleration()[0] = -5f;
        snapshot.getRotation()[0] = -5f;
        assertEquals(3.5f, snapshot.getAcceleration()[0], 0f);
        assertEquals(1f, snapshot.getRotation()[0], 0f);
        assertEquals(123L, snapshot.getElapsedRealtimeMillis());
    }

    @Test public void pdrEventCannotBeMutatedByPublisherOrSubscriber() {
        float[] position = {2f, 5f};
        SensorFusionEvent event = SensorFusionEvent.pdrStep(12L, position, 0.8, 0.3);
        position[0] = 100f;
        event.getPdrPosition()[1] = 200f;
        assertArrayEquals(new float[]{2f, 5f}, event.getPdrPosition(), 0f);
    }
}
