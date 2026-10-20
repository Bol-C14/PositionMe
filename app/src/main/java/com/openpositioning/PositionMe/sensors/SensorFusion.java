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
import com.openpositioning.PositionMe.data.remote.FloorplanApiClient;
import com.openpositioning.PositionMe.data.remote.WiFiPositioning;
import com.openpositioning.PositionMe.domain.EKF;
import com.openpositioning.PositionMe.domain.ParticleFilter;
import com.openpositioning.PositionMe.utils.CoordinateTransform;
import org.json.JSONObject;
import java.util.function.Consumer;
import com.openpositioning.PositionMe.presentation.activity.MainActivity;
import com.openpositioning.PositionMe.service.SensorCollectionService;
import com.openpositioning.PositionMe.utils.PathView;
import com.openpositioning.PositionMe.utils.PdrProcessing;
import com.openpositioning.PositionMe.utils.TrajectoryValidator;
import com.openpositioning.PositionMe.data.remote.ServerCommunications;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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

    // PDR and path
    private PdrProcessing pdrProcessing;
    private PathView pathView;

    // Sensor registration latency setting
    long maxReportLatencyNs = 0;

    // Floorplan API cache (latest result from start-location step)
    private final Map<String, FloorplanApiClient.BuildingInfo> floorplanBuildingCache =
            new HashMap<>();

    // Fork positioning fusion state. The EKF/PF operate in a local ENU frame whose origin is
    // the first valid GNSS/Wi-Fi position (or the selected start point when supplied).
    private EKF extendedKalmanFilter;
    private ParticleFilter particleFilter;
    private double refLat, refLon, refAlt;
    private boolean fusionInitialized;
    private double previousHeading;
    private long lastFusionMeasurementMs;
    private float gnssAccuracy = Float.MAX_VALUE;
    private volatile List<Wifi> latestWifiList = new ArrayList<>();
    private volatile com.openpositioning.PositionMe.presentation.fragment.TrajectoryMapFragment trajectoryMapFragment;
    private volatile Consumer<Integer> wifiFloorChangedListener;
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
        this.pathView = new PathView(context, null);
        WiFiPositioning wiFiPositioning = new WiFiPositioning(context);
        this.wiFiPositioning = wiFiPositioning;

        // Create internal modules
        this.recorder = new TrajectoryRecorder(appContext, state, serverCommunications, settings);
        this.recorder.setSensorReferences(
                accelerometerSensor, gyroscopeSensor, magnetometerSensor,
                barometerSensor, lightSensor, proximitySensor, rotationSensor);

        this.wifiPositionManager = new WifiPositionManager(wiFiPositioning, recorder);
        this.wifiPositionManager.setPositionListener(this::onWifiPosition);

        long bootTime = SystemClock.uptimeMillis();
        this.eventHandler = new SensorEventHandler(
                state, pdrProcessing, pathView, recorder, bootTime);
        this.eventHandler.setStepListener(this::onPdrStep);

        // Register WiFi observer on WifiPositionManager (not on SensorFusion)
        this.wifiProcessor = new WifiDataProcessor(context);
        wifiProcessor.registerObserver(wifiPositionManager);
        wifiProcessor.registerObserver(wifiObserver -> {
            List<Wifi> copy = new ArrayList<>();
            for (Object item : wifiObserver) if (item instanceof Wifi) copy.add((Wifi) item);
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
            eventHandler.handleSensorEvent(sensorEvent);
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
     * <p>Should be called from {@link MainActivity} when resuming the application.</p>
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
     * <p>Should be called from {@link MainActivity} when pausing the application.</p>
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
        stopWirelessCollectors();
        // Background collection has ended without an active foreground owner. Stop the
        // in-memory recording so the facade cannot claim the session is still collecting.
        if (recorder != null && recorder.isRecording()) {
            recorder.stopRecording();
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
        if (!recorder.startRecording(pdrProcessing)) {
            showRecordingFailure();
            return;
        }

        eventHandler.resetBootTime(recorder.getBootTime());
        stopWirelessCollectors();
        try {
            SensorCollectionService.start(appContext);
        } catch (RuntimeException exception) {
            Log.e("SensorFusion", "Unable to start foreground collection service", exception);
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
        if (recorder != null) recorder.stopRecording();
        if (appContext != null) SensorCollectionService.stop(appContext);
        stopWirelessCollectors();
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
        float[] latLong = new float[2];
        if (!start) {
            latLong[0] = state.latitude;
            latLong[1] = state.longitude;
        } else {
            latLong = state.startLocation;
        }
        return latLong;
    }

    /**
     * Setter function for core location data.
     *
     * @param startPosition contains the initial location set by the user
     */
    public void setStartGNSSLatitude(float[] startPosition) {
        state.startLocation[0] = startPosition[0];
        state.startLocation[1] = startPosition[1];
    }

    /**
     * Function to redraw path in corrections fragment.
     *
     * @param scalingRatio new size of path due to updated step length
     */
    public void redrawPath(float scalingRatio) {
        pathView.redraw(scalingRatio);
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
        Map<SensorTypes, float[]> sensorValueMap = new HashMap<>();
        sensorValueMap.put(SensorTypes.ACCELEROMETER, state.acceleration);
        sensorValueMap.put(SensorTypes.GRAVITY, state.gravity);
        sensorValueMap.put(SensorTypes.MAGNETICFIELD, state.magneticField);
        sensorValueMap.put(SensorTypes.GYRO, state.angularVelocity);
        sensorValueMap.put(SensorTypes.LIGHT, new float[]{state.light});
        sensorValueMap.put(SensorTypes.PRESSURE, new float[]{state.pressure});
        sensorValueMap.put(SensorTypes.PROXIMITY, new float[]{state.proximity});
        sensorValueMap.put(SensorTypes.GNSSLATLONG, getGNSSLatitude(false));
        sensorValueMap.put(SensorTypes.PDR, pdrProcessing.getPDRMovement());
        return sensorValueMap;
    }

    /**
     * Return the most recent list of WiFi names and levels.
     *
     * @return list of Wifi objects.
     */
    public List<Wifi> getWifiList() {
        return wifiPositionManager.getWifiList();
    }

    /**
     * Get information about all the sensors registered in SensorFusion.
     *
     * @return List of SensorInfo objects containing name, resolution, power, etc.
     */
    public List<SensorInfo> getSensorInfos() {
        List<SensorInfo> sensorInfoList = new ArrayList<>();
        sensorInfoList.add(this.accelerometerSensor.sensorInfo);
        sensorInfoList.add(this.barometerSensor.sensorInfo);
        sensorInfoList.add(this.gyroscopeSensor.sensorInfo);
        sensorInfoList.add(this.lightSensor.sensorInfo);
        sensorInfoList.add(this.proximitySensor.sensorInfo);
        sensorInfoList.add(this.magnetometerSensor.sensorInfo);
        return sensorInfoList;
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
        return wifiPositionManager.getLatLngWifiPositioning();
    }

    /**
     * Returns the current floor the user is on, obtained using WiFi positioning.
     *
     * @return current floor number.
     */
    public int getWifiFloor() {
        return wifiPositionManager.getWifiFloor();
    }

    /**
     * Utility function to log the event frequency of each sensor.
     */
    public void logSensorFrequencies() {
        eventHandler.logSensorFrequencies();
    }

    //endregion


    private synchronized void initializePositionFusion(double latitude, double longitude, double altitude) {
        if (fusionInitialized || !Double.isFinite(latitude) || !Double.isFinite(longitude)
                || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180
                || (latitude == 0.0 && longitude == 0.0)) return;
        float[] start = state.startLocation;
        boolean hasSelectedStart = start[0] != 0.0f || start[1] != 0.0f;
        refLat = hasSelectedStart ? start[0] : latitude;
        refLon = hasSelectedStart ? start[1] : longitude;
        refAlt = Double.isFinite(altitude) ? altitude : 0.0;
        extendedKalmanFilter = new EKF(0.0, 0.0, 0.0, state.orientation[0]);
        particleFilter = new ParticleFilter(100, refLat, refLon, refAlt);
        particleFilter.initializeParticles(0.0, 0.0, state.orientation[0], 1.0, 1.0, 0.1);
        previousHeading = state.orientation[0];
        fusionInitialized = true;
        Log.i("SensorFusion", "Experimental EKF/PF initialized in local ENU frame");
    }

    private void onWifiPosition(LatLng position, int floor) {
        if (position == null) return;
        initializePositionFusion(position.latitude, position.longitude, refAlt);
        if (!fusionInitialized) return;
        double[] enu = CoordinateTransform.geodeticToEnu(
                position.latitude, position.longitude, refAlt, refLat, refLon, refAlt);
        double now = android.os.SystemClock.uptimeMillis();
        double elapsed = lastFusionMeasurementMs == 0 ? 0 : now - lastFusionMeasurementMs;
        double avgRssi = latestWifiList.stream().mapToInt(Wifi::getLevel).average().orElse(-85.0);
        double base = avgRssi > -50 ? 3.0 : (avgRssi > -75 ? 4.0 : 5.0);
        double penalty = base * (1.0 + Math.min(elapsed / 10000.0, 3.0));
        extendedKalmanFilter.update(enu[0], enu[1], penalty);
        particleFilter.updateWiFi(enu[0], enu[1]);
        particleFilter.resample();
        lastFusionMeasurementMs = (long) now;
        if (trajectoryMapFragment != null) trajectoryMapFragment.updateWifiLocation(position, 0f);
        Consumer<Integer> listener = wifiFloorChangedListener;
        if (listener != null) listener.accept(floor);
    }

    private synchronized void onGnssPosition(Location location) {
        if (location == null) return;
        initializePositionFusion(location.getLatitude(), location.getLongitude(), location.getAltitude());
        if (!fusionInitialized || (location.hasAccuracy() && location.getAccuracy() >= 20f)) return;
        double[] enu = CoordinateTransform.geodeticToEnu(location.getLatitude(),
                location.getLongitude(), location.getAltitude(), refLat, refLon, refAlt);
        extendedKalmanFilter.updateGNSS(enu[0], enu[1], enu[2], 1.0);
        particleFilter.updateGNSS(location.getLatitude(), location.getLongitude());
        particleFilter.resample();
    }

    private synchronized void onPdrStep(float[] pdrPosition, float stepLength,
                                         float headingRadians, long timestampMs) {
        if (fusionInitialized && extendedKalmanFilter != null && particleFilter != null) {
            double heading = headingRadians;
            double deltaHeading = heading - previousHeading;
            while (deltaHeading > Math.PI) deltaHeading -= 2 * Math.PI;
            while (deltaHeading < -Math.PI) deltaHeading += 2 * Math.PI;
            previousHeading = heading;
            extendedKalmanFilter.predict(Math.max(0.0, stepLength), deltaHeading);
            particleFilter.predict(Math.max(0.0, stepLength), deltaHeading);
            if (state.stepCounter % 5 == 0) particleFilter.resample();
            double[] pfPosition = particleFilter.getEstimate2D();
            double[] ekfPosition = extendedKalmanFilter.getEstimatedPositionENU();
            double disagreement = Math.hypot(ekfPosition[0] - pfPosition[0],
                    ekfPosition[1] - pfPosition[1]);
            if (disagreement > 10.0 && particleFilter.computeEntropy() < 5.0) {
                extendedKalmanFilter.resetPosition(pfPosition[0], pfPosition[1]);
                Log.d("SensorFusion", "EKF position reset from confident particle estimate; disagreement="
                        + disagreement + " m");
            }
            LatLng estimate = extendedKalmanFilter.getEstimatedPosition(refLat, refLon, refAlt);
            if (trajectoryMapFragment != null && estimate != null) {
                trajectoryMapFragment.updateFusionLocation(estimate,
                        (float) Math.toDegrees(extendedKalmanFilter.getHeading()));
            }
        }
    }

    public WiFiPositioning getWiFiPositioning() {
        return wifiPositionManager == null ? null : wiFiPositioning;
    }

    private WiFiPositioning wiFiPositioning;

    public JSONObject getAllSensorData() {
        return SensorFusionUtils.getAllSensorData(this, wiFiPositioning);
    }

    public float getGnssAccuracy() { return gnssAccuracy; }

    public void setTrajectoryMapFragment(
            com.openpositioning.PositionMe.presentation.fragment.TrajectoryMapFragment fragment) {
        trajectoryMapFragment = fragment;
    }

    public void setOnWifiFloorChangedListener(Consumer<Integer> listener) {
        wifiFloorChangedListener = listener;
    }

    SensorState sensorState() { return state; }
    List<Wifi> latestWifiList() { return new ArrayList<>(latestWifiList); }

    //region Location listener

    /**
     * Location listener class to receive updates from the location manager.
     * Writes position data to {@link SensorState} and GNSS readings to
     * {@link TrajectoryRecorder}.
     */
    class MyLocationListener implements LocationListener {
        @Override
        public void onLocationChanged(@NonNull Location location) {
            state.latitude = (float) location.getLatitude();
            state.longitude = (float) location.getLongitude();
            gnssAccuracy = location.hasAccuracy() ? location.getAccuracy() : Float.MAX_VALUE;
            recorder.addGnssData(location);
            onGnssPosition(location);
        }
    }

    //endregion
}
