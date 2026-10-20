package com.openpositioning.PositionMe.data.local;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.InvalidProtocolBufferException;
import com.openpositioning.PositionMe.LegacyTraj;
import com.openpositioning.PositionMe.Traj;

import java.io.IOException;

/** Reads both the pre-M0 trajectory wire format and the current trajectory schema. */
public final class TrajectorySchemaAdapter {
    private TrajectorySchemaAdapter() { }

    /**
     * Parses a trajectory, translating legacy M0 recordings into the current schema in memory.
     * The original file remains untouched; callers that upload it should serialize the returned
     * message so the server receives the current wire format.
     */
    public static Traj.Trajectory parse(byte[] bytes) throws InvalidProtocolBufferException {
        if (bytes == null) throw new IllegalArgumentException("Trajectory bytes must not be null");
        if (bytes.length == 0) {
            throw new InvalidProtocolBufferException("Trajectory file is empty");
        }
        if (isLegacy(bytes)) {
            return migrate(LegacyTraj.Trajectory.parseFrom(bytes));
        }
        try {
            return Traj.Trajectory.parseFrom(bytes);
        } catch (InvalidProtocolBufferException currentFailure) {
            try {
                return migrate(LegacyTraj.Trajectory.parseFrom(bytes));
            } catch (InvalidProtocolBufferException legacyFailure) {
                legacyFailure.addSuppressed(currentFailure);
                throw legacyFailure;
            }
        }
    }

    /** Returns true when old-schema tags unambiguously identify the legacy wire format. */
    public static boolean isLegacy(byte[] bytes) throws InvalidProtocolBufferException {
        if (bytes == null) throw new IllegalArgumentException("Trajectory bytes must not be null");
        try {
            CodedInputStream input = CodedInputStream.newInstance(bytes);
            while (!input.isAtEnd()) {
                int tag = input.readTag();
                if (tag == 0) break;
                int fieldNumber = tag >>> 3;
                int wireType = tag & 7;
                // Legacy imu_data is a message at field 2; M0 trajectory_version is a float.
                if (fieldNumber == 2 && wireType == 2) return true;
                // Legacy start_timestamp is varint field 10; current field 10 is a message.
                if (fieldNumber == 10 && wireType == 0) return true;
                if (!input.skipField(tag)) break;
            }
            return false;
        } catch (IOException exception) {
            InvalidProtocolBufferException parseFailure = new InvalidProtocolBufferException(
                    "Malformed trajectory wire data");
            parseFailure.initCause(exception);
            throw parseFailure;
        }
    }

    private static Traj.Trajectory migrate(LegacyTraj.Trajectory old) {
        Traj.Trajectory.Builder migrated = Traj.Trajectory.newBuilder()
                .setAndroidVersion(old.getAndroidVersion())
                .setTrajectoryId(old.getDataIdentifier())
                .setTrajectoryVersion(1.0f)
                .setStartTimestamp(old.getStartTimestamp());

        for (LegacyTraj.Motion_Sample sample : old.getImuDataList()) {
            Traj.Vector3 acceleration = Traj.Vector3.newBuilder()
                    .setX(sample.getAccX()).setY(sample.getAccY()).setZ(sample.getAccZ()).build();
            Traj.Vector3 gyroscope = Traj.Vector3.newBuilder()
                    .setX(sample.getGyrX()).setY(sample.getGyrY()).setZ(sample.getGyrZ()).build();
            Traj.Quaternion orientation = Traj.Quaternion.newBuilder()
                    .setX(sample.getRotationVectorX()).setY(sample.getRotationVectorY())
                    .setZ(sample.getRotationVectorZ()).setW(sample.getRotationVectorW()).build();
            migrated.addImuData(Traj.IMUReading.newBuilder()
                    .setRelativeTimestamp(sample.getRelativeTimestamp())
                    .setAcc(acceleration).setGyr(gyroscope)
                    .setRotationVector(orientation).setStepCount(sample.getStepCount()));
        }

        for (LegacyTraj.Pdr_Sample sample : old.getPdrDataList()) {
            migrated.addPdrData(Traj.RelativePosition.newBuilder()
                    .setRelativeTimestamp(sample.getRelativeTimestamp())
                    .setX(sample.getX()).setY(sample.getY()));
        }

        for (LegacyTraj.Position_Sample sample : old.getPositionDataList()) {
            migrated.addMagnetometerData(Traj.MagnetometerReading.newBuilder()
                    .setRelativeTimestamp(sample.getRelativeTimestamp())
                    .setMag(Traj.Vector3.newBuilder().setX(sample.getMagX())
                            .setY(sample.getMagY()).setZ(sample.getMagZ())));
        }

        for (LegacyTraj.Pressure_Sample sample : old.getPressureDataList()) {
            migrated.addPressureData(Traj.BarometerReading.newBuilder()
                    .setRelativeTimestamp(sample.getRelativeTimestamp())
                    .setPressure(sample.getPressure()));
        }

        for (LegacyTraj.Light_Sample sample : old.getLightDataList()) {
            migrated.addLightData(Traj.LightReading.newBuilder()
                    .setRelativeTimestamp(sample.getRelativeTimestamp())
                    .setLight(sample.getLight()));
        }

        for (LegacyTraj.GNSS_Sample sample : old.getGnssDataList()) {
            Traj.GNSSPosition position = Traj.GNSSPosition.newBuilder()
                    .setRelativeTimestamp(sample.getRelativeTimestamp())
                    .setLatitude(sample.getLatitude()).setLongitude(sample.getLongitude())
                    .setAltitude(sample.getAltitude()).build();
            migrated.addGnssData(Traj.GNSSReading.newBuilder()
                    .setPosition(position).setAccuracy(sample.getAccuracy())
                    .setSpeed(sample.getSpeed()).setProvider(sample.getProvider()));
        }
        if (old.getGnssDataCount() > 0) {
            LegacyTraj.GNSS_Sample first = old.getGnssData(0);
            migrated.setInitialPosition(Traj.GNSSPosition.newBuilder()
                    .setRelativeTimestamp(first.getRelativeTimestamp())
                    .setLatitude(first.getLatitude()).setLongitude(first.getLongitude())
                    .setAltitude(first.getAltitude()));
        }

        for (LegacyTraj.WiFi_Sample sample : old.getWifiDataList()) {
            Traj.Fingerprint.Builder fingerprint = Traj.Fingerprint.newBuilder()
                    .setRelativeTimestamp(sample.getRelativeTimestamp());
            for (LegacyTraj.Mac_Scan scan : sample.getMacScansList()) {
                fingerprint.addRfScans(Traj.RFScan.newBuilder()
                        .setRelativeTimestamp(scan.getRelativeTimestamp())
                        .setMac(scan.getMac()).setRssi(scan.getRssi()));
            }
            migrated.addWifiFingerprints(fingerprint);
        }

        for (LegacyTraj.AP_Data ap : old.getApsDataList()) {
            migrated.addApsData(Traj.WiFiAPData.newBuilder()
                    .setMac(ap.getMac()).setSsid(ap.getSsid())
                    .setFrequency(ap.getFrequency()).setRttEnabled(false));
        }

        if (old.hasAccelerometerInfo()) migrated.setAccelerometerInfo(migrate(old.getAccelerometerInfo()));
        if (old.hasGyroscopeInfo()) migrated.setGyroscopeInfo(migrate(old.getGyroscopeInfo()));
        if (old.hasRotationVectorInfo()) migrated.setRotationVectorInfo(migrate(old.getRotationVectorInfo()));
        if (old.hasMagnetometerInfo()) migrated.setMagnetometerInfo(migrate(old.getMagnetometerInfo()));
        if (old.hasBarometerInfo()) migrated.setBarometerInfo(migrate(old.getBarometerInfo()));
        if (old.hasLightSensorInfo()) migrated.setLightSensorInfo(migrate(old.getLightSensorInfo()));

        return migrated.build();
    }

    private static Traj.SensorInfo migrate(LegacyTraj.Sensor_Info old) {
        return Traj.SensorInfo.newBuilder().setName(old.getName()).setVendor(old.getVendor())
                .setResolution(old.getResolution()).setPower(old.getPower())
                .setVersion(old.getVersion()).setType(old.getType()).build();
    }
}
