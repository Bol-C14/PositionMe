package com.openpositioning.PositionMe.data.remote;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Parses and validates the server catalog before it reaches presentation state. */
public final class RemoteTrajectoryCatalogParser {
    private RemoteTrajectoryCatalogParser() { }

    public static List<RemoteTrajectorySummary> parse(String response) throws IOException {
        try {
            JSONArray array = new JSONArray(response);
            List<RemoteTrajectorySummary> entries = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                Object rawId = item.get("id");
                Object rawOwner = item.get("owner_id");
                if (rawId == JSONObject.NULL || rawOwner == JSONObject.NULL) {
                    throw new IllegalArgumentException("Catalog entry has null metadata");
                }
                String id = rawId.toString();
                String owner = rawOwner.toString();
                String date = item.getString("date_submitted");
                Integer.parseInt(id);
                entries.add(new RemoteTrajectorySummary(id, owner, date));
            }
            entries.sort(Comparator.comparingInt(entry -> Integer.parseInt(entry.getId())));
            return entries;
        } catch (Exception invalidResponse) {
            throw new IOException("Could not parse remote trajectory catalog", invalidResponse);
        }
    }
}
