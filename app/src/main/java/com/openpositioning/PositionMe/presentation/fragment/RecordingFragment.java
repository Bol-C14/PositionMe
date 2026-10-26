package com.openpositioning.PositionMe.presentation.fragment;

import android.os.Bundle;
import android.os.Handler;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.Navigation;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.presentation.permissions.RecordingPermissionHost;
import com.openpositioning.PositionMe.presentation.recording.RecordingPhase;
import com.openpositioning.PositionMe.presentation.recording.RecordingUiState;
import com.openpositioning.PositionMe.presentation.recording.RecordingViewModel;
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
    private RecordingViewModel recordingViewModel;
    private Handler refreshDataHandler;
    private int testPointIndex;

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
        recordingViewModel = new ViewModelProvider(requireActivity()).get(RecordingViewModel.class);
        completeButton = view.findViewById(R.id.finishRecordingButton);
        savedSessionsButton = view.findViewById(R.id.savedSessionsButton);
        recordingStatusText = view.findViewById(R.id.recordingStatusText);
        moreInfoButton = view.findViewById(R.id.moreInfoButton);
        View manageRecordingAccessButton = view.findViewById(R.id.manageRecordingAccessButton);
        if (manageRecordingAccessButton != null) {
            manageRecordingAccessButton.setOnClickListener(v -> {
                if (requireActivity() instanceof RecordingPermissionHost) {
                    ((RecordingPermissionHost) requireActivity()).openRecordingPermissionSettings();
                }
            });
        }
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
        calibrationFragment.setRecordingViewModel(recordingViewModel);
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
        recordingViewModel.getState().observe(getViewLifecycleOwner(), this::renderRecordingState);

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
        RecordingUiState state = recordingViewModel.getState().getValue();
        if (state != null && state.getPhase() == RecordingPhase.RECORDING) {
            recordingViewModel.stop();
            return;
        }
        if (!hasRecordingPermissions()) {
            recordingViewModel.permissionDenied();
            if (requireActivity() instanceof RecordingPermissionHost) {
                ((RecordingPermissionHost) requireActivity()).requestRecordingPermissions();
            }
            return;
        }
        recordingViewModel.start();
    }

    private void renderRecordingState(RecordingUiState state) {
        if (completeButton == null || recordingStatusText == null) return;
        RecordingPhase phase = state.getPhase();
        boolean busy = state.isBusy();
        completeButton.setEnabled(!busy);
        completeButton.setText(phase == RecordingPhase.RECORDING
                ? R.string.stop_and_save : R.string.start_recording_action);
        completeButton.setContentDescription(getString(phase == RecordingPhase.RECORDING
                ? R.string.stop_and_save : R.string.start_recording_action));
        if (savedSessionsButton != null) savedSessionsButton.setEnabled(!busy);
        switch (phase) {
            case STARTING: recordingStatusText.setText(R.string.m3_recording_starting); break;
            case RECORDING: recordingStatusText.setText(R.string.m3_recording_active); break;
            case SAVING: recordingStatusText.setText(R.string.m3_recording_saving); break;
            case SAVED: recordingStatusText.setText(R.string.m3_recording_saved); break;
            case WARNING:
                recordingStatusText.setText(state.isPermissionRequired()
                        ? getString(R.string.m3_recording_permission_retry)
                        : getString(R.string.m3_recording_export_warning, detailOrRetry(state.getDetail())));
                break;
            case ERROR:
                recordingStatusText.setText(getString(R.string.m3_recording_error, detailOrRetry(state.getDetail())));
                break;
            case IDLE:
                recordingStatusText.setText(state.isPermissionReady()
                        ? R.string.m3_recording_permission_ready : R.string.m3_recording_idle);
                break;
            default: recordingStatusText.setText(R.string.m3_recording_idle); break;
        }
        if (state.getEffect() == RecordingUiState.Effect.OPEN_CORRECTION) {
            long effectId = state.getEffectId();
            Navigation.findNavController(requireView()).navigate(
                    RecordingFragmentDirections.actionRecordingFragmentToCorrectionFragment());
            recordingViewModel.consumeEffect(effectId);
        }
    }

    private String detailOrRetry(String detail) {
        return detail == null || detail.trim().isEmpty() ? getString(R.string.m3_recording_retry) : detail;
    }

    private boolean hasRecordingPermissions() {
        boolean locationGranted = androidx.core.content.ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean activityGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                || androidx.core.content.ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED;
        return locationGranted && activityGranted;
    }

    private void updateUIandPosition() {
        if (getView() == null || trajectoryMapFragment == null) return;
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

    @Override
    public void onPause() {
        super.onPause();
        refreshDataHandler.removeCallbacks(refreshDataTask);
    }

    @Override
    public void onResume() {
        super.onResume();
        RecordingUiState state = recordingViewModel == null ? null : recordingViewModel.getState().getValue();
        if (state != null && state.isPermissionRequired() && hasRecordingPermissions()) {
            recordingViewModel.permissionGranted();
        }
        refreshDataHandler.postDelayed(refreshDataTask, 500);
    }


    @Override
    public void onDestroyView() {
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
