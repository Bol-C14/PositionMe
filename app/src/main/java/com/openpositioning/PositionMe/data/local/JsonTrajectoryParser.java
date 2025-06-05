package com.openpositioning.PositionMe.data.local;

import android.content.Context;
import android.hardware.SensorManager;
import android.util.Log;

import com.google.android.gms.maps.model.LatLng;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Parses trajectory data stored in JSON files.
 */
public class JsonTrajectoryParser implements TrajectoryParser {

    private static final String TAG = "JsonTrajectoryParser";

    static class ImuRecord {
        long relativeTimestamp;
        float accX, accY, accZ;
        float gyrX, gyrY, gyrZ;
        float rotationVectorX, rotationVectorY, rotationVectorZ, rotationVectorW;
    }

    static class PdrRecord {
        long relativeTimestamp;
        float x, y;
    }

    static class GnssRecord {
        long relativeTimestamp;
        double latitude, longitude;
    }

    private final ImuSectionParser imuParser = new ImuSectionParser();
    private final PdrSectionParser pdrParser = new PdrSectionParser();
    private final GnssSectionParser gnssParser = new GnssSectionParser();

    @Override
    public List<ReplayPoint> parse(Path file, Context context,
                                   double originLat, double originLng) {
        List<ReplayPoint> result = new ArrayList<>();
        try {
            if (!Files.exists(file) || !Files.isReadable(file)) {
                Log.e(TAG, "File does NOT exist or is not readable: " + file);
                return result;
            }

            BufferedReader br = Files.newBufferedReader(file);
            JsonObject root = new JsonParser().parse(br).getAsJsonObject();
            br.close();

            long startTimestamp = root.has("startTimestamp")
                    ? root.get("startTimestamp").getAsLong() : 0;

            List<ImuRecord> imuList = imuParser.parse(root.getAsJsonArray("imuData"));
            List<PdrRecord> pdrList = pdrParser.parse(root.getAsJsonArray("pdrData"));
            List<GnssRecord> gnssList = gnssParser.parse(root.getAsJsonArray("gnssData"));

            for (int i = 0; i < pdrList.size(); i++) {
                PdrRecord pdr = pdrList.get(i);

                ImuRecord closestImu = findClosestImuRecord(imuList, pdr.relativeTimestamp);
                float orientationDeg = closestImu != null ? computeOrientationFromRotationVector(
                        closestImu.rotationVectorX,
                        closestImu.rotationVectorY,
                        closestImu.rotationVectorZ,
                        closestImu.rotationVectorW,
                        context
                ) : 0f;

                float speed = 0f;
                if (i > 0) {
                    PdrRecord prev = pdrList.get(i - 1);
                    double dt = (pdr.relativeTimestamp - prev.relativeTimestamp) / 1000.0;
                    double dx = pdr.x - prev.x;
                    double dy = pdr.y - prev.y;
                    double distance = Math.sqrt(dx * dx + dy * dy);
                    if (dt > 0) speed = (float) (distance / dt);
                }

                double lat = originLat + pdr.y * 1E-5;
                double lng = originLng + pdr.x * 1E-5;
                LatLng pdrLocation = new LatLng(lat, lng);

                GnssRecord closestGnss = findClosestGnssRecord(gnssList, pdr.relativeTimestamp);
                LatLng gnssLocation = closestGnss != null ?
                        new LatLng(closestGnss.latitude, closestGnss.longitude) : null;

                result.add(new ReplayPoint(pdrLocation, gnssLocation, orientationDeg,
                        speed, pdr.relativeTimestamp));
            }

            Collections.sort(result, Comparator.comparingLong(rp -> rp.timestamp));
        } catch (Exception e) {
            Log.e(TAG, "Error parsing trajectory file!", e);
        }

        return result;
    }

    private ImuRecord findClosestImuRecord(List<ImuRecord> imuList, long targetTimestamp) {
        return imuList.stream().min(
                Comparator.comparingLong(imu -> Math.abs(imu.relativeTimestamp - targetTimestamp)))
                .orElse(null);
    }

    private GnssRecord findClosestGnssRecord(List<GnssRecord> gnssList, long targetTimestamp) {
        return gnssList.stream().min(
                Comparator.comparingLong(gnss -> Math.abs(gnss.relativeTimestamp - targetTimestamp)))
                .orElse(null);
    }

    private float computeOrientationFromRotationVector(float rx, float ry, float rz, float rw,
                                                       Context context) {
        float[] rotationVector = new float[]{rx, ry, rz, rw};
        float[] rotationMatrix = new float[9];
        float[] orientationAngles = new float[3];

        SensorManager.getRotationMatrixFromVector(rotationMatrix, rotationVector);
        SensorManager.getOrientation(rotationMatrix, orientationAngles);

        float azimuthDeg = (float) Math.toDegrees(orientationAngles[0]);
        return azimuthDeg < 0 ? azimuthDeg + 360.0f : azimuthDeg;
    }
}
