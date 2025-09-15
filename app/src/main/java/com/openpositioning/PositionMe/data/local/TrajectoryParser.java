package com.openpositioning.PositionMe.data.local;

import android.content.Context;

import com.google.android.gms.maps.model.LatLng;

import java.nio.file.Path;
import java.util.List;

/**
 * Interface for parsing trajectory files into replay points.
 */
public interface TrajectoryParser {

    /**
     * Represents a single replay point containing estimated PDR position,
     * optional GNSS location, orientation, speed and timestamp.
     */
    class ReplayPoint {
        public LatLng pdrLocation;
        public LatLng gnssLocation;
        public float orientation;
        public float speed;
        public long timestamp;

        public ReplayPoint(LatLng pdrLocation, LatLng gnssLocation,
                            float orientation, float speed, long timestamp) {
            this.pdrLocation = pdrLocation;
            this.gnssLocation = gnssLocation;
            this.orientation = orientation;
            this.speed = speed;
            this.timestamp = timestamp;
        }
    }

    /**
     * Parse a trajectory file and return a list of replay points.
     *
     * @param file       path to the trajectory file
     * @param ctx        Android context for sensor calculations
     * @param originLat  reference latitude
     * @param originLng  reference longitude
     * @return list of parsed replay points
     */
    List<ReplayPoint> parse(Path file, Context ctx, double originLat, double originLng);
}
