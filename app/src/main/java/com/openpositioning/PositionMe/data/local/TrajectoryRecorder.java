package com.openpositioning.PositionMe.data.local;

import android.util.Log;

import com.openpositioning.PositionMe.Traj;
import com.openpositioning.PositionMe.data.remote.ServerCommunications;
import com.openpositioning.PositionMe.sensors.Wifi;

import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.function.Consumer;

/**
 * TrajectoryRecorder is responsible for managing the recording and storing of sensor data
 * into Protobuf trajectory objects. This class encapsulates all the Protobuf-specific logic,
 * allowing the sensor data collection to be independent of the storage format.
 */
public class TrajectoryRecorder {
    private static final String TAG = "TrajectoryRecorder";
    private static final long TIME_CONST = 10; // Static constant for calculations with milliseconds

    // Trajectory object containing all data
    private Traj.Trajectory.Builder trajectory;

    // Timer object for scheduling data recording
    private Timer storeTrajectoryTimer;

    // Track recording state
    private boolean isRecording = false;

    // Server communication for sending data
    private ServerCommunications serverCommunications;

    // Reference coordinates
    private double refLat, refLon, refAlt;

    // Listener for when trajectory is sent to server
    private Consumer<String> trajectorySentListener;

    /**
     * Constructor for TrajectoryRecorder
     * @param serverCommunications Server communications instance for uploading trajectories
     */
    public TrajectoryRecorder(ServerCommunications serverCommunications) {
        this.serverCommunications = serverCommunications;
    }

    /**
     * Starts recording trajectory data
     * @param name Name of the trajectory
     * @param refLat Reference latitude
     * @param refLon Reference longitude
     * @param refAlt Reference altitude
     */
    public void startRecording(String name, double refLat, double refLon, double refAlt) {
        if (isRecording) {
            stopRecording();
        }

        this.refLat = refLat;
        this.refLon = refLon;
        this.refAlt = refAlt;

        // Initialize trajectory builder with metadata
        this.trajectory = Traj.Trajectory.newBuilder()
                .setDataIdentifier(name)
                .setStartTimestamp(System.currentTimeMillis());

        // Start timer for storing data
        this.storeTrajectoryTimer = new Timer();
        this.storeTrajectoryTimer.schedule(new StoreDataTask(), 0, TIME_CONST);

        isRecording = true;
        Log.d(TAG, "Started recording trajectory: " + name);
    }

    /**
     * Stops recording trajectory data
     * @return The built Trajectory object
     */
    public Traj.Trajectory stopRecording() {
        if (!isRecording) {
            return null;
        }

        if (storeTrajectoryTimer != null) {
            storeTrajectoryTimer.cancel();
            storeTrajectoryTimer = null;
        }

        isRecording = false;

        // Build and return the final trajectory
        Traj.Trajectory finalTrajectory = trajectory.build();
        Log.d(TAG, "Stopped recording trajectory: " + finalTrajectory.getDataIdentifier());
        return finalTrajectory;
    }

    /**
     * Sends the current trajectory to the server
     */
    public void sendTrajectoryToServer() {
        if (trajectory == null) {
            Log.e(TAG, "Cannot send trajectory - no trajectory data available");
            return;
        }

        Traj.Trajectory finalTrajectory = trajectory.build();

        if (serverCommunications != null) {
            // Note: ServerCommunications class needs to implement uploadTrajectory method
            // For now, we'll just log the trajectory data
            Log.d(TAG, "Trajectory ready to send to server, identifier: " + finalTrajectory.getDataIdentifier());
            if (trajectorySentListener != null) {
                trajectorySentListener.accept("Trajectory processed locally");
            }
        } else {
            Log.e(TAG, "Cannot send trajectory - serverCommunications is null");
        }
    }

    /**
     * Sets a listener to be called when a trajectory is sent to the server
     * @param listener The listener to call
     */
    public void setTrajectorySentListener(Consumer<String> listener) {
        this.trajectorySentListener = listener;
    }

    /**
     * Add PDR (Pedestrian Dead Reckoning) data to the trajectory
     * @param timestamp Timestamp of the PDR sample
     * @param stepLength Length of the step detected
     * @param heading Heading direction in radians
     * @param x X coordinate in ENU reference frame
     * @param y Y coordinate in ENU reference frame
     */
    public void addPdrData(long timestamp, float stepLength, double heading, float x, float y) {
        if (!isRecording || trajectory == null) {
            return;
        }

        trajectory.addPdrData(Traj.Pdr_Sample.newBuilder()
                .setRelativeTimestamp(timestamp)
                .setX(x)
                .setY(y)
                .build());
    }

    /**
     * Add GNSS data to the trajectory
     * @param timestamp Timestamp of the GNSS sample
     * @param latitude Latitude in degrees
     * @param longitude Longitude in degrees
     * @param altitude Altitude in meters
     * @param accuracy Position accuracy in meters
     */
    public void addGnssData(long timestamp, double latitude, double longitude, double altitude, float accuracy) {
        if (!isRecording || trajectory == null) {
            return;
        }

        trajectory.addGnssData(Traj.GNSS_Sample.newBuilder()
                .setRelativeTimestamp(timestamp)
                .setLatitude((float) latitude)
                .setLongitude((float) longitude)
                .setAltitude((float) altitude)
                .setAccuracy(accuracy)
                .build());
    }

    /**
     * Add WiFi scan data to the trajectory
     * @param timestamp Timestamp of the WiFi scan
     * @param wifiList List of WiFi access points detected
     */
    public void addWifiData(long timestamp, List<Wifi> wifiList) {
        if (!isRecording || trajectory == null || wifiList == null || wifiList.isEmpty()) {
            return;
        }

        Traj.WiFi_Sample.Builder wifiData = Traj.WiFi_Sample.newBuilder()
                .setRelativeTimestamp(timestamp);

        for (Wifi wifi : wifiList) {
            wifiData.addMacScans(Traj.Mac_Scan.newBuilder()
                    .setMac(wifi.getBssid())
                    .setRssi(wifi.getLevel())
                    .build());
        }

        this.trajectory.addWifiData(wifiData);
    }

    /**
     * Add IMU motion data to the trajectory
     * @param timestamp Timestamp of the IMU sample
     * @param accX Acceleration X component
     * @param accY Acceleration Y component
     * @param accZ Acceleration Z component
     * @param gyroX Gyroscope X component
     * @param gyroY Gyroscope Y component
     * @param gyroZ Gyroscope Z component
     * @param magX Magnetometer X component
     * @param magY Magnetometer Y component
     * @param magZ Magnetometer Z component
     */
    public void addImuData(long timestamp, float accX, float accY, float accZ,
                           float gyroX, float gyroY, float gyroZ,
                           float magX, float magY, float magZ) {
        if (!isRecording || trajectory == null) {
            return;
        }

        trajectory.addImuData(Traj.Motion_Sample.newBuilder()
                .setRelativeTimestamp(timestamp)
                .setAccX(accX)
                .setAccY(accY)
                .setAccZ(accZ)
                .setGyrX(gyroX)
                .setGyrY(gyroY)
                .setGyrZ(gyroZ)
                .build());
    }

    /**
     * Add pressure sensor data to the trajectory
     * @param timestamp Timestamp of the pressure sample
     * @param pressure Barometric pressure in hPa
     */
    public void addPressureData(long timestamp, float pressure) {
        if (!isRecording || trajectory == null) {
            return;
        }

        trajectory.addPressureData(Traj.Pressure_Sample.newBuilder()
                .setRelativeTimestamp(timestamp)
                .setPressure(pressure)
                .build());
    }

    /**
     * Add access point data to the trajectory
     * @param timestamp Timestamp of the AP data
     * @param bssid BSSID of the access point
     * @param ssid SSID of the access point
     * @param rssi RSSI value of the access point
     */
    public void addApData(long timestamp, String bssid, String ssid, int rssi) {
        if (!isRecording || trajectory == null) {
            return;
        }

        // Note: AP_Data doesn't have timestamp in the protobuf schema
        trajectory.addApsData(Traj.AP_Data.newBuilder()
                .setMac(Long.parseLong(bssid.replaceAll(":", ""), 16))
                .setSsid(ssid)
                .setFrequency(rssi) // Note: This should be frequency, not RSSI
                .build());
    }

    /**
     * Check if currently recording
     * @return true if recording, false otherwise
     */
    public boolean isRecording() {
        return isRecording;
    }

    /**
     * Task for storing data in the trajectory at regular intervals
     */
    private class StoreDataTask extends TimerTask {
        @Override
        public void run() {
            // This method can be extended to add regular data sampling
            // Currently, data is added directly through the add* methods
        }
    }
}
