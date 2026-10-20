package com.openpositioning.PositionMe.sensors;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.util.Log;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.application.PositioningEngineComposition;
import com.openpositioning.PositionMe.application.recording.RecordingSessionFactory;
import com.openpositioning.PositionMe.application.recording.SnapshotSource;
import com.openpositioning.PositionMe.domain.recording.SessionState;
import com.openpositioning.PositionMe.data.remote.FloorplanApiClient;
import com.openpositioning.PositionMe.data.remote.WiFiPositioning;
import com.openpositioning.PositionMe.domain.GeoPosition;
import com.openpositioning.PositionMe.domain.GnssObservation;
import com.openpositioning.PositionMe.domain.PositionEstimate;
import com.openpositioning.PositionMe.domain.PositioningEngine;
import com.openpositioning.PositionMe.domain.StepObservation;
import com.openpositioning.PositionMe.domain.WifiObservation;
import org.json.JSONObject;
import com.openpositioning.PositionMe.application.recording.RecordingSessionCollector;
import com.openpositioning.PositionMe.service.SensorCollectionService;
import com.openpositioning.PositionMe.utils.PdrProcessing;
import com.openpositioning.PositionMe.utils.TrajectoryValidator;
import com.openpositioning.PositionMe.data.remote.ServerCommunications;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.io.IOException;

/**
 * The SensorFusion class is the main data gathering and processing class of the application.
 *
 * <p>It follows the singleton design pattern to ensure that every fragment and process has access
 * to the same data and sensor instances. Internally it delegates to specialised modules:</p>
 * <ul>
 *   <li>{@link SensorState} &ndash; shared sensor data holder</li>
 *   <li>{@link SensorEventHandler} &ndash; sensor event dispatch (switch logic)</li>
 *   <li>{@link TrajectoryRecorder} &ndash; recording lifecycle &amp; protobuf construction</li>
 *   <li>{@link WifiPositionManager} &ndash; WiFi scan processing &amp; positioning</li>
 * </ul>
 *
 * <p>The public API is unchanged &ndash; all external callers continue to use
 * {@code SensorFusion.getInstance().method()}.</p>
 */
public class SensorFusion implements SensorEventListener {

    //region Static variables
    private static final SensorFusion sensorFusion = new SensorFusion();
    //endregion

    //region Instance variables
    private Context appContext;
    private boolean contextInitialized;

    // Shared sensor state
    private final SensorState state = new SensorState();
    private final CopyOnWriteArrayList<SensorFusionListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong lastSensorSnapshotEventMillis = new AtomicLong();
    private volatile BiFunction<GeoPosition, Double, PositioningEngine> positioningEngineFactory =
            PositioningEngineComposition.defaultFactory();
    private volatile PositioningEngine positioningEngine;

    // Internal modules
    private SensorEventHandler eventHandler;
    private TrajectoryRecorder recorder;
    private WifiPositionManager wifiPositionManager;

    // Movement sensor instances (lifecycle managed here)
    private MovementSensor accelerometerSensor;
    private MovementSensor barometerSensor;
    private MovementSensor gyroscopeSensor;
    private MovementSensor lightSensor;
    private MovementSensor proximitySensor;
    private MovementSensor magnetometerSensor;
    private MovementSensor stepDetectionSensor;
    private MovementSensor rotationSensor;
    private MovementSensor gravitySensor;
    private MovementSensor linearAccelerationSensor;

    // Non-sensor data sources
    private WifiDataProcessor wifiProcessor;
    private BleDataProcessor bleProcessor;
    private GNSSDataProcessor gnssProcessor;
    private RttManager rttManager;
    private BleRttManager bleRttManager;
    private final LocationListener locationListener;

    // PDR processor
    private PdrProcessing pdrProcessing;

    // Sensor registration latency setting
    long maxReportLatencyNs = 0;

    // Floorplan API cache (latest result from start-location step)
    private final Map<String, FloorplanApiClient.BuildingInfo> floorplanBuildingCache =
            new HashMap<>();

    private float gnssAccuracy = Float.MAX_VALUE;
    private volatile List<Wifi> latestWifiList = new ArrayList<>();
    private volatile RecordingSessionCollector recordingSessionCollector;
    private final Object pdrPathLock = new Object();
    private final List<float[]> recordedPdrPath = new ArrayList<>();
    //endregion

    //region Initialisation

    /**
     * Private constructor for implementing singleton design pattern.
     */
    private SensorFusion() {
        this.locationListener = new MyLocationListener();
    }

    /**
     * Static function to access singleton instance of SensorFusion.
     *
     * @return singleton instance of SensorFusion class.
     */
    public static SensorFusion getInstance() {
        return sensorFusion;
    }

    /**
     * Initialisation function for the SensorFusion instance.
     *
     * <p>Initialises all movement sensor instances, creates internal modules, and prepares
     * the system for data collection.</p>
     *
     * @param context application context for permissions and device access.
     *
     * @see MovementSensor handling all SensorManager based data collection devices.
     * @see ServerCommunications handling communication with the server.
     * @see GNSSDataProcessor for location data processing.
     * @see WifiDataProcessor for network data processing.
     */
    public synchronized void setContext(Context context) {
        // Activity recreation must not replace an active recorder or its sensor observers.
        if (contextInitialized) return;
        this.appContext = context.getApplicationContext();

        // Initialise movement sensors
        this.accelerometerSensor = new MovementSensor(context, Sensor.TYPE_ACCELEROMETER);
        this.barometerSensor = new MovementSensor(context, Sensor.TYPE_PRESSURE);
        this.gyroscopeSensor = new MovementSensor(context, Sensor.TYPE_GYROSCOPE);
        this.lightSensor = new MovementSensor(context, Sensor.TYPE_LIGHT);
        this.proximitySensor = new MovementSensor(context, Sensor.TYPE_PROXIMITY);
        this.magnetometerSensor = new MovementSensor(context, Sensor.TYPE_MAGNETIC_FIELD);
        this.stepDetectionSensor = new MovementSensor(context, Sensor.TYPE_STEP_DETECTOR);
        this.rotationSensor = new MovementSensor(context, Sensor.TYPE_ROTATION_VECTOR);
        this.gravitySensor = new MovementSensor(context, Sensor.TYPE_GRAVITY);
        this.linearAccelerationSensor = new MovementSensor(context, Sensor.TYPE_LINEAR_ACCELERATION);

        // Initialise non-sensor data sources
        this.gnssProcessor = new GNSSDataProcessor(context, locationListener);
        ServerCommunications serverCommunications = new ServerCommunications(context);

        // Initialise utilities
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(context);
        this.pdrProcessing = new PdrProcessing(context);
        WiFiPositioning wiFiPositioning = new WiFiPositioning(context);
        this.wiFiPositioning = wiFiPositioning;
        this.positioningEngine = positioningEngineFactory.apply(selectedStartPosition(), (double) state.orientation[0]);

        // Create internal modules
        this.recorder = new TrajectoryRecorder(appContext, state, serverCommunications, settings);
        this.recorder.setSensorReferences(
                accelerometerSensor, gyroscopeSensor, magnetometerSensor,
                barometerSensor, lightSensor, proximitySensor, rotationSensor);

        this.wifiPositionManager = new WifiPositionManager(wiFiPositioning, recorder);
        this.wifiPositionManager.setPositionListener(this::onWifiPosition);

        long bootTime = SystemClock.elapsedRealtime();
        this.eventHandler = new SensorEventHandler(
                state, pdrProcessing, recorder, bootTime);
        this.eventHandler.setStepListener(this::onPdrStep);

        // Register WiFi observer on WifiPositionManager (not on SensorFusion)
        this.wifiProcessor = new WifiDataProcessor(context);
        wifiProcessor.registerObserver(wifiPositionManager);
        wifiProcessor.registerObserver(wifiObserver -> {
            List<Wifi> copy = new ArrayList<>();
            for (Object item : wifiObserver) if (item instanceof Wifi) copy.add(((Wifi) item).copy());
            latestWifiList = copy;
        });

        // Initialise BLE scanner and register observer for trajectory recording
        this.bleProcessor = new BleDataProcessor(context);
        bleProcessor.registerObserver(new Observer() {
            @Override
            public void update(Object[] objList) {
                List<BleDevice> bleList = Stream.of(objList)
                        .map(o -> (BleDevice) o).collect(Collectors.toList());
                recorder.addBleFingerprint(bleList);
            }
        });

        // Initialise WiFi RTT manager and register as WiFi scan observer
        this.rttManager = new RttManager(appContext, recorder, wifiProcessor);
        wifiProcessor.registerObserver(rttManager);

        // Initialise BLE RTT estimator and register on BLE scan updates
        this.bleRttManager = new BleRttManager(recorder);
        bleProcessor.registerObserver(bleRttManager);

        if (!rttManager.isRttSupported()) {
            new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(appContext,
                            "WiFi RTT is not supported on this device",
                            Toast.LENGTH_LONG).show());
        }
        createRecordingSessionCollector();
        contextInitialized = true;
    }

    //endregion

    //region SensorEventListener

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to {@link SensorEventHandler#handleSensorEvent(SensorEvent)}.</p>
     */
    @Override
    public void onSensorChanged(SensorEvent sensorEvent) {
        if (eventHandler != null && sensorEvent != null) {
            long now = SystemClock.elapsedRealtime();
            eventHandler.handleSensorEvent(sensorEvent);
            publishSensorSnapshot(now);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {}

    //endregion

    //region Start/Stop listening

    /**
     * Registers all device listeners and enables updates with the specified sampling rate.
     *
     * <p>Should be called when the application resumes.</p>
     */
    public void resumeListening() {
        if (eventHandler == null || recorder == null) {
            Log.w("SensorFusion", "Ignoring resumeListening before sensor context initialization");
            return;
        }
        registerSensor(accelerometerSensor, 10000, (int) maxReportLatencyNs);
        registerSensor(linearAccelerationSensor, 10000, (int) maxReportLatencyNs);
        registerSensor(gravitySensor, 10000, (int) maxReportLatencyNs);
        registerSensor(barometerSensor, (int) 1e6, 0);
        registerSensor(gyroscopeSensor, 10000, (int) maxReportLatencyNs);
        registerSensor(lightSensor, (int) 1e6, 0);
        registerSensor(proximitySensor, (int) 1e6, 0);
        registerSensor(magnetometerSensor, 10000, (int) maxReportLatencyNs);
        registerSensor(stepDetectionSensor, SensorManager.SENSOR_DELAY_NORMAL, 0);
        registerSensor(rotationSensor, (int) 1e6, 0);
        // Foreground service owns WiFi/BLE scanning during recording.
        if (!recorder.isRecording()) {
            startWirelessCollectors();
        }
        if (gnssProcessor != null) gnssProcessor.startLocationUpdates();
    }

    private void registerSensor(MovementSensor movementSensor, int samplingPeriodUs,
                                int maxReportLatencyUs) {
        if (movementSensor == null || movementSensor.sensorManager == null
                || movementSensor.sensor == null) {
            return; // Hardware capability is optional; missing sensors produce no readings.
        }
        movementSensor.sensorManager.registerListener(this, movementSensor.sensor,
                samplingPeriodUs, maxReportLatencyUs);
    }

    /**
     * Un-registers all device listeners and pauses data collection.
     *
     * <p>Should be called when the application pauses.</p>
     */
    public void stopListening() {
        if (recorder != null && recorder.isRecording()) return;
        unregisterSensor(accelerometerSensor);
        unregisterSensor(barometerSensor);
        unregisterSensor(gyroscopeSensor);
        unregisterSensor(lightSensor);
        unregisterSensor(proximitySensor);
        unregisterSensor(magnetometerSensor);
        unregisterSensor(stepDetectionSensor);
        unregisterSensor(rotationSensor);
        unregisterSensor(linearAccelerationSensor);
        unregisterSensor(gravitySensor);
        stopWirelessCollectors();
        if (gnssProcessor != null) gnssProcessor.stopUpdating();
    }

    private void unregisterSensor(MovementSensor movementSensor) {
        if (movementSensor != null && movementSensor.sensorManager != null) {
            movementSensor.sensorManager.unregisterListener(this);
        }
    }

    /**
     * Called by {@link SensorCollectionService} when foreground collection starts.
     * Moves WiFi/BLE scanning responsibility into the service lifecycle while recording.
     */
    public void onCollectionServiceStarted() {
        if (recorder == null || !recorder.isRecording()) {
            Log.w("SensorFusion", "Ignoring collection service start without an active recording");
            stopWirelessCollectors();
            return;
        }
        startWirelessCollectors();
    }

    /**
     * Called by {@link SensorCollectionService} when foreground collection stops.
     * Stops WiFi/BLE scans that were started for recording continuity.
     */
    public void onCollectionServiceStopped() {
        stopSessionCollector();
        stopWirelessCollectors();
        // Background collection has ended without an active foreground owner. Stop the
        // in-memory recording so the facade cannot claim the session is still collecting.
        if (recorder != null && recorder.isRecording()) {
            recorder.stopRecording();
            publish(SensorFusionEvent.recordingLifecycle(SystemClock.elapsedRealtime(), false));
        }
    }

    public boolean isRecording() {
        return recorder != null && recorder.isRecording();
    }

    private void startWirelessCollectors() {
        if (wifiProcessor != null) {
            wifiProcessor.startListening();
        }
        if (bleProcessor != null) {
            bleProcessor.startListening();
        }
    }

    private void stopWirelessCollectors() {
        try {
            if (wifiProcessor != null) {
                wifiProcessor.stopListening();
            }
        } catch (Exception e) {
            System.err.println("WiFi stop failed");
        }
        try {
            if (bleProcessor != null) {
                bleProcessor.stopListening();
            }
        } catch (Exception e) {
            System.err.println("BLE stop failed");
        }
    }

    private void createRecordingSessionCollector() {
        try {
            recordingSessionCollector = RecordingSessionFactory.create(appContext,
                    new SnapshotSource() {
                        @Override
                        public JSONObject captureSnapshot() {
                            return getAllSensorData();
                        }

                        @Override
                        public void requestWifiUpdate() {
                            if (wifiPositionManager != null) {
                                wifiPositionManager.requestCurrentPosition();
                            }
                        }
                    });
        } catch (IOException exception) {
            Log.e("SensorFusion", "Could not create sensor recording collector", exception);
        }
    }

    private synchronized void startSessionCollector() {
        RecordingSessionCollector collector = recordingSessionCollector;
        if (collector == null || (collector.getStatus().getState() != SessionState.NEW
                && collector.getStatus().getState() != SessionState.RUNNING)) {
            createRecordingSessionCollector();
            collector = recordingSessionCollector;
        }
        if (collector != null && collector.getStatus().getState() == SessionState.NEW) {
            collector.start();
        }
    }

    private void stopSessionCollector() {
        RecordingSessionCollector collector = recordingSessionCollector;
        if (collector != null) collector.stop();
    }

    //endregion

    //region Recording lifecycle (delegated to TrajectoryRecorder)

    /**
     * Enables saving sensor values to the trajectory object.
     * Also starts the foreground service to keep data collection alive in the background.
     *
     * @see TrajectoryRecorder#startRecording(PdrProcessing)
     * @see SensorCollectionService
     */
    public void startRecording() {
        if (recorder == null || eventHandler == null || pdrProcessing == null || appContext == null) {
            Log.e("SensorFusion", "Cannot start recording before sensor context initialization");
            showRecordingFailure();
            return;
        }
        if (recorder.isRecording()) return;
        rebuildPositioningEngine();
        synchronized (pdrPathLock) { recordedPdrPath.clear(); }
        if (!recorder.startRecording(pdrProcessing)) {
            showRecordingFailure();
            return;
        }

        eventHandler.resetBootTime(recorder.getBootTime());
        publish(SensorFusionEvent.recordingLifecycle(SystemClock.elapsedRealtime(), true));
        stopWirelessCollectors();
        try {
            SensorCollectionService.start(appContext);
            startSessionCollector();
        } catch (RuntimeException exception) {
            Log.e("SensorFusion", "Unable to start foreground collection service", exception);
            publish(SensorFusionEvent.recordingLifecycle(SystemClock.elapsedRealtime(), false));
            recorder.stopRecording();
            stopWirelessCollectors();
            showRecordingFailure();
        }
    }

    private void showRecordingFailure() {
        if (appContext != null) {
            new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(appContext,
                            "Recording could not start. Please retry while the app is open.",
                            Toast.LENGTH_LONG).show());
        }
    }

    /**
     * Disables saving sensor values to the trajectory object.
     * Also stops the foreground service since background collection is no longer needed.
     *
     * @see TrajectoryRecorder#stopRecording()
     * @see SensorCollectionService
     */
    public void stopRecording() {
        boolean wasRecording = recorder != null && recorder.isRecording();
        stopSessionCollector();
        if (recorder != null) recorder.stopRecording();
        if (appContext != null) SensorCollectionService.stop(appContext);
        stopWirelessCollectors();
        if (wasRecording) publish(SensorFusionEvent.recordingLifecycle(
                SystemClock.elapsedRealtime(), false));
    }

    /**
     * Validates the current trajectory against quality thresholds before upload.
     *
     * @return validation result with errors and warnings
     * @see TrajectoryValidator
     */
    public TrajectoryValidator.ValidationResult validateTrajectory() {
        return recorder.validateTrajectory();
    }

    /**
     * Send the trajectory object to servers.
     *
     * @see TrajectoryRecorder#sendTrajectoryToCloud()
     */
    public void sendTrajectoryToCloud() {
        recorder.sendTrajectoryToCloud();
    }

    /**
     * Sets the trajectory name/ID for the current recording session.
     *
     * @param id trajectory name entered by the user
     */
    public void setTrajectoryId(String id) {
        recorder.setTrajectoryId(id);
    }

    /**
     * Gets the trajectory name/ID for the current recording session.
     *
     * @return trajectory name string, or null if not set
     */
    public String getTrajectoryId() {
        return recorder.getTrajectoryId();
    }

    /**
     * Sets the selected building identifier for the current recording session.
     * Used to determine the campaign name when uploading the trajectory.
     *
     * @param buildingId building name from the floorplan API (e.g. "nucleus_building")
     */
    public void setSelectedBuildingId(String buildingId) {
        recorder.setSelectedBuildingId(buildingId);
    }

    /**
     * Gets the selected building identifier for the current recording session.
     *
     * @return building name string, or null if no building was selected
     */
    public String getSelectedBuildingId() {
        return recorder.getSelectedBuildingId();
    }

    /**
     * Caches floorplan API building payloads for use in later fragments.
     *
     * @param buildings buildings returned by floorplan API
     */
    public void setFloorplanBuildings(List<FloorplanApiClient.BuildingInfo> buildings) {
        floorplanBuildingCache.clear();
        if (buildings == null) return;

        for (FloorplanApiClient.BuildingInfo building : buildings) {
            if (building == null || building.getName() == null || building.getName().isEmpty()) {
                continue;
            }
            floorplanBuildingCache.put(building.getName(), building);
        }
    }

    /**
     * Returns a cached floorplan entry by building id.
     *
     * @param buildingId building name from floorplan API
     * @return cached building info, or null if not present
     */
    public FloorplanApiClient.BuildingInfo getFloorplanBuilding(String buildingId) {
        if (buildingId == null || buildingId.isEmpty()) {
            return null;
        }
        return floorplanBuildingCache.get(buildingId);
    }

    /**
     * Returns all cached floorplan entries.
     *
     * @return list copy of cached building info objects
     */
    public List<FloorplanApiClient.BuildingInfo> getFloorplanBuildings() {
        return new ArrayList<>(floorplanBuildingCache.values());
    }

    /**
     * Writes the initial position and heading into the trajectory protobuf.
     * Should be called after startRecording() and setStartGNSSLatitude().
     */
    public void writeInitialMetadata() {
        recorder.writeInitialMetadata();
    }

    /**
     * Adds a test point (user ground truth marker) to the trajectory.
     */
    public void addTestPointToProto(long pressTimestampMs, double lat, double lng) {
        recorder.addTestPoint(pressTimestampMs, lat, lng);
    }

    //endregion

    //region Getters/Setters

    /**
     * Getter function for core location data.
     *
     * @param start set true to get the initial location
     * @return longitude and latitude data in a float[2].
     */
    public float[] getGNSSLatitude(boolean start) {
        synchronized (state) {
            if (!start) {
                return new float[]{state.latitude, state.longitude};
            }
            return state.startLocation.clone();
        }
    }

    /**
     * Setter function for core location data.
     *
     * @param startPosition contains the initial location set by the user
     */
    public void setStartGNSSLatitude(float[] startPosition) {
        if (startPosition == null || startPosition.length < 2) {
            throw new IllegalArgumentException("Start position must contain latitude and longitude");
        }
        synchronized (state) {
            state.startLocation[0] = startPosition[0];
            state.startLocation[1] = startPosition[1];
        }
        if (!isRecording()) rebuildPositioningEngine();
    }

    /**
     * Getter function for average step length.
     *
     * @return average step length of total PDR.
     */
    public float passAverageStepLength() {
        return pdrProcessing.getAverageStepLength();
    }

    /**
     * Getter function for device orientation.
     *
     * @return orientation of device in radians.
     */
    public float passOrientation() {
        return state.orientation[0];
    }

    /**
     * Return most recent sensor readings.
     *
     * @return Map of {@link SensorTypes} to float array of most recent values.
     */
    public Map<SensorTypes, float[]> getSensorValueMap() {
        synchronized (state) {
            Map<SensorTypes, float[]> sensorValueMap = new HashMap<>();
            sensorValueMap.put(SensorTypes.ACCELEROMETER, state.acceleration.clone());
            sensorValueMap.put(SensorTypes.GRAVITY, state.gravity.clone());
            sensorValueMap.put(SensorTypes.MAGNETICFIELD, state.magneticField.clone());
            sensorValueMap.put(SensorTypes.GYRO, state.angularVelocity.clone());
            sensorValueMap.put(SensorTypes.LIGHT, new float[]{state.light});
            sensorValueMap.put(SensorTypes.PRESSURE, new float[]{state.pressure});
            sensorValueMap.put(SensorTypes.PROXIMITY, new float[]{state.proximity});
            sensorValueMap.put(SensorTypes.GNSSLATLONG, new float[]{state.latitude, state.longitude});
            float[] movement = pdrProcessing.getPDRMovement();
            sensorValueMap.put(SensorTypes.PDR, movement == null ? null : movement.clone());
            return sensorValueMap;
        }
    }

    /**
     * Return the most recent list of WiFi names and levels.
     *
     * @return list of Wifi objects.
     */
    public List<Wifi> getWifiList() {
        return wifiPositionManager == null ? new ArrayList<>() : wifiPositionManager.getWifiList();
    }

    /**
     * Get information about all the sensors registered in SensorFusion.
     *
     * @return List of SensorInfo objects containing name, resolution, power, etc.
     */
    public List<SensorInfo> getSensorInfos() {
        List<SensorInfo> sensorInfoList = new ArrayList<>();
        addSensorInfo(sensorInfoList, accelerometerSensor);
        addSensorInfo(sensorInfoList, barometerSensor);
        addSensorInfo(sensorInfoList, gyroscopeSensor);
        addSensorInfo(sensorInfoList, lightSensor);
        addSensorInfo(sensorInfoList, proximitySensor);
        addSensorInfo(sensorInfoList, magnetometerSensor);
        return sensorInfoList;
    }

    private void addSensorInfo(List<SensorInfo> target, MovementSensor movementSensor) {
        if (movementSensor != null && movementSensor.sensorInfo != null) {
            target.add(movementSensor.sensorInfo);
        }
    }

    /**
     * Registers the caller observer to receive updates from the server instance.
     *
     * @param observer Instance implementing {@link Observer} who wants to be notified of
     *                 events relating to sending and receiving trajectories.
     */
    public void registerForServerUpdate(Observer observer) {
        recorder.getServerCommunications().registerObserver(observer);
    }

    /**
     * Get the estimated elevation value in meters calculated by the PDR class.
     *
     * @return float of the estimated elevation in meters.
     */
    public float getElevation() {
        return state.elevation;
    }

    /**
     * Get an estimate whether the user is currently taking an elevator.
     *
     * @return true if the PDR estimates the user is in an elevator, false otherwise.
     */
    public boolean getElevator() {
        return state.elevator;
    }

    /**
     * Estimates position of the phone based on proximity and light sensors.
     *
     * @return int 1 if the phone is by the ear, int 0 otherwise.
     */
    public int getHoldMode() {
        int proximityThreshold = 1, lightThreshold = 100;
        if (state.proximity < proximityThreshold && state.light > lightThreshold) {
            return 1;
        } else {
            return 0;
        }
    }

    /**
     * Returns the user position obtained using WiFi positioning.
     *
     * @return {@link LatLng} corresponding to user's position.
     */
    public LatLng getLatLngWifiPositioning() {
        return wifiPositionManager == null ? null : wifiPositionManager.getLatLngWifiPositioning();
    }

    /**
     * Returns the current floor the user is on, obtained using WiFi positioning.
     *
     * @return current floor number.
     */
    public int getWifiFloor() {
        return wifiPositionManager == null ? 0 : wifiPositionManager.getWifiFloor();
    }

    /**
     * Utility function to log the event frequency of each sensor.
     */
    public void logSensorFrequencies() {
        if (eventHandler == null) return;
        eventHandler.logSensorFrequencies();
    }

    //endregion

    private void onWifiPosition(LatLng position, int floor) {
        if (position == null) return;
        long now = SystemClock.elapsedRealtime();
        double avgRssi = latestWifiList.stream().mapToInt(Wifi::getLevel).average().orElse(-85.0);
        PositionEstimate estimate = positioningEngine.onWifi(new WifiObservation(now,
                new GeoPosition(position.latitude, position.longitude, currentAltitude()),
                avgRssi, floor));
        publishPositionEstimate(estimate);
        publish(SensorFusionEvent.wifiFloor(now, floor));
    }

    private synchronized void onGnssPosition(Location location) {
        if (location == null) return;
        PositionEstimate estimate = positioningEngine.onGnss(new GnssObservation(
                SystemClock.elapsedRealtime(), new GeoPosition(location.getLatitude(),
                location.getLongitude(), location.getAltitude()),
                location.hasAccuracy() ? location.getAccuracy() : 0.0));
        publishPositionEstimate(estimate);
    }

    private synchronized void onPdrStep(float[] pdrPosition, float stepLength,
                                         float headingRadians, long elapsedRealtimeMillis,
                                         long stepCounter) {
        if (isRecording() && pdrPosition != null && pdrPosition.length >= 2) {
            synchronized (pdrPathLock) { recordedPdrPath.add(pdrPosition.clone()); }
        }
        publish(SensorFusionEvent.pdrStep(elapsedRealtimeMillis, pdrPosition, stepLength,
                headingRadians));
        PositionEstimate estimate = positioningEngine.onStep(new StepObservation(
                elapsedRealtimeMillis, Math.max(0.0, stepLength), headingRadians, stepCounter));
        publishPositionEstimate(estimate);
    }

    public WiFiPositioning getWiFiPositioning() {
        return wifiPositionManager == null ? null : wiFiPositioning;
    }

    private WiFiPositioning wiFiPositioning;

    public JSONObject getAllSensorData() {
        synchronized (state) {
            return SensorFusionUtils.getAllSensorData(this, wiFiPositioning);
        }
    }

    /** Returns a coherent immutable snapshot for UI and application-layer consumers. */
    public SensorSnapshot getSensorSnapshot() {
        synchronized (state) {
            return new SensorSnapshot(SystemClock.elapsedRealtime(), state);
        }
    }

    public float getGnssAccuracy() { return gnssAccuracy; }

    /** Subscribes to immutable sensor-fusion events. Close the returned handle to unsubscribe. */
    public AutoCloseable subscribe(SensorFusionListener listener) {
        if (listener == null) throw new IllegalArgumentException("listener must not be null");
        listeners.addIfAbsent(listener);
        return () -> listeners.remove(listener);
    }

    /** Installs the domain-engine factory before the next positioning session starts. */
    public synchronized void setPositioningEngineFactory(
            BiFunction<GeoPosition, Double, PositioningEngine> factory) {
        if (factory == null) throw new IllegalArgumentException("factory must not be null");
        if (recorder != null && recorder.isRecording())
            throw new IllegalStateException("Cannot change positioning engine during recording");
        positioningEngineFactory = factory;
        if (contextInitialized) rebuildPositioningEngine();
    }

    public PositionEstimate getPositionEstimate() {
        return positioningEngine == null ? null : positioningEngine.getEstimate();
    }

    public synchronized void setRecordingSessionCollector(RecordingSessionCollector collector) {
        if (collector == null) throw new IllegalArgumentException("collector must not be null");
        if (recorder != null && recorder.isRecording()) {
            throw new IllegalStateException("Cannot replace session collector during recording");
        }
        RecordingSessionCollector previous = recordingSessionCollector;
        if (previous != null && previous != collector) previous.stop();
        this.recordingSessionCollector = collector;
    }

    public RecordingSessionCollector getRecordingSessionCollector() {
        return recordingSessionCollector;
    }

    /** Includes asynchronous JSON persistence failure while protobuf trajectory capture continues. */
    public SensorFusionRecordingStatus getRecordingStatus() {
        RecordingSessionCollector collector = recordingSessionCollector;
        return new SensorFusionRecordingStatus(isRecording(),
                collector == null ? null : collector.getStatus());
    }

    /** Returns the complete session PDR path, independent of current UI subscriptions. */
    public List<float[]> getRecordedPdrPathSnapshot() {
        synchronized (pdrPathLock) {
            List<float[]> copy = new ArrayList<>(recordedPdrPath.size());
            for (float[] point : recordedPdrPath) copy.add(point.clone());
            return copy;
        }
    }

    private void publishPositionEstimate(PositionEstimate estimate) {
        if (estimate != null) {
            publish(SensorFusionEvent.positionEstimate(estimate.getElapsedRealtimeMillis(), estimate));
        }
    }

    private void publish(SensorFusionEvent event) {
        for (SensorFusionListener listener : listeners) {
            try {
                listener.onSensorFusionEvent(event);
            } catch (RuntimeException exception) {
                Log.e("SensorFusion", "Sensor event subscriber failed", exception);
            }
        }
    }

    /** Publishes UI snapshots at most 5 Hz while raw recorder sampling remains independent. */
    private void publishSensorSnapshot(long elapsedRealtimeMillis) {
        while (true) {
            long previous = lastSensorSnapshotEventMillis.get();
            if (elapsedRealtimeMillis - previous < 200L) return;
            if (lastSensorSnapshotEventMillis.compareAndSet(previous, elapsedRealtimeMillis)) break;
        }
        SensorSnapshot snapshot = new SensorSnapshot(elapsedRealtimeMillis, state);
        publish(SensorFusionEvent.sensorState(elapsedRealtimeMillis, snapshot));
    }

    private synchronized void rebuildPositioningEngine() {
        if (positioningEngineFactory == null) return;
        double initialHeading;
        synchronized (state) { initialHeading = state.orientation[0]; }
        positioningEngine = positioningEngineFactory.apply(selectedStartPosition(), initialHeading);
    }

    private GeoPosition selectedStartPosition() {
        float latitude;
        float longitude;
        synchronized (state) {
            latitude = state.startLocation[0];
            longitude = state.startLocation[1];
        }
        if (latitude == 0.0f && longitude == 0.0f) return null;
        return new GeoPosition(latitude, longitude, 0.0);
    }

    private double currentAltitude() {
        return state.altitude;
    }

    SensorState sensorState() { return state; }
    List<Wifi> latestWifiList() {
        List<Wifi> copy = new ArrayList<>();
        for (Wifi wifi : latestWifiList) copy.add(wifi.copy());
        return copy;
    }

    //region Location listener

    /**
     * Location listener class to receive updates from the location manager.
     * Writes position data to {@link SensorState} and GNSS readings to
     * {@link TrajectoryRecorder}.
     */
    class MyLocationListener implements LocationListener {
        @Override
        public void onLocationChanged(@NonNull Location location) {
            long now = SystemClock.elapsedRealtime();
            synchronized (state) {
                state.latitude = (float) location.getLatitude();
                state.longitude = (float) location.getLongitude();
                state.altitude = location.getAltitude();
            }
            publishSensorSnapshot(now);
            gnssAccuracy = location.hasAccuracy() ? location.getAccuracy() : Float.MAX_VALUE;
            recorder.addGnssData(location);
            onGnssPosition(location);
        }
    }

    //endregion
}
