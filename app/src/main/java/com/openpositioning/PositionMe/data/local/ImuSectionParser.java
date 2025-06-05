package com.openpositioning.PositionMe.data.local;

import com.google.gson.Gson;
import com.google.gson.JsonArray;

import java.util.ArrayList;
import java.util.List;

/** Parses IMU records from a JSON array. */
class ImuSectionParser {
    List<JsonTrajectoryParser.ImuRecord> parse(JsonArray imuArray) {
        List<JsonTrajectoryParser.ImuRecord> imuList = new ArrayList<>();
        if (imuArray == null) return imuList;
        Gson gson = new Gson();
        for (int i = 0; i < imuArray.size(); i++) {
            JsonTrajectoryParser.ImuRecord record =
                    gson.fromJson(imuArray.get(i), JsonTrajectoryParser.ImuRecord.class);
            imuList.add(record);
        }
        return imuList;
    }
}
