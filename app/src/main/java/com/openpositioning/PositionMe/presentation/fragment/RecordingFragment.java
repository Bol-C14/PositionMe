package com.openpositioning.PositionMe.presentation.fragment;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.application.recording.RecordingSessionCollector;
import com.openpositioning.PositionMe.domain.recording.SessionState;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.sensors.SensorTypes;
import com.openpositioning.PositionMe.utils.UtilFunctions;


/**
 * Fragment presenting the active recording session and its map/calibration controls.
 *
 * <p>This fragment allows users to:
 * <ul>
 *   <li>Place and confirm calibration markers on the map</li>
 *   <li>Record labeled location data for machine learning model training</li>
 *   <li>Tag each calibration point with floor, indoor state, and building name</li>
 *   <li>Display current and average localization errors (GNSS, PDR, WiFi)</li>
 * </ul>
 *
 * <p>Sensor collection and persistence belong to the recording session. This fragment renders live
 * state and submits user actions such as test-point annotations.
 *
 * <p>This class works closely with:
 * <ul>
 *   <li>{@link com.openpositioning.PositionMe.sensors.SensorFusion} - for accessing fused location data</li>
 *   <li>{@link com.openpositioning.PositionMe.presentation.fragment.TrajectoryMapFragment} - for map visualization and marker handling</li>
 *   <li>{@link com.openpositioning.PositionMe.utils.CalibrationUtils} - for computing error metrics</li>
 * </ul>
 *
 * @see TrajectoryMapFragment
 * @see SensorFusion
 */
public class RecordingFragment extends Fragment {

    private Button completeButton;
    private Button savedSessionsButton;
    private android.widget.TextView recordingStatusText;
    private Button moreInfoButton;

    private TrajectoryMapFragment trajectoryMapFragment;
    private CalibrationFragment calibrationFragment;
    private StatusBottomSheetFragment statusBottomSheet;

    private SensorFusion sensorFusion;
    private RecordingSessionCollector recordingSessionCollector;
    private Handler refreshDataHandler;
    private int testPointIndex;
    private int viewGeneration;
    private boolean recordingActionPending;

    private float previousPosX = 0f;
    private float previousPosY = 0f;

    private final Runnable refreshDataTask = new Runnable() {
        @Override
        public void run() {
            updateUIandPosition();
            refreshDataHandler.postDelayed(this, 200);
        }
    };


    public RecordingFragment() {}

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sensorFusion = SensorFusion.getInstance();
        refreshDataHandler = new Handler();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_recording, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewGeneration++;

        recordingSessionCollector = sensorFusion.getRecordingSessionCollector();
        completeButton = view.findViewById(R.id.finishRecordingButton);
        savedSessionsButton = view.findViewById(R.id.savedSessionsButton);
        recordingStatusText = view.findViewById(R.id.recordingStatusText);
        moreInfoButton = view.findViewById(R.id.moreInfoButton);
        view.findViewById(R.id.addTestPointButton).setOnClickListener(v -> onAddTestPoint());

        trajectoryMapFragment = (TrajectoryMapFragment) getChildFragmentManager()
                .findFragmentById(R.id.trajectoryMapFragmentContainer);
        if (trajectoryMapFragment == null) {
            trajectoryMapFragment = new TrajectoryMapFragment.RecordingTrajectoryMapFragment();
            getChildFragmentManager()
                    .beginTransaction()
                    .replace(R.id.trajectoryMapFragmentContainer, trajectoryMapFragment)
                    .commitNow();
        }

        calibrationFragment = (CalibrationFragment) getChildFragmentManager()
                .findFragmentById(R.id.calibrationFragmentContainer);
        if (calibrationFragment == null) {
            calibrationFragment = CalibrationFragment.newInstance();
            getChildFragmentManager()
                    .beginTransaction()
                    .replace(R.id.calibrationFragmentContainer, calibrationFragment)
                    .commitNow();
        }
        calibrationFragment.setSensorFusion(sensorFusion);
        calibrationFragment.setRecordingSessionCollector(recordingSessionCollector);
        calibrationFragment.setTrajectoryMapFragment(trajectoryMapFragment);

        // Show/hide bottom sheet
        moreInfoButton.setOnClickListener(v -> {
            if (statusBottomSheet == null || !statusBottomSheet.isAdded()) {
                statusBottomSheet = new StatusBottomSheetFragment();
                statusBottomSheet.show(getChildFragmentManager(), "StatusBottomSheet");
            } else {
                statusBottomSheet.dismiss();
            }
        });

        // Toggle calibration
        Button toggleAdvancedMenuButton = view.findViewById(R.id.toggleCalibrationMenuButton);
        View calibrationContainer = view.findViewById(R.id.calibrationFragmentContainer);
        toggleAdvancedMenuButton.setOnClickListener(v -> {
            if (calibrationContainer.getVisibility() == View.VISIBLE) {
                calibrationContainer.setVisibility(View.GONE);
                toggleAdvancedMenuButton.setText("Tag");
            } else {
                calibrationContainer.setVisibility(View.VISIBLE);
                toggleAdvancedMenuButton.setText("Hide");
            }
        });

        completeButton.setOnClickListener(v -> onRecordingAction());
        savedSessionsButton.setOnClickListener(v ->
                Navigation.findNavController(v).navigate(R.id.filesFragment));
        renderRecordingState(sensorFusion.isRecording());

    }

    private void onAddTestPoint() {
        if (trajectoryMapFragment == null) return;
        LatLng current = trajectoryMapFragment.getCurrentLocation();
        if (current == null) {
            android.widget.Toast.makeText(requireContext(),
                    "Waiting for a location fix. Move a few steps or wait for the map to load.",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }

        long timestampMs = System.currentTimeMillis();
        int index = ++testPointIndex;
        sensorFusion.addTestPointToProto(timestampMs, current.latitude, current.longitude);
        trajectoryMapFragment.addTestPointMarker(index, timestampMs, current);
    }

    private void onRecordingAction() {
        if (recordingActionPending) return;
        final int generation = viewGeneration;
        if (sensorFusion.isRecording()) {
            recordingActionPending = true;
            completeButton.setEnabled(false);
            savedSessionsButton.setEnabled(false);
            recordingStatusText.setText(R.string.saving_recording);
            sensorFusion.stopAndSave().whenComplete((status, failure) -> mainHandler().post(() -> {
                recordingActionPending = false;
                if (!isCurrentView(generation)) return;
                if (failure != null || status == null || status.isFailure()) {
                    String detail = failure != null ? failureMessage(failure)
                            : status == null ? "Unknown save error" : status.getFailureMessage();
                    recordingStatusText.setText(getString(R.string.recording_save_failed, detail));
                    completeButton.setEnabled(true);
                    completeButton.setText(R.string.start_recording_action);
                    savedSessionsButton.setEnabled(true);
                    return;
                }
                if (status.hasWarning()) {
                    String detail = status.getFailureMessage();
                    String warning = getString(R.string.recording_saved_export_warning,
                            detail == null || detail.trim().isEmpty()
                                    ? getString(R.string.export_failed_brief) : detail);
                    recordingStatusText.setText(warning);
                    android.widget.Toast.makeText(requireContext(), warning,
                            android.widget.Toast.LENGTH_LONG).show();
                } else {
                    recordingStatusText.setText(R.string.recording_saved);
                }
                View currentView = getView();
                if (currentView == null) return;
                Navigation.findNavController(currentView).navigate(
                        RecordingFragmentDirections.actionRecordingFragmentToCorrectionFragment());
            }));
            return;
        }

        completeButton.setEnabled(false);
        savedSessionsButton.setEnabled(false);
        recordingStatusText.setText(R.string.starting_recording);
        if (!hasRecordingPermissions()) {
            recordingStatusText.setText(R.string.recording_permission_denied);
            completeButton.setEnabled(true);
            savedSessionsButton.setEnabled(true);
            return;
        }
        float[] startPosition = sensorFusion.getGNSSLatitude(false);
        sensorFusion.setStartGNSSLatitude(startPosition);
        recordingActionPending = true;
        sensorFusion.startRecordingAsync().whenComplete((status, failure) -> mainHandler().post(() -> {
            recordingActionPending = false;
            if (!isCurrentView(generation)) return;
            if (failure != null || status == null || status.isFailure()) {
                Throwable cause = unwrap(failure);
                String detail = failure != null ? failureMessage(failure)
                        : status == null ? "Unknown start error" : status.getFailureMessage();
                if (cause instanceof SecurityException || (detail != null
                        && detail.toLowerCase(java.util.Locale.ROOT).contains("permission"))) {
                    recordingStatusText.setText(R.string.recording_permission_denied);
                } else {
                    recordingStatusText.setText(getString(R.string.recording_start_failed, detail));
                }
                completeButton.setEnabled(true);
                completeButton.setText(R.string.start_recording_action);
                savedSessionsButton.setEnabled(true);
                return;
            }
            renderRecordingState(true);
            savedSessionsButton.setEnabled(true);
        }));
    }

    private void renderRecordingState(boolean recording) {
        if (completeButton == null || recordingStatusText == null) return;
        if (recordingActionPending) return;
        completeButton.setEnabled(true);
        completeButton.setText(recording ? R.string.stop_and_save : R.string.start_recording_action);
        completeButton.setContentDescription(getString(recording
                ? R.string.stop_and_save : R.string.start_recording_action));
        recordingStatusText.setText(recording ? R.string.recording_started
                : R.string.recording_not_started);
        if (savedSessionsButton != null) savedSessionsButton.setEnabled(true);
    }

    private Handler mainHandler() {
        return new Handler(Looper.getMainLooper());
    }

    private boolean isCurrentView(int generation) {
        return isAdded() && generation == viewGeneration && getView() != null
                && completeButton != null && recordingStatusText != null;
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current != null && current.getCause() != null) current = current.getCause();
        return current;
    }

    private static String failureMessage(Throwable failure) {
        Throwable cause = unwrap(failure);
        String message = cause == null ? null : cause.getMessage();
        return message == null || message.trim().isEmpty() ? "Please try again." : message;
    }

    private boolean hasRecordingPermissions() {
        boolean locationGranted = ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean activityGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                || ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED;
        return locationGranted && activityGranted;
    }

    private void updateUIandPosition() {
        if (getView() == null || trajectoryMapFragment == null) return;
        updateRecordingStatusFromOwner();
        if (calibrationFragment != null) calibrationFragment.updateRecordingControls();
        float[] pdrValues = sensorFusion.getSensorValueMap().get(SensorTypes.PDR);
        if (pdrValues == null) return;

        // Update elevation
        float elevationVal = sensorFusion.getElevation();
        if (statusBottomSheet != null && statusBottomSheet.isAdded()) {
            statusBottomSheet.updateElevation(String.format("%.1f", elevationVal));
        }

        // Update PDR (Pedestrian Dead Reckoning)
        float[] latLngArray = sensorFusion.getGNSSLatitude(true);
        if (latLngArray != null) {
            LatLng oldLocation = trajectoryMapFragment.getCurrentLocation();
            LatLng newLocation = UtilFunctions.calculateNewPos(
                    oldLocation == null ? new LatLng(latLngArray[0], latLngArray[1]) : oldLocation,
                    new float[]{pdrValues[0] - previousPosX, pdrValues[1] - previousPosY}
            );
            if (trajectoryMapFragment != null) {
                trajectoryMapFragment.updateUserLocation(newLocation,
                        (float) Math.toDegrees(sensorFusion.passOrientation()));
            }
        }

        // Update GNSS error
        float[] gnss = sensorFusion.getSensorValueMap().get(SensorTypes.GNSSLATLONG);
        if (gnss != null && trajectoryMapFragment != null) {
            if (trajectoryMapFragment.isGnssEnabled()) {
                LatLng gnssLocation = new LatLng(gnss[0], gnss[1]);
                LatLng currentLoc = trajectoryMapFragment.getCurrentLocation();
                if (currentLoc != null) {
                    double errorDist = UtilFunctions.distanceBetweenPoints(currentLoc, gnssLocation);
                    if (statusBottomSheet != null && statusBottomSheet.isAdded()) {
                        statusBottomSheet.updateGnssError(String.format("%.2fm", errorDist), View.VISIBLE);
                    }
                }
                trajectoryMapFragment.updateGNSS(gnssLocation);
            } else {
                if (statusBottomSheet != null && statusBottomSheet.isAdded()) {
                    statusBottomSheet.updateGnssError("", View.GONE);
                }
                trajectoryMapFragment.clearGNSS();
            }
        }

        previousPosX = pdrValues[0];
        previousPosY = pdrValues[1];
    }

    private void updateRecordingStatusFromOwner() {
        if (recordingActionPending || completeButton == null || recordingStatusText == null) return;
        recordingSessionCollector = sensorFusion.getRecordingSessionCollector();
        boolean recording = sensorFusion.isRecording();
        renderRecordingState(recording);
        if (!recording && recordingSessionCollector != null
                && recordingSessionCollector.getStatus().getState() == SessionState.FAILED) {
            String detail = recordingSessionCollector.getStatus().getFailureMessage();
            recordingStatusText.setText(getString(R.string.recording_session_failed,
                    detail == null || detail.trim().isEmpty() ? "Please check Saved sessions." : detail));
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        refreshDataHandler.removeCallbacks(refreshDataTask);
    }

    @Override
    public void onResume() {
        super.onResume();
        updateRecordingStatusFromOwner();
        refreshDataHandler.postDelayed(refreshDataTask, 500);
    }


    @Override
    public void onDestroyView() {
        viewGeneration++;
        refreshDataHandler.removeCallbacks(refreshDataTask);
        completeButton = null;
        savedSessionsButton = null;
        recordingStatusText = null;
        moreInfoButton = null;
        trajectoryMapFragment = null;
        calibrationFragment = null;
        statusBottomSheet = null;
        super.onDestroyView();
    }
}
