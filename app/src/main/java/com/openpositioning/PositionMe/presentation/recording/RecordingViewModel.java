package com.openpositioning.PositionMe.presentation.recording;

import android.os.Handler;
import android.os.Looper;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.application.recording.RecordingOperationSnapshot;

/** Activity-scoped owner shared by the main recording and nested calibration controls. */
public final class RecordingViewModel extends ViewModel {
    private final MutableLiveData<RecordingUiState> state = new MutableLiveData<>(RecordingUiState.initial());
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final RecordingController controller;
    private final AutoCloseable subscription;

    public RecordingViewModel() {
        this(new SensorFusionRecordingCommands(SensorFusion.getInstance()));
    }

    public RecordingViewModel(RecordingCommands commands) {
        controller = new RecordingController(commands, value -> mainHandler.post(() -> state.setValue(value)));
        subscription = commands.observe(new RecordingCommands.Listener() {
            @Override public void onRecordingStarted() { controller.onRecordingStarted(); }
            @Override public void onRecordingStopped() { controller.onRecordingStopped(); }
            @Override public void onSessionCompleted(com.openpositioning.PositionMe.domain.recording.SessionStatus status) {
                controller.onSessionCompleted(status);
            }
            @Override public void onOperationSnapshot(RecordingOperationSnapshot snapshot) {
                controller.onOperationSnapshot(snapshot);
            }
        });
        state.setValue(controller.getState());
    }

    public LiveData<RecordingUiState> getState() { return state; }
    public void start() { controller.requestStart(); }
    public void stop() { controller.requestStop(); }
    public void permissionDenied() { controller.permissionDenied(); }
    public void permissionGranted() { controller.permissionGranted(); }
    public void consumeEffect(long id) { controller.consumeEffect(id); }

    @Override protected void onCleared() {
        try { subscription.close(); } catch (Exception ignored) { }
    }
}
