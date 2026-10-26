package com.openpositioning.PositionMe.presentation.fragment;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.navigation.NavDirections;
import androidx.navigation.fragment.NavHostFragment;

import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.utils.PathView;
import com.openpositioning.PositionMe.utils.TrajectoryValidator;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MarkerOptions;

/**
 * A simple {@link Fragment} subclass. Corrections Fragment is displayed after a recording session
 * is finished to enable manual adjustments to the PDR. The adjustments are not saved as of now.
 */
public class CorrectionFragment extends Fragment {

    //Map variable
    public GoogleMap mMap;
    //Button to go to next
    private Button button;
    //Singleton SensorFusion class
    private SensorFusion sensorFusion = SensorFusion.getInstance();
    private TextView averageStepLengthText;
    private EditText stepLengthInput;
    private float averageStepLength;
    private float newStepLength;
    private int secondPass = 0;
    private CharSequence changedText;
    private static float scalingRatio = 0f;
    private static LatLng start;
    private PathView pathView;
    private TextView uploadStatusText;
    private Button uploadButton;
    private int viewGeneration;

    public CorrectionFragment() {
        // Required empty public constructor
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        final int generation = ++viewGeneration;
        AppCompatActivity activity = (AppCompatActivity) getActivity();
        if (activity != null && activity.getSupportActionBar() != null) {
            activity.getSupportActionBar().hide();
        }
        View rootView = inflater.inflate(R.layout.fragment_correction, container, false);

        //Obtain start position
        float[] startPosition = sensorFusion.getGNSSLatitude(true);
        final LatLng mapStartPosition = startPosition == null || startPosition.length < 2
                ? null : new LatLng(startPosition[0], startPosition[1]);

        // Initialize map fragment
        SupportMapFragment supportMapFragment=(SupportMapFragment)
                getChildFragmentManager().findFragmentById(R.id.map);

        this.button = rootView.findViewById(R.id.correction_done);


        supportMapFragment.getMapAsync(new OnMapReadyCallback() {
            @Override
            public void onMapReady(GoogleMap map) {
                if (generation != viewGeneration || getView() == null) return;
                mMap = map;
                mMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
                mMap.getUiSettings().setCompassEnabled(true);
                mMap.getUiSettings().setTiltGesturesEnabled(true);
                mMap.getUiSettings().setRotateGesturesEnabled(true);
                mMap.getUiSettings().setScrollGesturesEnabled(true);

                // Add a marker at the start position
                start = mapStartPosition;
                if (start != null) {
                    mMap.addMarker(new MarkerOptions().position(start).title("Start Position"));
                    updateCorrectionCamera();
                }
            }
        });

        return rootView;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        this.averageStepLengthText = view.findViewById(R.id.averageStepView);
        this.stepLengthInput = view.findViewById(R.id.inputStepLength);
        this.pathView = view.findViewById(R.id.pathView1);
        this.uploadStatusText = view.findViewById(R.id.uploadStatusText);
        this.uploadButton = view.findViewById(R.id.uploadRecordingButton);
        this.pathView.setTrajectory(sensorFusion.getRecordedPdrPathSnapshot());
        this.pathView.setScaleListener(scale -> {
            scalingRatio = scale;
            updateCorrectionCamera();
        });

        averageStepLength = sensorFusion.passAverageStepLength();
        averageStepLengthText.setText(getString(R.string.averageStepLgn) + ": "
                + String.format("%.2f", averageStepLength));

        // Listen for ENTER key
        this.stepLengthInput.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_ENTER) {
                newStepLength = Float.parseFloat(changedText.toString());
                // Rescale path
                pathView.redraw(newStepLength / averageStepLength);
                averageStepLengthText.setText(getString(R.string.averageStepLgn)
                        + ": " + String.format("%.2f", newStepLength));
                pathView.invalidate();

                secondPass++;
                if (secondPass == 2) {
                    averageStepLength = newStepLength;
                    secondPass = 0;
                }
            }
            return false;
        });

        this.stepLengthInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count,int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before,int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                changedText = s;
            }
        });




        this.button.setOnClickListener(v -> {
            NavController navController = NavHostFragment.findNavController(CorrectionFragment.this);
            NavDirections action = CorrectionFragmentDirections.actionCorrectionFragmentToRecordingFragment();
            navController.navigate(action);

            AppCompatActivity activity = (AppCompatActivity) getActivity();
            if (activity != null && activity.getSupportActionBar() != null) {
                activity.getSupportActionBar().show();
            }
        });

        this.uploadButton.setOnClickListener(v -> validateAndUpload());

    }

    public void setScalingRatio(float scalingRatio) {
        this.scalingRatio = scalingRatio;
    }

    private void updateCorrectionCamera() {
        if (mMap == null || start == null) return;
        float zoom = 19f;
        if (Float.isFinite(scalingRatio) && scalingRatio > 0f) {
            double zoomValue = Math.log(156543.03392f
                    * Math.cos(start.latitude * Math.PI / 180.0) * scalingRatio) / Math.log(2.0);
            if (Double.isFinite(zoomValue)) zoom = (float) Math.max(0.0, Math.min(21.0, zoomValue));
        }
        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(start, zoom));
    }

    /**
     * Runs pre-upload quality validation and either uploads directly (if clean)
     * or shows a warning dialog letting the user choose to proceed or cancel.
     */
    private void validateAndUpload() {
        if (getView() == null || uploadButton == null || !uploadButton.isEnabled()) return;
        if (uploadButton != null) uploadButton.setEnabled(false);
        TrajectoryValidator.ValidationResult result = sensorFusion.validateTrajectory();

        if (result.isClean()) {
            uploadAfterConfirmation();
            return;
        }

        String summary = result.buildSummary();
        Log.w("CorrectionFragment", "Trajectory quality issues:\n" + summary);

        if (!result.isPassed()) {
            // Blocking errors exist — warn strongly but still allow upload
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.validation_error_title)
                    .setMessage(getString(R.string.validation_error_message, summary))
                    .setPositiveButton(R.string.upload_anyway, (dialog, which) -> {
                        uploadAfterConfirmation();
                    })
                    .setNegativeButton(R.string.cancel_upload, (dialog, which) -> {
                        dialog.dismiss();
                        if (uploadButton != null) uploadButton.setEnabled(true);
                    })
                    .setCancelable(false)
                    .show();
        } else {
            // Only warnings — show lighter dialog
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.validation_warning_title)
                    .setMessage(getString(R.string.validation_warning_message, summary))
                    .setPositiveButton(R.string.upload_anyway, (dialog, which) -> {
                        uploadAfterConfirmation();
                    })
                    .setNegativeButton(R.string.cancel_upload, (dialog, which) -> {
                        dialog.dismiss();
                        if (uploadButton != null) uploadButton.setEnabled(true);
                    })
                    .setCancelable(false)
                    .show();
        }
    }

    private void uploadAfterConfirmation() {
        if (getView() == null || uploadButton == null) return;
        try {
            sensorFusion.sendTrajectoryToCloud();
            if (uploadStatusText != null) uploadStatusText.setText(R.string.upload_submitted);
        } catch (RuntimeException failure) {
            if (uploadStatusText != null) uploadStatusText.setText(R.string.upload_failed);
            uploadButton.setEnabled(true);
        }
    }

    @Override
    public void onDestroyView() {
        viewGeneration++;
        mMap = null;
        button = null;
        averageStepLengthText = null;
        stepLengthInput = null;
        pathView = null;
        uploadStatusText = null;
        uploadButton = null;
        super.onDestroyView();
    }
}
