package com.openpositioning.PositionMe.utils;

import android.graphics.Color;
import android.util.Log;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.GroundOverlay;
import com.google.android.gms.maps.model.GroundOverlayOptions;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Polygon;
import com.google.android.gms.maps.model.PolygonOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.data.remote.FloorplanApiClient;
import com.openpositioning.PositionMe.sensors.SensorFusion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Displays a building's floor map on a Google map. Uses downloaded vector floorplans when
 * available and retains the bundled bitmap overlays as an offline fallback for Nucleus and
 * Library. Floor indices are zero-based indices into the available map list.
 */
public class IndoorMapManager {
    private static final String TAG = "IndoorMapManager";

    public static final int BUILDING_NONE = 0;
    public static final int BUILDING_NUCLEUS = 1;
    public static final int BUILDING_LIBRARY = 2;
    public static final int BUILDING_MURCHISON = 3;

    public static final float NUCLEUS_FLOOR_HEIGHT = 4.2F;
    public static final float LIBRARY_FLOOR_HEIGHT = 3.6F;
    public static final float MURCHISON_FLOOR_HEIGHT = 4.0F;

    private static final int WALL_STROKE = Color.argb(200, 80, 80, 80);
    private static final int ROOM_STROKE = Color.argb(180, 33, 150, 243);
    private static final int ROOM_FILL = Color.argb(40, 33, 150, 243);
    private static final int DEFAULT_STROKE = Color.argb(150, 100, 100, 100);

    private static final List<Integer> NUCLEUS_MAPS = Collections.unmodifiableList(Arrays.asList(
            R.drawable.nucleuslg, R.drawable.nucleusg, R.drawable.nucleus1,
            R.drawable.nucleus2, R.drawable.nucleus3));
    private static final List<Integer> LIBRARY_MAPS = Collections.unmodifiableList(Arrays.asList(
            R.drawable.libraryg, R.drawable.library1, R.drawable.library2, R.drawable.library3));

    private final GoogleMap gMap;
    private final List<Polygon> drawnPolygons = new ArrayList<>();
    private final List<Polyline> drawnPolylines = new ArrayList<>();
    private final List<Polyline> buildingOutlines = new ArrayList<>();

    private LatLng currentLocation;
    private GroundOverlay groundOverlay;
    private boolean isIndoorMapSet;
    private int currentFloor;
    private String currentBuilding = "";
    private float floorHeight;
    private List<FloorplanApiClient.FloorShapes> currentFloorShapes;
    private boolean usingVectorFloorplan;

    public IndoorMapManager(GoogleMap map) {
        this.gMap = map;
    }

    /** Updates the user's position and refreshes the active building floor map. */
    public void setCurrentLocation(LatLng location) {
        currentLocation = location;
        if (location != null) setBuildingOverlay();
    }

    public float getFloorHeight() {
        return floorHeight;
    }

    public boolean getIsIndoorMapSet() {
        return isIndoorMapSet;
    }

    /** Returns the API building name (or empty string when outside supported buildings). */
    public String getCurrentBuilding() {
        return currentBuilding;
    }

    public int getCurrentBuildingType() {
        if ("nucleus_building".equals(currentBuilding)) return BUILDING_NUCLEUS;
        if ("library".equals(currentBuilding)) return BUILDING_LIBRARY;
        if ("murchison_house".equals(currentBuilding)) return BUILDING_MURCHISON;
        return BUILDING_NONE;
    }

    public int getCurrentFloor() {
        return currentFloor;
    }

    public String getCurrentFloorDisplayName() {
        if (usingVectorFloorplan && currentFloorShapes != null
                && currentFloor >= 0 && currentFloor < currentFloorShapes.size()) {
            String name = currentFloorShapes.get(currentFloor).getDisplayName();
            if (name != null && !name.isEmpty()) return name;
        }
        if ("nucleus_building".equals(currentBuilding) && currentFloor == 0) return "LG";
        if ("library".equals(currentBuilding) && currentFloor == 0) return "G";
        if ("murchison_house".equals(currentBuilding) && currentFloor == 0) return "LG";
        return String.valueOf(currentFloor);
    }

    /** Buildings with a lower-ground floor at index zero need a +1 auto-floor offset. */
    public int getAutoFloorBias() {
        return ("nucleus_building".equals(currentBuilding)
                || "murchison_house".equals(currentBuilding)) ? 1 : 0;
    }

    /**
     * Sets a floor. Auto-floor values are logical floors (0 = ground, -1 = lower ground);
     * manual values are direct zero-based map indices.
     */
    public void setCurrentFloor(int newFloor, boolean autoFloor) {
        if (autoFloor) newFloor += getAutoFloorBias();
        int count = getFloorCount();
        if (newFloor < 0 || newFloor >= count || newFloor == currentFloor) return;
        currentFloor = newFloor;
        if (usingVectorFloorplan) drawFloorShapes(currentFloor);
        else updateBitmapFloor();
    }

    public void increaseFloor() {
        setCurrentFloor(currentFloor + 1, false);
    }

    public void decreaseFloor() {
        setCurrentFloor(currentFloor - 1, false);
    }

    private int getFloorCount() {
        if (usingVectorFloorplan) {
            return currentFloorShapes == null ? 0 : currentFloorShapes.size();
        }
        if ("nucleus_building".equals(currentBuilding)) return NUCLEUS_MAPS.size();
        if ("library".equals(currentBuilding)) return LIBRARY_MAPS.size();
        return 0;
    }

    private void setBuildingOverlay() {
        try {
            String detected = detectCurrentBuilding();
            if (detected.isEmpty()) {
                clearActiveFloorMap();
                return;
            }

            FloorplanApiClient.BuildingInfo info = getCachedBuilding(detected);
            List<FloorplanApiClient.FloorShapes> shapes = info == null
                    ? Collections.emptyList() : info.getFloorShapesList();
            boolean hasVectors = shapes != null && !shapes.isEmpty();

            if (isIndoorMapSet && detected.equals(currentBuilding)
                    && hasVectors == usingVectorFloorplan) return;

            clearActiveFloorMap();
            currentBuilding = detected;
            usingVectorFloorplan = hasVectors;
            currentFloorShapes = hasVectors
                    ? new ArrayList<>(shapes) : Collections.emptyList();
            floorHeight = floorHeightFor(detected);
            currentFloor = defaultFloor(detected, hasVectors);

            if (usingVectorFloorplan) {
                drawFloorShapes(currentFloor);
                isIndoorMapSet = true;
            } else if (createBitmapOverlay(detected, currentFloor)) {
                isIndoorMapSet = true;
            }
        } catch (RuntimeException exception) {
            Log.e(TAG, "Unable to update indoor floor map", exception);
        }
    }

    private FloorplanApiClient.BuildingInfo getCachedBuilding(String name) {
        try {
            return SensorFusion.getInstance().getFloorplanBuilding(name);
        } catch (RuntimeException exception) {
            Log.w(TAG, "Floorplan cache is unavailable", exception);
            return null;
        }
    }

    private String detectCurrentBuilding() {
        try {
            for (FloorplanApiClient.BuildingInfo building
                    : SensorFusion.getInstance().getFloorplanBuildings()) {
                List<LatLng> outline = building.getOutlinePolygon();
                if (outline != null && outline.size() >= 3
                        && BuildingPolygon.pointInPolygon(currentLocation, outline)) {
                    String name = building.getName();
                    if (isSupportedBuilding(name)) return name;
                }
            }
        } catch (RuntimeException exception) {
            Log.w(TAG, "Could not inspect floorplan outlines", exception);
        }

        if (BuildingPolygon.inNucleus(currentLocation)) return "nucleus_building";
        if (BuildingPolygon.inLibrary(currentLocation)) return "library";
        if (BuildingPolygon.inMurchison(currentLocation)) return "murchison_house";
        return "";
    }

    private boolean isSupportedBuilding(String name) {
        return "nucleus_building".equals(name) || "library".equals(name)
                || "murchison_house".equals(name);
    }

    private float floorHeightFor(String building) {
        if ("nucleus_building".equals(building)) return NUCLEUS_FLOOR_HEIGHT;
        if ("library".equals(building)) return LIBRARY_FLOOR_HEIGHT;
        if ("murchison_house".equals(building)) return MURCHISON_FLOOR_HEIGHT;
        return 0F;
    }

    private int defaultFloor(String building, boolean vectorMaps) {
        if (vectorMaps) {
            int groundIndex = getAutoFloorBiasFor(building);
            return currentFloorShapes.size() > groundIndex ? groundIndex : 0;
        }
        return "nucleus_building".equals(building) ? 1 : 0;
    }

    private int getAutoFloorBiasFor(String building) {
        return ("nucleus_building".equals(building) || "murchison_house".equals(building)) ? 1 : 0;
    }

    private boolean createBitmapOverlay(String building, int floor) {
        List<Integer> maps;
        if ("nucleus_building".equals(building)) maps = NUCLEUS_MAPS;
        else if ("library".equals(building)) maps = LIBRARY_MAPS;
        else return false;

        if (floor < 0 || floor >= maps.size()) return false;
        List<LatLng> boundsPoints = "nucleus_building".equals(building)
                ? BuildingPolygon.NUCLEUS_POLYGON : BuildingPolygon.LIBRARY_POLYGON;
        LatLngBounds.Builder boundsBuilder = LatLngBounds.builder();
        for (LatLng point : boundsPoints) {
            boundsBuilder.include(point);
        }
        groundOverlay = gMap.addGroundOverlay(new GroundOverlayOptions()
                .image(BitmapDescriptorFactory.fromResource(maps.get(floor)))
                .positionFromBounds(boundsBuilder.build()));
        return groundOverlay != null;
    }

    private void updateBitmapFloor() {
        if (groundOverlay == null) return;
        List<Integer> maps = "nucleus_building".equals(currentBuilding)
                ? NUCLEUS_MAPS : LIBRARY_MAPS;
        groundOverlay.setImage(BitmapDescriptorFactory.fromResource(maps.get(currentFloor)));
    }

    private void clearActiveFloorMap() {
        clearDrawnShapes();
        if (groundOverlay != null) {
            groundOverlay.remove();
            groundOverlay = null;
        }
        isIndoorMapSet = false;
        currentBuilding = "";
        currentFloor = 0;
        currentFloorShapes = null;
        usingVectorFloorplan = false;
        floorHeight = 0F;
    }

    private void drawFloorShapes(int floorIndex) {
        clearDrawnShapes();
        if (currentFloorShapes == null || floorIndex < 0
                || floorIndex >= currentFloorShapes.size()) return;

        for (FloorplanApiClient.MapShapeFeature feature
                : currentFloorShapes.get(floorIndex).getFeatures()) {
            String geometry = feature.getGeometryType();
            String type = feature.getIndoorType();
            for (List<LatLng> part : feature.getParts()) {
                if (("MultiPolygon".equals(geometry) || "Polygon".equals(geometry))
                        && part.size() >= 3) {
                    Polygon polygon = gMap.addPolygon(new PolygonOptions().addAll(part)
                            .strokeColor(getStrokeColor(type)).strokeWidth(5f)
                            .fillColor(getFillColor(type)));
                    if (polygon != null) drawnPolygons.add(polygon);
                } else if (("MultiLineString".equals(geometry) || "LineString".equals(geometry))
                        && part.size() >= 2) {
                    Polyline line = gMap.addPolyline(new PolylineOptions().addAll(part)
                            .color(getStrokeColor(type)).width(6f));
                    if (line != null) drawnPolylines.add(line);
                }
            }
        }
    }

    private void clearDrawnShapes() {
        for (Polygon polygon : drawnPolygons) polygon.remove();
        for (Polyline polyline : drawnPolylines) polyline.remove();
        drawnPolygons.clear();
        drawnPolylines.clear();
    }

    private int getStrokeColor(String indoorType) {
        if ("wall".equals(indoorType)) return WALL_STROKE;
        if ("room".equals(indoorType)) return ROOM_STROKE;
        return DEFAULT_STROKE;
    }

    private int getFillColor(String indoorType) {
        return "room".equals(indoorType) ? ROOM_FILL : Color.TRANSPARENT;
    }

    /** Adds building outline indicators without mutating the shared polygon definitions. */
    public void setIndicationOfIndoorMap() {
        for (Polyline line : buildingOutlines) line.remove();
        buildingOutlines.clear();

        boolean nucleusDrawn = false;
        boolean libraryDrawn = false;
        boolean murchisonDrawn = false;
        try {
            for (FloorplanApiClient.BuildingInfo building
                    : SensorFusion.getInstance().getFloorplanBuildings()) {
                List<LatLng> outline = building.getOutlinePolygon();
                if (outline == null || outline.size() < 3) continue;
                addOutline(outline);
                if ("nucleus_building".equals(building.getName())) nucleusDrawn = true;
                if ("library".equals(building.getName())) libraryDrawn = true;
                if ("murchison_house".equals(building.getName())) murchisonDrawn = true;
            }
        } catch (RuntimeException exception) {
            Log.w(TAG, "Could not draw downloaded building outlines", exception);
        }
        if (!nucleusDrawn) addOutline(BuildingPolygon.NUCLEUS_POLYGON);
        if (!libraryDrawn) addOutline(BuildingPolygon.LIBRARY_POLYGON);
        if (!murchisonDrawn) addOutline(BuildingPolygon.MURCHISON_POLYGON);
    }

    private void addOutline(List<LatLng> points) {
        if (points == null || points.size() < 3) return;
        List<LatLng> closed = new ArrayList<>(points);
        closed.add(points.get(0));
        Polyline line = gMap.addPolyline(new PolylineOptions().color(Color.GREEN).addAll(closed));
        if (line != null) buildingOutlines.add(line);
    }
}
