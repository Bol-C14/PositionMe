package com.openpositioning.PositionMe.presentation.correction;

import org.junit.Test;
import static org.junit.Assert.*;

public class StepLengthPreviewTest {
    @Test public void previewsAlwaysReferenceOriginalMeasurementAndRespectDecimalSeparator() {
        assertEquals(1.5f, StepLengthPreview.parse("0,75", .5f, ',').getScale(), .00001f);
        assertEquals(.75f, StepLengthPreview.parse(" .75 ", 1f, '.').getStepLengthMeters(), .00001f);
        assertEquals(2f, StepLengthPreview.parse("1.0", .5f, '.').getScale(), .00001f);
    }

    @Test public void rejectsInvalidInputWithoutProducingAnInfiniteScale() {
        for (String input : new String[]{null, "", " ", "0", "-1", "NaN", "Infinity", "1e40", "0x1.0p1", "1,000", "oops"}) {
            try { StepLengthPreview.parse(input, .6f, '.'); fail("Accepted: " + input); }
            catch (IllegalArgumentException expected) { }
        }
        for (float baseline : new float[]{0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.MIN_VALUE}) {
            try { StepLengthPreview.parse("10", baseline, '.'); fail("Accepted baseline: " + baseline); }
            catch (IllegalArgumentException expected) { }
        }
    }
}
