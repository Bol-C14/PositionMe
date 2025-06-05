package com.openpositioning.PositionMe.data.local;

import com.google.gson.Gson;
import com.google.gson.JsonArray;

import java.util.ArrayList;
import java.util.List;

/** Parses GNSS records from a JSON array. */
class GnssSectionParser {
    List<JsonTrajectoryParser.GnssRecord> parse(JsonArray gnssArray) {
        List<JsonTrajectoryParser.GnssRecord> gnssList = new ArrayList<>();
        if (gnssArray == null) return gnssList;
        Gson gson = new Gson();
        for (int i = 0; i < gnssArray.size(); i++) {
            JsonTrajectoryParser.GnssRecord record =
                    gson.fromJson(gnssArray.get(i), JsonTrajectoryParser.GnssRecord.class);
            gnssList.add(record);
        }
        return gnssList;
    }
}
