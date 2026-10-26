package com.openpositioning.PositionMe.presentation.permissions;

/**
 * Narrow permission boundary used by recording UI when the user explicitly starts recording.
 */
public interface RecordingPermissionHost {
    void requestRecordingPermissions();

    /** Opens app settings so the user can manage recording and optional access at any time. */
    void openRecordingPermissionSettings();
}
