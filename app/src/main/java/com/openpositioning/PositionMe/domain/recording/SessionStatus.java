package com.openpositioning.PositionMe.domain.recording;

/** Immutable observable status for an auxiliary recording session. */
public final class SessionStatus {
    private final SessionState state;
    private final String failureMessage;
    private final boolean warning;

    private SessionStatus(SessionState state, String failureMessage, boolean warning) {
        this.state = state;
        this.failureMessage = failureMessage;
        this.warning = warning;
    }

    public static SessionStatus of(SessionState state) {
        return new SessionStatus(state, null, false);
    }

    public static SessionStatus failed(Throwable failure) {
        String message = failure == null ? "Recording session failed" : failure.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = failure == null ? "Recording session failed" : failure.getClass().getSimpleName();
        }
        return new SessionStatus(SessionState.FAILED, message, false);
    }

    /** Indicates the private recording is saved while a secondary export step needs attention. */
    public static SessionStatus savedWithWarning(Throwable warning) {
        String message = warning == null ? "Saved privately; Downloads export needs retry."
                : warning.getMessage();
        if (message == null || message.trim().isEmpty()) message = warning.getClass().getSimpleName();
        return new SessionStatus(SessionState.STOPPED, message, true);
    }

    public SessionState getState() {
        return state;
    }

    public String getFailureMessage() {
        return failureMessage;
    }

    public boolean isFailure() {
        return state == SessionState.FAILED;
    }

    public boolean hasWarning() { return warning; }
}
