package com.openpositioning.PositionMe.domain.recording;

/** Immutable observable status for an auxiliary recording session. */
public final class SessionStatus {
    private final SessionState state;
    private final String failureMessage;

    private SessionStatus(SessionState state, String failureMessage) {
        this.state = state;
        this.failureMessage = failureMessage;
    }

    public static SessionStatus of(SessionState state) {
        return new SessionStatus(state, null);
    }

    public static SessionStatus failed(Throwable failure) {
        String message = failure == null ? "Recording session failed" : failure.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = failure == null ? "Recording session failed" : failure.getClass().getSimpleName();
        }
        return new SessionStatus(SessionState.FAILED, message);
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
}
