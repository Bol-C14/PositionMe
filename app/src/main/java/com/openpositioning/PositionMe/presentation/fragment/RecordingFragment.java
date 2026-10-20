package com.openpositioning.PositionMe.presentation.fragment;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavDirections;
import androidx.navigation.Navigation;
import androidx.preference.PreferenceManager;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.application.recording.RecordingSessionCollector;
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
    private Button moreInfoButton;

    private TrajectoryMapFragment trajectoryMapFragment;
    private CalibrationFragment calibrationFragment;
    private StatusBottomSheetFragment statusBottomSheet;

    private SensorFusion sensorFusion;
    private RecordingSessionCollector recordingSessionCollector;
    private SharedPreferences settings;

    private Handler refreshDataHandler;
    private CountDownTimer autoStop;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable startRecordingTask;
    private long splitStopAtElapsedRealtime;
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
        Context context = requireActivity();
        settings = PreferenceManager.getDefaultSharedPreferences(context);
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

        recordingSessionCollector = sensorFusion.getRecordingSessionCollector();
        if (!sensorFusion.isRecording()) {
            splitStopAtElapsedRealtime = 0L;
            float[] startPosition = sensorFusion.getGNSSLatitude(false);
            sensorFusion.setStartGNSSLatitude(startPosition);
            startRecordingTask = () -> {
                if (!sensorFusion.isRecording()) sensorFusion.startRecording();
            };
            mainHandler.postDelayed(startRecordingTask, 3000);
        }

        completeButton = view.findViewById(R.id.finishRecordingButton);
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

        // When the finish button is clicked, stop recording and navigate to CorrectionFragment
        completeButton.setOnClickListener(v -> {
            if (autoStop != null) autoStop.cancel();
            sensorFusion.stopRecording();

            // use Safe Args to navigate CorrectionFragment
            NavDirections action = RecordingFragmentDirections.actionRecordingFragmentToCorrectionFragment();
            Navigation.findNavController(v).navigate(action);
        });

        // If split trajectory timing is enabled
        if (settings.getBoolean("split_trajectory", false)) {
            long limit = settings.getInt("split_duration", 30) * 60000L;
            if (splitStopAtElapsedRealtime == 0L) {
                splitStopAtElapsedRealtime = SystemClock.elapsedRealtime() + limit;
            }
            long remaining = Math.max(0L, splitStopAtElapsedRealtime - SystemClock.elapsedRealtime());
            autoStop = new CountDownTimer(remaining, 1000) {
                @Override
                public void onTick(long millisUntilFinished) {
                    if (statusBottomSheet != null && statusBottomSheet.isAdded()) {
                        statusBottomSheet.incrementProgress();
                    }
                    updateUIandPosition();
                }

                @Override
                public void onFinish() {
                    sensorFusion.stopRecording();

                    // When the timer ends, navigate to CorrectionFragment
                    NavDirections action = RecordingFragmentDirections.actionRecordingFragmentToCorrectionFragment();
                    Navigation.findNavController(getView()).navigate(action);
                }
            }.start();
        }

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

    private void updateUIandPosition() {
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

    @Override
    public void onPause() {
        super.onPause();
        refreshDataHandler.removeCallbacks(refreshDataTask);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!settings.getBoolean("split_trajectory", false)) {
            refreshDataHandler.postDelayed(refreshDataTask, 500);
        }
    }


    @Override
    public void onDestroyView() {
        refreshDataHandler.removeCallbacks(refreshDataTask);
        if (startRecordingTask != null) mainHandler.removeCallbacks(startRecordingTask);
        if (autoStop != null) autoStop.cancel();
        super.onDestroyView();
    }
}
