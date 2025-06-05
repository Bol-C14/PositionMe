package com.openpositioning.PositionMe.data.local;

import com.google.gson.Gson;
import com.google.gson.JsonArray;

import java.util.ArrayList;
import java.util.List;

/** Parses PDR records from a JSON array. */
class PdrSectionParser {
    List<JsonTrajectoryParser.PdrRecord> parse(JsonArray pdrArray) {
        List<JsonTrajectoryParser.PdrRecord> pdrList = new ArrayList<>();
        if (pdrArray == null) return pdrList;
        Gson gson = new Gson();
        for (int i = 0; i < pdrArray.size(); i++) {
            JsonTrajectoryParser.PdrRecord record =
                    gson.fromJson(pdrArray.get(i), JsonTrajectoryParser.PdrRecord.class);
            pdrList.add(record);
        }
        return pdrList;
    }
}
