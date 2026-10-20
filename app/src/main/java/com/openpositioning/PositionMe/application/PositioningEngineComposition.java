package com.openpositioning.PositionMe.application;

import com.openpositioning.PositionMe.domain.EkfParticlePositioningEngine;
import com.openpositioning.PositionMe.domain.GeoPosition;
import com.openpositioning.PositionMe.domain.PositioningEngine;

import java.util.function.BiFunction;

/** Application composition for the currently selected live positioning implementation. */
public final class PositioningEngineComposition {
    private PositioningEngineComposition() { }

    public static BiFunction<GeoPosition, Double, PositioningEngine> defaultFactory() {
        return (reference, initialHeadingRadians) ->
                new EkfParticlePositioningEngine(reference, initialHeadingRadians, 1L);
    }
}
