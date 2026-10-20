package com.openpositioning.PositionMe.presentation.fragment;

import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.material.button.MaterialButton;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.application.recording.RecordingSessionCollector;
import com.openpositioning.PositionMe.domain.recording.SessionState;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.utils.CalibrationUtils;

import org.json.JSONObject;


/**
 * CalibrationFragment that:
 *  - Displays recording state and enables session-owned calibration annotations.
 *  - Enables calibration tagging only if recording is active.
 *  - Counts and displays how many total data records have been stored.
 *  - Compute and display the current and average errors for PDR, GNSS, and WiFi.
 *
 * @see RecordingFragment that handles the actual recording of sensor data, containing
 * the logic for the recording process and contains this sub-fragment.
 *
 * @author Shu Gu
 */
public class CalibrationFragment extends Fragment {

    private static final String TAG = "CalibrationFragment";

    // UI references
    private Spinner floorSpinner;
    private Spinner indoorStateSpinner;
    private EditText buildingNameEditText;
    private MaterialButton calibrationButton, startRecordingButton;
    private TextView pointCountTextView;   // Displays total collected data points
    private TextView errorTextView;        // Displays current and average errors

    // Data references
    private SensorFusion sensorFusion;
    private RecordingSessionCollector recordingSessionCollector;
    private TrajectoryMapFragment trajectoryMapFragment;

    // Marker & calibration state
    private Marker calibrationMarker = null;
    private boolean markerPlaced = false;
    private int selectedFloorLevel = -1;
    private int selectedIndoorState = 0;
    private String buildingName = null;

    // Count of confirmed calibration annotations.
    private int recordCount = 0;

    // Error computation and tracking
    private double totalPdrError = 0;
    private double totalGnssError = 0;
    private double totalWifiError = 0;
    private int calibrationCount = 0;

    public CalibrationFragment() {
        // Required empty constructor
    }

    public static CalibrationFragment newInstance() {
        return new CalibrationFragment();
    }

    /**
     * Optional: these setter methods let the parent inject references if needed.
     */
    public void setSensorFusion(SensorFusion sensorFusion) {
        this.sensorFusion = sensorFusion;
    }
    public void setRecordingSessionCollector(RecordingSessionCollector collector) {
        this.recordingSessionCollector = collector;
    }
    public void setTrajectoryMapFragment(TrajectoryMapFragment mapFragment) {
        this.trajectoryMapFragment = mapFragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_calibration, container, false);

        // Initialize UI references
        buildingNameEditText   = view.findViewById(R.id.buildingNameEditText);
        floorSpinner           = view.findViewById(R.id.floorSpinner);
        indoorStateSpinner     = view.findViewById(R.id.indoorStateSpinner);
        calibrationButton      = view.findViewById(R.id.toggleCalibrationButton);
        startRecordingButton   = view.findViewById(R.id.startRecordingButton);
        pointCountTextView     = view.findViewById(R.id.pointCountTextView);
        errorTextView          = view.findViewById(R.id.errorTextView); // 新增的 TextView

        // Setup spinners
        setupFloorSpinner();
        setupIndoorStateSpinner();

        // Initially disable calibration button until recording is started
        calibrationButton.setEnabled(false);
        calibrationButton.setText("Add Tag");
        calibrationButton.setOnClickListener(v -> {
            if (!markerPlaced) {
                placeCalibrationMarker();
                markerPlaced = true;
                calibrationButton.setText("Confirm");
                Log.d(TAG, "Calibration marker placed.");
            } else {
                confirmCalibration();
                calibrationMarker = null;
                markerPlaced = false;
                calibrationButton.setText("Add Tag");
            }
        });

        startRecordingButton.setOnClickListener(v -> toggleSessionRecording());

        // Show initial count = 0
        updatePointCountDisplay();
        updateRecordingControls();

        return view;
    }

    /**
     * Floor level spinner
     */
    private void setupFloorSpinner() {
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                requireContext(),
                R.array.floor_levels,
                android.R.layout.simple_spinner_item
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        floorSpinner.setAdapter(adapter);
        floorSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                String selected = parent.getItemAtPosition(position).toString();
                if (selected.contains("Outdoors") || selected.startsWith("-1")) {
                    selectedFloorLevel = -1;
                } else {
                    try {
                        selectedFloorLevel = Integer.parseInt(selected.split(" ")[0]);
                    } catch (NumberFormatException e) {
                        selectedFloorLevel = -1;
                    }
                }
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
    }

    /**
     * Indoor state spinner
     */
    private void setupIndoorStateSpinner() {
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                requireContext(),
                R.array.indoor_states,
                android.R.layout.simple_spinner_item
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        indoorStateSpinner.setAdapter(adapter);
        indoorStateSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                try {
                    // We assume the array items are like: "0 - Outdoor", "1 - Indoor", etc.
                    selectedIndoorState = Integer.parseInt(
                            parent.getItemAtPosition(position).toString().substring(0, 1)
                    );
                } catch (Exception e) {
                    selectedIndoorState = 0;
                }
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
    }

    /**
     * Place a draggable marker for calibration
     */
    private void placeCalibrationMarker() {
        if (trajectoryMapFragment == null || trajectoryMapFragment.getGoogleMap() == null) {
            Toast.makeText(getContext(), "Map not ready!", Toast.LENGTH_SHORT).show();
            return;
        }
        GoogleMap map = trajectoryMapFragment.getGoogleMap();

        if (calibrationMarker != null) {
            map.animateCamera(
                    com.google.android.gms.maps.CameraUpdateFactory.newLatLng(
                            calibrationMarker.getPosition())
            );
            return;
        }
        LatLng userLocation = trajectoryMapFragment.getCurrentLocation();
        if (userLocation == null) {
            userLocation = new LatLng(55.9228, -3.1746); // fallback
        }
        calibrationMarker = map.addMarker(new MarkerOptions()
                .position(userLocation)
                .title("Calibration Marker")
                .draggable(true));

        map.animateCamera(
                com.google.android.gms.maps.CameraUpdateFactory.newLatLngZoom(userLocation, 19f)
        );

        trajectoryMapFragment.updateCalibrationPinLocation(userLocation, false);

        map.setOnMarkerDragListener(new GoogleMap.OnMarkerDragListener() {
            @Override public void onMarkerDragStart(@NonNull Marker marker) {}
            @Override public void onMarkerDrag(@NonNull Marker marker) {}
            @Override public void onMarkerDragEnd(@NonNull Marker marker) {
                if (calibrationMarker != null) {
                    calibrationMarker.setPosition(marker.getPosition());
                    trajectoryMapFragment.updateCalibrationPinLocation(marker.getPosition(), false);
                }
            }
        });
    }

    /**
     * Confirm the marker location, store calibration record,
     * And compute the errors for PDR, GNSS, and WiFi.
     */
    private void confirmCalibration() {
        if (calibrationMarker == null) {
            Toast.makeText(getContext(), "No calibration marker placed!", Toast.LENGTH_SHORT).show();
            return;
        }
        buildingName = buildingNameEditText.getText().toString();
        if (TextUtils.isEmpty(buildingName)) {
            buildingName = null;
        }
        LatLng markerPos = calibrationMarker.getPosition();
        double lat = markerPos.latitude;
        double lng = markerPos.longitude;

        trajectoryMapFragment.updateCalibrationPinLocation(markerPos, true);
        Toast.makeText(getContext(),
                "Calibration confirmed at (" + lat + ", " + lng + ")",
                Toast.LENGTH_SHORT).show();

        // get all sensor data
        JSONObject record = sensorFusion.getAllSensorData();
        // get gnss position
        double gnssLat = record.optDouble("gnssLat", Double.NaN);
        double gnssLon = record.optDouble("gnssLon", Double.NaN);
        LatLng gnssPos = (!Double.isNaN(gnssLat) && !Double.isNaN(gnssLon)) ? new LatLng(gnssLat, gnssLon) : null;

        // get pdr position
        double pdrX = record.optDouble("pdrX", Double.NaN);
        double pdrY = record.optDouble("pdrY", Double.NaN);
        // convert pdr from meters to latitude/longitude
        pdrX = lat + (pdrX / 111320); // 1 degree latitude ~ 111.32 km
        pdrY = lng + (pdrY / (111320 * Math.cos(Math.toRadians(lat)))); // 1 degree longitude ~ 111.32 km * cos(latitude)

        LatLng pdrPos = (!Double.isNaN(pdrX) && !Double.isNaN(pdrY)) ? new LatLng(pdrX, pdrY) : null;

        // WiFi position get
        double wifiLat = record.optDouble("wifiLat", Double.NaN);
        double wifiLon = record.optDouble("wifiLon", Double.NaN);
        LatLng wifiPos = (!Double.isNaN(wifiLat) && !Double.isNaN(wifiLon)) ? new LatLng(wifiLat, wifiLon) : null;


        // Use the CalibrationUtils to calculate errors
        CalibrationUtils.CalibrationErrors errors = CalibrationUtils.calculateCalibrationErrors(markerPos, gnssPos, pdrPos, wifiPos);
        double currentGnssError = errors.gnssError;
        double currentPdrError = errors.pdrError;
        double currentWifiError = errors.wifiError;

        // Update total errors
        if (currentGnssError >= 0) {
            totalGnssError += currentGnssError;
        }
        if (currentPdrError >= 0) {
            totalPdrError += currentPdrError;
        }
        if (currentWifiError >= 0) {
            totalWifiError += currentWifiError;
        }
        calibrationCount++;

        // Compute average errors
        double avgGnssError = (calibrationCount > 0) ? totalGnssError / calibrationCount : 0;
        double avgPdrError = (calibrationCount > 0) ? totalPdrError / calibrationCount : 0;
        double avgWifiError = (calibrationCount > 0) ? totalWifiError / calibrationCount : 0;

        Log.d(TAG, String.format("Calibration Error — PDR: %.2f m, GNSS: %.2f m, WiFi: %.2f m",
                currentPdrError, currentGnssError, currentWifiError));
        Log.d(TAG, String.format("Avg Error after %d tags — PDR: %.2f m, GNSS: %.2f m, WiFi: %.2f m",
                calibrationCount, avgPdrError, avgGnssError, avgWifiError));

        // Update the error text view
        if (errorTextView != null) {
            String errorText = "Current Errors:\n" +
                    String.format("PDR: %.2f m, GNSS: %.2f m, WiFi: %.2f m\n", currentPdrError, currentGnssError, currentWifiError) +
                    "Average Errors:\n" +
                    String.format("PDR: %.2f m, GNSS: %.2f m, WiFi: %.2f m", avgPdrError, avgGnssError, avgWifiError);
            errorTextView.setText(errorText);
        }

        // Then trigger the calibration marker update
        onCalibrationTriggered(lat, lng, selectedIndoorState, selectedFloorLevel, buildingName);
        calibrationMarker.remove();
    }

    /**
     * Create a calibration record and add to dataFileManager.
     */
    private void onCalibrationTriggered(double userLat,
                                        double userLng,
                                        int indoorState,
                                        int floorLevel,
                                        String buildingName) {
        if (sensorFusion == null || recordingSessionCollector == null) {
            Log.e(TAG, "Cannot trigger calibration without an active recording session.");
            return;
        }
        try {
            JSONObject calibrationRecord = sensorFusion.getAllSensorData();
            calibrationRecord.put("isCalibration", true);
            calibrationRecord.put("userLat", userLat);
            calibrationRecord.put("userLng", userLng);
            calibrationRecord.put("floorLevel", floorLevel);
            calibrationRecord.put("indoorState", indoorState);
            calibrationRecord.put("buildingName", buildingName);

            recordingSessionCollector.append(calibrationRecord);
            recordCount++;
            updatePointCountDisplay();
            Log.d(TAG, "Calibration triggered and saved.");
        } catch (Exception e) {
            Log.e(TAG, "Error triggering calibration", e);
        }
    }


    private void toggleSessionRecording() {
        if (sensorFusion == null) return;
        if (sensorFusion.isRecording()) {
            sensorFusion.stopRecording();
            Toast.makeText(getContext(), "Recording stopped and saved.", Toast.LENGTH_SHORT).show();
        } else {
            sensorFusion.setStartGNSSLatitude(sensorFusion.getGNSSLatitude(false));
            sensorFusion.startRecording();
        }
        updateRecordingControls();
    }

    /** Updates controls from the session owner without collecting or persisting records here. */
    public void updateRecordingControls() {
        if (calibrationButton == null || startRecordingButton == null) return;
        if (recordingSessionCollector == null && sensorFusion != null) {
            recordingSessionCollector = sensorFusion.getRecordingSessionCollector();
        }
        boolean running = sensorFusion != null && sensorFusion.isRecording()
                && recordingSessionCollector != null
                && recordingSessionCollector.getStatus().getState() == SessionState.RUNNING;
        calibrationButton.setEnabled(running);
        startRecordingButton.setText(running ? "Stop recording" : "Start recording");
    }

    private void updatePointCountDisplay() {
        if (pointCountTextView != null) {
            pointCountTextView.setText(
                    getString(R.string.point_count_format, recordCount)
            );
        }
    }

}
