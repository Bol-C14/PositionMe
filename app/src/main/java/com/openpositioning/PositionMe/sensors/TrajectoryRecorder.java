package com.openpositioning.PositionMe.sensors;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import com.openpositioning.PositionMe.Traj;
import com.openpositioning.PositionMe.data.remote.ServerCommunications;
import com.openpositioning.PositionMe.utils.PdrProcessing;
import com.openpositioning.PositionMe.utils.TrajectoryValidator;

import com.google.protobuf.ByteString;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Manages the recording lifecycle, protobuf trajectory construction, and trajectory upload.
 *
 * <p>Encapsulates the start/stop recording logic, the 10ms {@link TimerTask} that periodically
 * writes sensor data into the trajectory protobuf, and the upload to the server.</p>
 *
 * @see SensorFusion the singleton facade that delegates recording operations here
 * @see SensorState  the shared sensor data holder read by the internal TimerTask
 */
public class TrajectoryRecorder {

    private static final long TIME_CONST = 10;
    public static final float FILTER_COEFFICIENT = 0.96f;

    private final SensorState state;
    private final ServerCommunications serverCommunications;
    private final SharedPreferences settings;
    private final Context appContext;
    /** Serializes every access to the non-thread-safe protobuf builder and its session state. */
    private final Object recorderLock = new Object();

    // Recording state
    private volatile boolean saveRecording = false;
    private Traj.Trajectory.Builder trajectory;
    private String trajectoryId;
    private String selectedBuildingId;
    private Timer storeTrajectoryTimer;
    private long absoluteStartTime;
    private long bootTime;
    private int counter;
    private int secondCounter;
    private float filterCoefficient;

    // WiFi fingerprint dedup
    private int lastWifiFingerprintHash = 0;
    private final Set<Long> recordedApMacs = new HashSet<>();

    // BLE fingerprint dedup
    private int lastBleFingerprintHash = 0;
    private final Set<String> recordedBleMacs = new HashSet<>();

    // WakeLock
    private PowerManager.WakeLock wakeLock;

    // Sensor references for info builders and null checks in TimerTask
    private MovementSensor accelerometerSensor;
    private MovementSensor gyroscopeSensor;
    private MovementSensor magnetometerSensor;
    private MovementSensor barometerSensor;
    private MovementSensor lightSensor;
    private MovementSensor proximitySensor;
    private MovementSensor rotationSensor;

    /**
     * Creates a new TrajectoryRecorder.
     *
     * @param appContext            application context for WakeLock
     * @param state                 shared sensor state holder
     * @param serverCommunications  server communication instance for uploading
     * @param settings              shared preferences for filter coefficient
     */
    public TrajectoryRecorder(Context appContext, SensorState state,
                              ServerCommunications serverCommunications,
                              SharedPreferences settings) {
        this.appContext = appContext;
        this.state = state;
        this.serverCommunications = serverCommunications;
        this.settings = settings;
        PowerManager pm = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            this.wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "PositionMe::Recording");
        }
    }

    /**
     * Sets references to movement sensors needed for trajectory metadata and TimerTask null checks.
     */
    public void setSensorReferences(MovementSensor accel, MovementSensor gyro,
                                    MovementSensor mag, MovementSensor baro,
                                    MovementSensor light, MovementSensor prox,
                                    MovementSensor rotation) {
        synchronized (recorderLock) {
            this.accelerometerSensor = accel;
            this.gyroscopeSensor = gyro;
            this.magnetometerSensor = mag;
            this.barometerSensor = baro;
            this.lightSensor = light;
            this.proximitySensor = prox;
            this.rotationSensor = rotation;
        }
    }

    //region Recording lifecycle

    /**
     * Enables saving sensor values to the trajectory object.
     *
     * <p>Sets save recording to true, resets the absolute start time and creates a new timer
     * object for periodically writing data to the trajectory.</p>
     *
     * @param pdrProcessing PDR processor to reset at recording start
     */
    public synchronized boolean startRecording(PdrProcessing pdrProcessing) {
        if (saveRecording) return true;
        Timer newTimer = null;
        try {
            if (pdrProcessing == null) {
                throw new IllegalStateException("PDR processor is not initialized");
            }
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
                if (pm == null) throw new IllegalStateException("Power manager unavailable");
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                        "PositionMe::Recording");
            }

            long newStartTime = System.currentTimeMillis();
            long newBootTime = SystemClock.elapsedRealtime();
            MovementSensor accel;
            MovementSensor gyro;
            MovementSensor mag;
            MovementSensor baro;
            MovementSensor light;
            MovementSensor prox;
            MovementSensor rotation;
            synchronized (recorderLock) {
                accel = accelerometerSensor;
                gyro = gyroscopeSensor;
                mag = magnetometerSensor;
                baro = barometerSensor;
                light = lightSensor;
                prox = proximitySensor;
                rotation = rotationSensor;
            }
            Traj.Trajectory.Builder newTrajectory = Traj.Trajectory.newBuilder()
                    .setAndroidVersion(Build.VERSION.RELEASE)
                    .setTrajectoryVersion(2.0f)
                    .setStartTimestamp(newStartTime)
                    .setAccelerometerInfo(createInfoBuilder(accel))
                    .setGyroscopeInfo(createInfoBuilder(gyro))
                    .setMagnetometerInfo(createInfoBuilder(mag))
                    .setBarometerInfo(createInfoBuilder(baro))
                    .setLightSensorInfo(createInfoBuilder(light))
                    .setProximityInfo(createInfoBuilder(prox))
                    .setRotationVectorInfo(createInfoBuilder(rotation));

            pdrProcessing.resetPDR();
            wakeLock.acquire(31 * 60 * 1000L /*31 minutes*/);

            if (settings.getBoolean("overwrite_constants", false)) {
                this.filterCoefficient = Float.parseFloat(
                        settings.getString("accel_filter", "0.96"));
            } else {
                this.filterCoefficient = FILTER_COEFFICIENT;
            }

            // State is acquired before recorderLock everywhere in this class. Keep this
            // assignment outside the recorder critical section to avoid lock inversion.
            synchronized (state) {
                state.stepCounter = 0;
            }
            newTimer = new Timer("PositionMe-TrajectoryRecorder", true);
            synchronized (recorderLock) {
                this.absoluteStartTime = newStartTime;
                this.bootTime = newBootTime;
                this.counter = 0;
                this.secondCounter = 0;
                this.lastWifiFingerprintHash = 0;
                this.recordedApMacs.clear();
                this.lastBleFingerprintHash = 0;
                this.recordedBleMacs.clear();
                this.trajectory = newTrajectory;
                this.storeTrajectoryTimer = newTimer;
                this.saveRecording = true;
            }
            newTimer.schedule(new StoreDataInTrajectory(), 0, TIME_CONST);
            return true;
        } catch (RuntimeException exception) {
            synchronized (recorderLock) {
                saveRecording = false;
                trajectory = null;
            }
            if (newTimer != null) newTimer.cancel();
            storeTrajectoryTimer = null;
            releaseWakeLock();
            Log.e("TrajectoryRecorder", "Recording session initialization failed", exception);
            return false;
        }
    }

    /**
     * Disables saving sensor values to the trajectory object.
     */
    public synchronized void stopRecording() {
        synchronized (recorderLock) {
            saveRecording = false;
        }
        if (storeTrajectoryTimer != null) {
            storeTrajectoryTimer.cancel();
            storeTrajectoryTimer = null;
        }
        releaseWakeLock();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    /**
     * Returns whether a recording is currently in progress.
     */
    public boolean isRecording() {
        return saveRecording;
    }

    //endregion

    //region Trajectory metadata

    public void setTrajectoryId(String id) {
        synchronized (recorderLock) {
            this.trajectoryId = id;
        }
    }

    public String getTrajectoryId() {
        synchronized (recorderLock) {
            return this.trajectoryId;
        }
    }

    /**
     * Sets the selected building identifier for the current recording session.
     * Used to determine the campaign name when uploading the trajectory.
     *
     * @param buildingId building name from the floorplan API (e.g. "nucleus_building")
     */
    public void setSelectedBuildingId(String buildingId) {
        synchronized (recorderLock) {
            this.selectedBuildingId = buildingId;
        }
    }

    /**
     * Gets the selected building identifier for the current recording session.
     *
     * @return building name string, or null if no building was selected
     */
    public String getSelectedBuildingId() {
        synchronized (recorderLock) {
            return this.selectedBuildingId;
        }
    }

    /**
     * Writes the initial position and heading into the trajectory protobuf.
     * Should be called after startRecording() and setStartGNSSLatitude().
     */
    public void writeInitialMetadata() {
        SensorSnapshot snapshot;
        synchronized (state) {
            snapshot = new SensorSnapshot(SystemClock.elapsedRealtime(), state);
        }
        float[] startLocation = snapshot.getStartLocation();
        synchronized (recorderLock) {
            if (!saveRecording || trajectory == null) return;

            if (trajectoryId != null && !trajectoryId.isEmpty()) {
                trajectory.setTrajectoryId(trajectoryId);
            }

            if (startLocation[0] != 0 || startLocation[1] != 0) {
                trajectory.setInitialPosition(
                        Traj.GNSSPosition.newBuilder()
                                .setRelativeTimestamp(0)
                                .setLatitude(startLocation[0])
                                .setLongitude(startLocation[1])
                                .setAltitude(0.0)
                );
            }
        }
    }

    //endregion

    //region Data writing (called from other modules)

    /**
     * Adds a PDR position entry to the trajectory.
     */
    public void addPdrData(long relativeTimestamp, float x, float y) {
        synchronized (recorderLock) {
            if (!saveRecording || trajectory == null) return;
            trajectory.addPdrData(Traj.RelativePosition.newBuilder()
                    .setRelativeTimestamp(relativeTimestamp)
                    .setX(x)
                    .setY(y));
        }
    }

    /**
     * Adds a GNSS reading to the trajectory.
     */
    public void addGnssData(Location location) {
        if (location == null) return;
        synchronized (recorderLock) {
            if (trajectory == null || !saveRecording) return;

            long relTs = SystemClock.elapsedRealtime() - bootTime;
            Traj.GNSSPosition.Builder pos = Traj.GNSSPosition.newBuilder()
                    .setRelativeTimestamp(relTs)
                    .setLatitude(location.getLatitude())
                    .setLongitude(location.getLongitude())
                    .setAltitude(location.getAltitude());

            trajectory.addGnssData(Traj.GNSSReading.newBuilder()
                    .setPosition(pos)
                    .setAccuracy(location.getAccuracy())
                    .setSpeed(location.getSpeed())
                    .setBearing(location.getBearing())
                    .setProvider(location.getProvider()));
        }
    }

    /**
     * Adds a WiFi fingerprint to the trajectory with cross-scan deduplication.
     *
     * @param wifiList the list of scanned WiFi access points
     */
    public void addWifiFingerprint(List<Wifi> wifiList) {
        if (wifiList == null || wifiList.isEmpty()) return;
        synchronized (recorderLock) {
        if (!saveRecording || trajectory == null) return;

        int currentHash = 0;
        for (Wifi data : wifiList) {
            currentHash = 31 * currentHash + Long.hashCode(data.getBssid()) + data.getLevel();
        }

        if (currentHash != lastWifiFingerprintHash) {
            lastWifiFingerprintHash = currentHash;
            long relTs = SystemClock.elapsedRealtime() - bootTime;

            Traj.Fingerprint.Builder fp = Traj.Fingerprint.newBuilder()
                    .setRelativeTimestamp(relTs);

            for (Wifi data : wifiList) {
                fp.addRfScans(Traj.RFScan.newBuilder()
                        .setRelativeTimestamp(SystemClock.elapsedRealtime() - bootTime)
                        .setMac(data.getBssid())
                        .setRssi(data.getLevel()));

                if (!recordedApMacs.contains(data.getBssid())) {
                    recordedApMacs.add(data.getBssid());
                    trajectory.addApsData(Traj.WiFiAPData.newBuilder()
                            .setMac(data.getBssid())
                            .setSsid(data.getSsid() != null ? data.getSsid() : "")
                            .setFrequency(data.getFrequency())
                            .setRttEnabled(data.isRttEnabled()));
                }
            }

            trajectory.addWifiFingerprints(fp);
        }
        }
    }

    /**
     * Adds a test point (user-placed ground truth marker) to the trajectory.
     */
    public void addTestPoint(long pressTimestampMs, double lat, double lng) {
        synchronized (recorderLock) {
        if (trajectory == null || !saveRecording) return;

        long startTs = trajectory.getStartTimestamp();
        long rel = pressTimestampMs - startTs;
        if (rel < 0) rel = 0;

        trajectory.addTestPoints(
                Traj.GNSSPosition.newBuilder()
                        .setRelativeTimestamp(rel)
                        .setLatitude(lat)
                        .setLongitude(lng)
                        .setAltitude(0.0)
        );
        }
    }

    /**
     * Adds a BLE fingerprint to the trajectory with cross-scan deduplication.
     * Also records unique BLE device metadata in {@code ble_data}.
     *
     * @param bleList the list of scanned BLE devices
     */
    public void addBleFingerprint(List<BleDevice> bleList) {
        if (bleList == null || bleList.isEmpty()) return;
        synchronized (recorderLock) {
        if (!saveRecording || trajectory == null) return;

        int currentHash = 0;
        for (BleDevice d : bleList) {
            currentHash = 31 * currentHash
                    + (d.getMacAddress() != null ? d.getMacAddress().hashCode() : 0)
                    + d.getRssi();
        }

        if (currentHash != lastBleFingerprintHash) {
            lastBleFingerprintHash = currentHash;
            long relTs = SystemClock.elapsedRealtime() - bootTime;

            Traj.Fingerprint.Builder fp = Traj.Fingerprint.newBuilder()
                    .setRelativeTimestamp(relTs);

            for (BleDevice d : bleList) {
                fp.addRfScans(Traj.RFScan.newBuilder()
                        .setRelativeTimestamp(SystemClock.elapsedRealtime() - bootTime)
                        .setMac(d.getMacAsLong())
                        .setRssi(d.getRssi()));

                // Record BLE device metadata once per unique MAC
                if (d.getMacAddress() != null && !recordedBleMacs.contains(d.getMacAddress())) {
                    recordedBleMacs.add(d.getMacAddress());

                    Traj.BleData.Builder bleData = Traj.BleData.newBuilder()
                            .setMacAddress(d.getMacAddress())
                            .setName(d.getName() != null ? d.getName() : "")
                            .setTxPowerLevel(d.getTxPowerLevel())
                            .setAdvertiseFlags(d.getAdvertiseFlags());

                    if (d.getServiceUuids() != null) {
                        bleData.addAllServiceUuids(d.getServiceUuids());
                    }

                    if (d.getManufacturerData() != null) {
                        bleData.setManufacturerData(
                                ByteString.copyFrom(d.getManufacturerData()));
                    }

                    trajectory.addBleData(bleData);
                }
            }

            trajectory.addBleFingerprints(fp);
        }
        }
    }

    /**
     * Add WiFi RTT ranging measurement to the trajectory.
     *
     * @param mac           integer-encoded MAC address of the AP
     * @param distanceMm    measured distance in millimetres
     * @param distanceStdMm standard deviation of distance in millimetres
     * @param rssi          RSSI in dBm
     */
    public void addWifiRttReading(long mac, float distanceMm, float distanceStdMm, int rssi) {
        synchronized (recorderLock) {
            if (!saveRecording || trajectory == null) return;
            long relTs = SystemClock.elapsedRealtime() - bootTime;
            trajectory.addWifiRttData(Traj.WiFiRTTReading.newBuilder()
                    .setRelativeTimestamp(relTs)
                    .setMac(mac)
                    .setDistance(distanceMm)
                    .setDistanceStd(distanceStdMm)
                    .setRssi(rssi));
        }
    }

    /**
     * Adds a BLE RTT-style ranging estimate to the trajectory.
     *
     * <p>For protobuf compatibility this is currently serialised through
     * {@code wifi_rtt_data}. The MAC and measurement values belong to BLE scans,
     * and are produced by {@link BleRttManager}.</p>
     *
     * @param reading BLE RTT-style reading derived from BLE scan metadata
     */
    public void addBleRttReading(BleRttReading reading) {
        if (reading == null) return;
        addWifiRttReading(reading.getMac(), reading.getDistanceMm(),
                reading.getDistanceStdMm(), reading.getRssi());
    }

    //endregion

    //region Upload

    /**
     * Builds the trajectory protobuf from the current builder state.
     * Used for validation before upload.
     *
     * @return the built trajectory, or null if no recording was started
     */
    public Traj.Trajectory buildTrajectory() {
        synchronized (recorderLock) {
            return trajectory == null ? null : trajectory.build();
        }
    }

    /**
     * Validates the current trajectory against quality thresholds.
     *
     * @return validation result, or a failed result if no trajectory exists
     */
    public TrajectoryValidator.ValidationResult validateTrajectory() {
        Traj.Trajectory built = buildTrajectory();
        if (built == null) {
            java.util.List<String> errors = new java.util.ArrayList<>();
            errors.add("No trajectory data available");
            return new TrajectoryValidator.ValidationResult(
                    false, errors, java.util.Collections.emptyList());
        }
        return TrajectoryValidator.validate(built);
    }

    /**
     * Sends the trajectory object to the server.
     * Passes the user-selected building ID for campaign binding.
     */
    public void sendTrajectoryToCloud() {
        Traj.Trajectory built = buildTrajectory();
        if (built == null) {
            Log.w("TrajectoryRecorder", "No trajectory is available to upload");
            return;
        }
        String buildingId;
        synchronized (recorderLock) {
            buildingId = selectedBuildingId;
        }
        this.serverCommunications.sendTrajectory(built, buildingId);
    }

    //endregion

    //region Getters

    public long getBootTime() {
        synchronized (recorderLock) {
            return bootTime;
        }
    }

    public long getAbsoluteStartTime() {
        synchronized (recorderLock) {
            return absoluteStartTime;
        }
    }

    public ServerCommunications getServerCommunications() {
        return serverCommunications;
    }

    //endregion

    //region Internal helpers

    private Traj.SensorInfo.Builder createInfoBuilder(MovementSensor sensor) {
        SensorInfo info = sensor == null ? null : sensor.sensorInfo;
        if (info == null) {
            return Traj.SensorInfo.newBuilder()
                    .setName("Not available")
                    .setVendor("-")
                    .setResolution(-1.0f)
                    .setPower(0.0f)
                    .setVersion(0)
                    .setType(0);
        }
        return Traj.SensorInfo.newBuilder()
                .setName(info.getName())
                .setVendor(info.getVendor())
                .setResolution(info.getResolution())
                .setPower(info.getPower())
                .setVersion(info.getVersion())
                .setType(info.getType());
    }

    /**
     * Timer task to record data with the desired frequency in the trajectory class.
     * Reads from {@link SensorState} and writes to the protobuf trajectory builder.
     */
    private class StoreDataInTrajectory extends TimerTask {
        @Override
        public void run() {
            // Copy all sensor values while holding only the state lock, then release it
            // before touching recorderLock. Sensor callbacks may update state and append
            // PDR/other data, so this ordering prevents lock inversion.
            SensorSnapshot snapshot;
            synchronized (state) {
                snapshot = new SensorSnapshot(SystemClock.elapsedRealtime(), state);
            }
            synchronized (recorderLock) {
                if (!saveRecording || trajectory == null) return;
                long t = snapshot.getElapsedRealtimeMillis() - bootTime;
                float[] acceleration = snapshot.getAcceleration();
                float[] angularVelocity = snapshot.getAngularVelocity();
                float[] rotation = snapshot.getRotation();
                float[] magneticField = snapshot.getMagneticField();

                // IMU data (Vector3 + Quaternion)
                trajectory.addImuData(
                        Traj.IMUReading.newBuilder()
                                .setRelativeTimestamp(t)
                                .setAcc(
                                        Traj.Vector3.newBuilder()
                                                .setX(acceleration[0])
                                                .setY(acceleration[1])
                                                .setZ(acceleration[2])
                                )
                                .setGyr(
                                        Traj.Vector3.newBuilder()
                                                .setX(angularVelocity[0])
                                                .setY(angularVelocity[1])
                                                .setZ(angularVelocity[2])
                                )
                                .setRotationVector(
                                        Traj.Quaternion.newBuilder()
                                                .setX(rotation[0])
                                                .setY(rotation[1])
                                                .setZ(rotation[2])
                                                .setW(rotation[3])
                                )
                                .setStepCount(snapshot.getStepCounter())
                );

                // Magnetometer data
                trajectory.addMagnetometerData(
                        Traj.MagnetometerReading.newBuilder()
                                .setRelativeTimestamp(t)
                                .setMag(
                                        Traj.Vector3.newBuilder()
                                                .setX(magneticField[0])
                                                .setY(magneticField[1])
                                                .setZ(magneticField[2])
                                )
                );

                // Pressure / Light / Proximity (every ~1s, counter wraps at 99)
                if (counter >= 99) {
                    counter = 0;

                    if (barometerSensor != null && barometerSensor.sensor != null) {
                        trajectory.addPressureData(
                                Traj.BarometerReading.newBuilder()
                                        .setRelativeTimestamp(t)
                                        .setPressure(snapshot.getPressure())
                        );
                    }

                    if (lightSensor != null && lightSensor.sensor != null) {
                        trajectory.addLightData(
                                Traj.LightReading.newBuilder()
                                        .setRelativeTimestamp(t)
                                        .setLight(snapshot.getLight())
                        );
                    }

                    if (proximitySensor != null && proximitySensor.sensor != null) {
                        trajectory.addProximityData(
                                Traj.ProximityReading.newBuilder()
                                        .setRelativeTimestamp(t)
                                        .setDistance(snapshot.getProximity())
                        );
                    }
                } else {
                    counter++;
                }
            }
        }
    }

    //endregion
}
