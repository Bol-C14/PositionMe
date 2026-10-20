package com.openpositioning.PositionMe.utils;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class SmoothingFilterTest {
    @Test
    public void initializesFromFirstObservationThenAppliesExponentialWeightPerValue() {
        SmoothingFilter filter = new SmoothingFilter(0.25, 2);

        assertArrayEquals(new double[] {4.0, 20.0}, filter.applySmoothing(new double[] {4.0, 20.0}), 0.0);
        assertArrayEquals(new double[] {8.0, 16.0}, filter.applySmoothing(new double[] {20.0, 4.0}), 1e-12);
    }

    @Test
    public void resetMakesNextObservationTheNewBaseline() {
        SmoothingFilter filter = new SmoothingFilter(0.5, 1);
        filter.applySmoothing(new double[] {10.0});
        filter.applySmoothing(new double[] {20.0});

        filter.reset();

        assertArrayEquals(new double[] {-3.0}, filter.applySmoothing(new double[] {-3.0}), 0.0);
    }

    @Test
    public void rejectsInvalidConfigurationAndObservationSize() {
        assertThrows(IllegalArgumentException.class, () -> new SmoothingFilter(-0.01, 1));
        assertThrows(IllegalArgumentException.class, () -> new SmoothingFilter(0.5, 0));

        SmoothingFilter filter = new SmoothingFilter(0.5, 2);
        assertThrows(IllegalArgumentException.class, () -> filter.applySmoothing(new double[] {1.0}));
    }
}
