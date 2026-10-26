package com.openpositioning.PositionMe.presentation.activity;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.view.View;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.navigation.NavController;
import androidx.navigation.ui.NavigationUI;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.presentation.permissions.PermissionPolicy;
import com.openpositioning.PositionMe.presentation.permissions.RecordingPermissionHost;
import com.openpositioning.PositionMe.sensors.Observer;
import com.openpositioning.PositionMe.sensors.SensorFusion;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Main navigation host and explicit permission entry point for recording. */
public class MainActivity extends AppCompatActivity implements Observer, RecordingPermissionHost {
    private static final String PERMISSION_PREFERENCES = "recording_permission_state";
    private static final String OPTIONAL_PROMPT_ANSWERED = "optional_prompt_answered";
    private static final String REQUESTED_PREFIX = "requested.";

    private NavController navController;
    private ActivityResultLauncher<String[]> multiplePermissionsLauncher;
    private final Handler httpResponseHandler = new Handler();
    private SensorFusion sensorFusion;
    private List<String> permissionsInFlight = new ArrayList<>();
    private Set<String> previouslyRequestedInFlight = new HashSet<>();
    private boolean requiredRequestInFlight;
    private boolean serverObserverRegistered;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);

        // Register before setContentView because the nav host may create its start fragment there.
        multiplePermissionsLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(), this::onPermissionResult);

        sensorFusion = SensorFusion.getInstance();
        sensorFusion.setContext(getApplicationContext());

        setContentView(R.layout.activity_main);
        View content = findViewById(R.id.main_root);
        ViewCompat.setOnApplyWindowInsetsListener(content, (view, insets) -> {
            int systemTypes = WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout();
            Insets safe = insets.getInsets(systemTypes);
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return new WindowInsetsCompat.Builder(insets).setInsets(systemTypes, Insets.NONE).build();
        });
        ViewCompat.requestApplyInsets(content);

        NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager()
                .findFragmentById(R.id.nav_host_fragment);
        navController = Objects.requireNonNull(navHostFragment).getNavController();
        BottomNavigationView bottomNavigationView = findViewById(R.id.bottom_navigation);
        NavigationUI.setupWithNavController(bottomNavigationView, navController);
        OnBackPressedCallback leaveRecording = new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                com.openpositioning.PositionMe.application.recording.RecordingOperationSnapshot operation =
                        sensorFusion.getRecordingOperationSnapshot();
                boolean active = operation.isRecording() || operation.isStarting() || operation.isSaving();
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(R.string.exit_positionme_title)
                        .setMessage(active ? R.string.exit_positionme_active : R.string.exit_positionme_idle)
                        .setPositiveButton(active ? R.string.go_to_background : R.string.close_positionme,
                                (dialog, which) -> {
                                    if (active) moveTaskToBack(true);
                                    else finish();
                                })
                        .setNegativeButton(R.string.stay_in_positionme, null)
                        .show();
            }
        };
        getOnBackPressedDispatcher().addCallback(this, leaveRecording);
        navController.addOnDestinationChangedListener((controller, destination, arguments) ->
                leaveRecording.setEnabled(destination.getId() == R.id.recordingFragment));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Returning from app settings only refreshes current access. It never starts a prompt.
        updatePermissionDependentServices();
    }

    @Override
    public void requestRecordingPermissions() {
        List<String> missing = PermissionPolicy.missingRequiredPermissions(
                Build.VERSION.SDK_INT, grantedPermissions());
        if (missing.isEmpty()) {
            promptForOptionalPermissionsIfNeeded();
            return;
        }

        Set<String> requestedBefore = previouslyRequestedPermissions();
        Set<String> rationale = permissionsNeedingRationale(missing);
        if (!PermissionPolicy.permanentlyDenied(missing, requestedBefore, rationale).isEmpty()) {
            showSettingsDialog(true);
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.permission_recording_title)
                .setMessage(R.string.permission_recording_rationale)
                .setPositiveButton(R.string.permission_continue,
                        (dialog, which) -> launchPermissions(PermissionPolicy.permissionsToRequest(
                                Build.VERSION.SDK_INT, grantedPermissions()), true))
                .setNegativeButton(R.string.permission_not_now, null)
                .show();
    }

    private void promptForOptionalPermissionsIfNeeded() {
        SharedPreferences preferences = getSharedPreferences(PERMISSION_PREFERENCES, MODE_PRIVATE);
        if (preferences.getBoolean(OPTIONAL_PROMPT_ANSWERED, false)) return;
        List<String> missing = PermissionPolicy.missingOptionalPermissions(
                Build.VERSION.SDK_INT, grantedPermissions());
        if (missing.isEmpty()) {
            preferences.edit().putBoolean(OPTIONAL_PROMPT_ANSWERED, true).apply();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.permission_optional_title)
                .setMessage(R.string.permission_optional_rationale)
                .setPositiveButton(R.string.permission_enable_optional, (dialog, which) -> {
                    preferences.edit().putBoolean(OPTIONAL_PROMPT_ANSWERED, true).apply();
                    launchPermissions(missing, false);
                })
                .setNegativeButton(R.string.permission_continue_without, (dialog, which) -> {
                    preferences.edit().putBoolean(OPTIONAL_PROMPT_ANSWERED, true).apply();
                    Toast.makeText(this, R.string.permission_ready_to_record, Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void launchPermissions(List<String> permissions, boolean required) {
        permissionsInFlight = new ArrayList<>(permissions);
        previouslyRequestedInFlight = previouslyRequestedPermissions();
        requiredRequestInFlight = required;
        // Persist that the system dialog was requested so denial state survives process recreation.
        SharedPreferences.Editor editor = getSharedPreferences(PERMISSION_PREFERENCES, MODE_PRIVATE).edit();
        for (String permission : permissions) editor.putBoolean(REQUESTED_PREFIX + permission, true);
        editor.apply();
        multiplePermissionsLauncher.launch(permissions.toArray(new String[0]));
    }

    private void onPermissionResult(Map<String, Boolean> result) {
        // Activity Result can redeliver after process recreation, when in-memory request state is gone.
        if (permissionsInFlight.isEmpty() && result != null) {
            permissionsInFlight = new ArrayList<>(result.keySet());
            Set<String> requiredPermissions = new HashSet<>(
                    PermissionPolicy.requiredPermissions(Build.VERSION.SDK_INT));
            requiredRequestInFlight = permissionsInFlight.stream().anyMatch(requiredPermissions::contains);
        }
        SharedPreferences.Editor editor = getSharedPreferences(PERMISSION_PREFERENCES, MODE_PRIVATE).edit();
        for (String permission : permissionsInFlight) editor.putBoolean(REQUESTED_PREFIX + permission, true);
        editor.apply();

        boolean requiredFlow = requiredRequestInFlight;
        permissionsInFlight = new ArrayList<>();
        requiredRequestInFlight = false;

        if (requiredFlow) {
            List<String> missing = PermissionPolicy.missingRequiredPermissions(
                    Build.VERSION.SDK_INT, grantedPermissions());
            if (!missing.isEmpty()) {
                Set<String> rationale = permissionsNeedingRationale(missing);
                if (!PermissionPolicy.permanentlyDenied(missing, previouslyRequestedInFlight, rationale).isEmpty()) {
                    showSettingsDialog(true);
                } else {
                    Toast.makeText(this, R.string.permission_required_denied, Toast.LENGTH_LONG).show();
                }
                updatePermissionDependentServices();
                return;
            }
            updatePermissionDependentServices();
            Toast.makeText(this, R.string.permission_ready_to_record, Toast.LENGTH_SHORT).show();
            promptForOptionalPermissionsIfNeeded();
            return;
        }

        List<String> missingOptional = PermissionPolicy.missingOptionalPermissions(
                Build.VERSION.SDK_INT, grantedPermissions());
        if (!missingOptional.isEmpty()) {
            showOptionalResultDialog();
        } else {
            Toast.makeText(this, R.string.permission_ready_to_record, Toast.LENGTH_SHORT).show();
        }
    }

    private void updatePermissionDependentServices() {
        if (sensorFusion == null) return;
        if (PermissionPolicy.missingRequiredPermissions(Build.VERSION.SDK_INT,
                grantedPermissions()).isEmpty()) {
            if (!serverObserverRegistered) {
                sensorFusion.registerForServerUpdate(this);
                serverObserverRegistered = true;
            }
            sensorFusion.resumeListening();
        } else {
            sensorFusion.stopListening();
        }
    }

    private Set<String> grantedPermissions() {
        Set<String> granted = new HashSet<>();
        for (String permission : PermissionPolicy.requiredPermissions(Build.VERSION.SDK_INT)) {
            if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
                granted.add(permission);
            }
        }
        for (String permission : PermissionPolicy.optionalPermissions(Build.VERSION.SDK_INT)) {
            if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
                granted.add(permission);
            }
        }
        return granted;
    }

    private Set<String> previouslyRequestedPermissions() {
        Set<String> requested = new HashSet<>();
        SharedPreferences preferences = getSharedPreferences(PERMISSION_PREFERENCES, MODE_PRIVATE);
        List<String> known = new ArrayList<>(PermissionPolicy.requiredPermissions(Build.VERSION.SDK_INT));
        known.addAll(PermissionPolicy.optionalPermissions(Build.VERSION.SDK_INT));
        for (String permission : known) {
            if (preferences.getBoolean(REQUESTED_PREFIX + permission, false)) requested.add(permission);
        }
        return requested;
    }

    private Set<String> permissionsNeedingRationale(List<String> permissions) {
        Set<String> result = new HashSet<>();
        for (String permission : permissions) {
            if (shouldShowRequestPermissionRationale(permission)) result.add(permission);
        }
        return result;
    }

    private void showSettingsDialog(boolean required) {
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this)
                .setTitle(required ? R.string.permission_required_settings_title
                        : R.string.permission_optional_settings_title)
                .setMessage(required ? R.string.permission_required_settings_message
                        : R.string.permission_optional_settings_message)
                .setPositiveButton(R.string.permission_open_settings,
                        (dialog, which) -> openAppSettings())
                .setNegativeButton(required ? R.string.permission_not_now
                                : R.string.permission_continue_without, null)
                .show();
    }

    private void showOptionalResultDialog() {
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.permission_optional_settings_title)
                .setMessage(R.string.permission_optional_settings_message)
                .setPositiveButton(R.string.permission_open_settings,
                        (dialog, which) -> openAppSettings())
                .setNegativeButton(R.string.permission_continue_without, (dialog, which) ->
                        Toast.makeText(this, R.string.permission_ready_to_record, Toast.LENGTH_SHORT).show())
                .show();
    }

    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null));
        startActivity(intent);
    }

    @Override
    public void openRecordingPermissionSettings() {
        openAppSettings();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (sensorFusion != null) sensorFusion.stopListening();
    }

    @Override
    protected void onDestroy() {
        httpResponseHandler.removeCallbacksAndMessages(null);
        if (sensorFusion != null && serverObserverRegistered) {
            sensorFusion.unregisterForServerUpdate(this);
            serverObserverRegistered = false;
        }
        if (sensorFusion != null) sensorFusion.stopListening();
        super.onDestroy();
    }

    @Override
    public void update(Object[] objList) {
        if (objList == null || objList.length == 0 || !(objList[0] instanceof Boolean)) return;
        String message = (Boolean) objList[0]
                ? "Trajectory uploaded" : "Failed to complete trajectory upload";
        if (isDestroyed()) return;
        httpResponseHandler.post(() -> {
            if (!isDestroyed()) Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        });
    }
}
