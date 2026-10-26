package com.openpositioning.PositionMe.presentation.recording;

/** Immutable state rendered by both recording controls. */
public final class RecordingUiState {
    public enum Effect { NONE, OPEN_CORRECTION }

    private final RecordingPhase phase;
    private final String detail;
    private final boolean permissionRequired;
    private final boolean permissionReady;
    private final long effectId;
    private final Effect effect;

    RecordingUiState(RecordingPhase phase, String detail, boolean permissionRequired,
                     long effectId, Effect effect) {
        this(phase, detail, permissionRequired, false, effectId, effect);
    }

    RecordingUiState(RecordingPhase phase, String detail, boolean permissionRequired,
                     boolean permissionReady, long effectId, Effect effect) {
        this.phase = phase;
        this.detail = detail;
        this.permissionRequired = permissionRequired;
        this.permissionReady = permissionReady;
        this.effectId = effectId;
        this.effect = effect;
    }

    public static RecordingUiState initial() {
        return new RecordingUiState(RecordingPhase.IDLE, null, false, 0L, Effect.NONE);
    }

    public RecordingPhase getPhase() { return phase; }
    public String getDetail() { return detail; }
    public boolean isPermissionRequired() { return permissionRequired; }
    public boolean isPermissionReady() { return permissionReady; }
    public long getEffectId() { return effectId; }
    public Effect getEffect() { return effect; }
    public boolean isBusy() { return phase == RecordingPhase.STARTING || phase == RecordingPhase.SAVING; }
    public boolean canAnnotate() { return phase == RecordingPhase.RECORDING; }
}
