package com.openpositioning.PositionMe.presentation.correction;

/** Validated visual adjustment; lengths are meters. This never changes a saved trajectory. */
public final class StepLengthPreview {
    private final float stepLengthMeters;
    private final float scale;

    private StepLengthPreview(float stepLengthMeters, float scale) {
        this.stepLengthMeters = stepLengthMeters;
        this.scale = scale;
    }

    public static StepLengthPreview parse(String input, float originalStepLengthMeters,
                                          char decimalSeparator) {
        if (!isValidLength(originalStepLengthMeters)) {
            throw new IllegalArgumentException("No valid measured step length is available");
        }
        if (input == null || input.trim().isEmpty()) {
            throw new IllegalArgumentException("Enter a step length in meters");
        }
        String normalized = input.trim().replace(decimalSeparator, '.');
        // Reject grouping, hexadecimal/scientific forms and special IEEE values in a user field.
        if (!normalized.matches("(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)")) {
            throw new IllegalArgumentException("Enter a positive decimal length in meters");
        }
        final float length;
        try { length = Float.parseFloat(normalized); }
        catch (NumberFormatException failure) { throw new IllegalArgumentException("Invalid step length", failure); }
        float scale = length / originalStepLengthMeters;
        if (!isValidLength(length) || !isValidLength(scale)) {
            throw new IllegalArgumentException("Step length must be positive and finite");
        }
        return new StepLengthPreview(length, scale);
    }

    public static boolean isValidLength(float value) { return Float.isFinite(value) && value > 0f; }
    public float getStepLengthMeters() { return stepLengthMeters; }
    public float getScale() { return scale; }
}
