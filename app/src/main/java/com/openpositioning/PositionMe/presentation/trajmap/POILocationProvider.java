package com.openpositioning.PositionMe.presentation.trajmap;

import android.content.Context;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Utility class for loading point-of-interest locations from a JSON configuration file.
 */
public class POILocationProvider {
    private static Map<String, Map<String, Map<Integer, List<LatLng>>>> locationMap;

    private static void ensureLoaded(Context context) {
        if (locationMap != null) return;
        locationMap = new HashMap<>();
        try {
            InputStream is = context.getResources().openRawResource(R.raw.poi_locations);
            byte[] buffer = new byte[is.available()];
            int read = is.read(buffer);
            is.close();
            if (read > 0) {
                JSONObject root = new JSONObject(new String(buffer));
                Iterator<String> types = root.keys();
                while (types.hasNext()) {
                    String type = types.next();
                    JSONObject buildingsObj = root.getJSONObject(type);
                    Map<String, Map<Integer, List<LatLng>>> buildingMap = new HashMap<>();
                    Iterator<String> buildingKeys = buildingsObj.keys();
                    while (buildingKeys.hasNext()) {
                        String building = buildingKeys.next();
                        JSONObject floorsObj = buildingsObj.getJSONObject(building);
                        Map<Integer, List<LatLng>> floorMap = new HashMap<>();
                        Iterator<String> floorKeys = floorsObj.keys();
                        while (floorKeys.hasNext()) {
                            String floorKey = floorKeys.next();
                            int floor = Integer.parseInt(floorKey);
                            JSONArray arr = floorsObj.getJSONArray(floorKey);
                            List<LatLng> list = new ArrayList<>();
                            for (int i = 0; i < arr.length(); i++) {
                                JSONObject obj = arr.getJSONObject(i);
                                double lat = obj.getDouble("lat");
                                double lng = obj.getDouble("lng");
                                list.add(new LatLng(lat, lng));
                            }
                            floorMap.put(floor, list);
                        }
                        buildingMap.put(building, floorMap);
                    }
                    locationMap.put(type, buildingMap);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Retrieves the list of locations for the given marker type, building and floor.
     */
    public static List<LatLng> getLocations(String type, String building, int floor, Context context) {
        ensureLoaded(context);
        Map<String, Map<Integer, List<LatLng>>> buildings = locationMap.get(type);
        if (buildings == null) return null;
        Map<Integer, List<LatLng>> floors = buildings.get(building);
        if (floors == null) return null;
        return floors.get(floor);
    }
}
