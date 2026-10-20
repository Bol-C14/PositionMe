package com.openpositioning.PositionMe.data.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.InvalidProtocolBufferException;
import com.openpositioning.PositionMe.Traj;

import org.junit.Test;

import java.util.Base64;

public class TrajectorySchemaAdapterTest {
    /*
     * Fixed wire fixture encoded with the pre-M0 field numbers from the historical traj.proto:
     * android_version=1, imu_data=2, pdr_data=3, position_data=4, pressure_data=5,
     * light_data=6, gnss_data=7, wifi_data=8, aps_data=9, start_timestamp=10,
     * data_identifier=11, accelerometer_info=12. Keeping this independent of the current Traj
     * builders guards against accidental changes to either schema silently changing the input.
     */
    private static final byte[] LEGACY_TRAJECTORY = Base64.getDecoder().decode(
            "CgIxNBI2CGQVAACAPx0AAABAJQAAQEAtzczMPTXNzEw+PZqZmT5FAAAAAE0AAAAAVQAAAABdAACA"
                    + "P2AHGgwIZBUAAMA/HQAAEMAiEQhkFQAA8EEdAAAgwiUAAEhCKgcIZBUAUH1EMgcIZBUAAChCOiAI"
                    + "ZBUAAE5CHY/C9b0lAABwQS0AAGBANZqZmT86A2dwc0IXCGQSEwhkEPftlZcEGNH//////////wFK"
                    + "Dgj37ZWXBBIDbGFiGOwSUIDQlf+8MVoObGVnYWN5LWZpeHR1cmViHQoFYWNjZWwSBnZlbmRvch0K"
                    + "1yM8JQAAAD8oATAK");

    @Test
    public void legacyTrajectoryIsTranslatedToCurrentRecords() throws Exception {
        assertTrue(TrajectorySchemaAdapter.isLegacy(LEGACY_TRAJECTORY));

        Traj.Trajectory trajectory = TrajectorySchemaAdapter.parse(LEGACY_TRAJECTORY);

        assertEquals("14", trajectory.getAndroidVersion());
        assertEquals(1.0f, trajectory.getTrajectoryVersion(), 0.0f);
        assertEquals("legacy-fixture", trajectory.getTrajectoryId());
        assertEquals(1_700_000_000_000L, trajectory.getStartTimestamp());

        assertEquals(1, trajectory.getImuDataCount());
        Traj.IMUReading imu = trajectory.getImuData(0);
        assertEquals(100L, imu.getRelativeTimestamp());
        assertEquals(1.0f, imu.getAcc().getX(), 0.0f);
        assertEquals(2.0f, imu.getAcc().getY(), 0.0f);
        assertEquals(3.0f, imu.getAcc().getZ(), 0.0f);
        assertEquals(0.1f, imu.getGyr().getX(), 1e-6f);
        assertEquals(0.2f, imu.getGyr().getY(), 1e-6f);
        assertEquals(0.3f, imu.getGyr().getZ(), 1e-6f);
        assertEquals(1.0f, imu.getRotationVector().getW(), 0.0f);
        assertEquals(7, imu.getStepCount());

        assertEquals(1, trajectory.getPdrDataCount());
        assertEquals(100L, trajectory.getPdrData(0).getRelativeTimestamp());
        assertEquals(1.5f, trajectory.getPdrData(0).getX(), 0.0f);
        assertEquals(-2.25f, trajectory.getPdrData(0).getY(), 0.0f);

        assertEquals(1, trajectory.getMagnetometerDataCount());
        assertEquals(30.0f, trajectory.getMagnetometerData(0).getMag().getX(), 0.0f);
        assertEquals(-40.0f, trajectory.getMagnetometerData(0).getMag().getY(), 0.0f);
        assertEquals(50.0f, trajectory.getMagnetometerData(0).getMag().getZ(), 0.0f);
        assertEquals(1013.25f, trajectory.getPressureData(0).getPressure(), 0.0f);
        assertEquals(42.0f, trajectory.getLightData(0).getLight(), 0.0f);

        assertEquals(1, trajectory.getGnssDataCount());
        Traj.GNSSReading gnss = trajectory.getGnssData(0);
        assertEquals(100L, gnss.getPosition().getRelativeTimestamp());
        assertEquals(51.5, gnss.getPosition().getLatitude(), 1e-6);
        assertEquals(-0.12, gnss.getPosition().getLongitude(), 1e-6);
        assertEquals(15.0, gnss.getPosition().getAltitude(), 0.0);
        assertEquals(3.5f, gnss.getAccuracy(), 0.0f);
        assertEquals(1.2f, gnss.getSpeed(), 1e-6f);
        assertEquals("gps", gnss.getProvider());
        assertEquals(gnss.getPosition(), trajectory.getInitialPosition());

        assertEquals(1, trajectory.getWifiFingerprintsCount());
        Traj.RFScan scan = trajectory.getWifiFingerprints(0).getRfScans(0);
        assertEquals(100L, scan.getRelativeTimestamp());
        assertEquals(1_122_334_455L, scan.getMac());
        assertEquals(-47, scan.getRssi());

        assertEquals(1, trajectory.getApsDataCount());
        assertEquals(1_122_334_455L, trajectory.getApsData(0).getMac());
        assertEquals("lab", trajectory.getApsData(0).getSsid());
        assertEquals(2412L, trajectory.getApsData(0).getFrequency());
        assertFalse(trajectory.getApsData(0).getRttEnabled());

        assertTrue(trajectory.hasAccelerometerInfo());
        assertEquals("accel", trajectory.getAccelerometerInfo().getName());
        assertEquals("vendor", trajectory.getAccelerometerInfo().getVendor());
        assertEquals(0.01f, trajectory.getAccelerometerInfo().getResolution(), 1e-7f);
        assertEquals(0.5f, trajectory.getAccelerometerInfo().getPower(), 0.0f);
        assertEquals(1, trajectory.getAccelerometerInfo().getVersion());
        assertEquals(10, trajectory.getAccelerometerInfo().getType());
    }

    @Test
    public void currentSchemaTrajectoryPassesThroughUnchanged() throws Exception {
        Traj.Trajectory current = Traj.Trajectory.newBuilder()
                .setAndroidVersion("Android 15")
                .setTrajectoryVersion(2.0f)
                .setTrajectoryId("current-fixture")
                .setStartTimestamp(1_700_000_123_456L)
                .addPdrData(Traj.RelativePosition.newBuilder()
                        .setRelativeTimestamp(250L).setX(3.0f).setY(-4.0f))
                .build();

        assertFalse(TrajectorySchemaAdapter.isLegacy(current.toByteArray()));
        assertEquals(current, TrajectorySchemaAdapter.parse(current.toByteArray()));
    }

    @Test
    public void malformedTrajectoryFailsInsteadOfReturningPartialData() {
        byte[] truncatedNestedMessage = new byte[] {0x12, 0x05, 0x08};

        assertThrows(InvalidProtocolBufferException.class,
                () -> TrajectorySchemaAdapter.parse(truncatedNestedMessage));
    }
}
