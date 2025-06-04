package com.openpositioning.PositionMe.utils;

import android.graphics.Color;
import android.util.Log;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.GroundOverlay;
import com.google.android.gms.maps.model.GroundOverlayOptions;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.PolylineOptions;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.utils.BuildingOverlayProvider;
import com.openpositioning.PositionMe.utils.NucleusBuildingManager;
import com.openpositioning.PositionMe.utils.LibraryBuildingManager;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Class used to manage indoor floor map overlays
 * Currently used by TrajectoryMapFragment to display indoor maps
 * @see BuildingPolygon Describes the bounds of buildings and the methods to check if point is
 * in the building
 * @author Arun Gopalakrishnan
 * @author Shu Gu
 * @version 1.1 - Bug fix for not updating the overlay if the location jumps from one building to another.
 */
public class IndoorMapManager {
    // Map instance and overlay
    private GoogleMap gMap;
    private GroundOverlay groundOverlay;
    // Current user location
    private LatLng currentLocation;
    // Indicates if an indoor map overlay is currently set
    private boolean isIndoorMapSet = false;
    // Current floor and floor height
    private int currentFloor;
    private float floorHeight;
    // NEW: Track which building's overlay is currently shown ("nucleus", "library", or empty)
    private String currentBuilding = "";

    // Building overlay providers indexed by building name
    private final Map<String, BuildingOverlayProvider> providers = new HashMap<>();
    private BuildingOverlayProvider currentProvider;

    public IndoorMapManager(GoogleMap map) {
        this.gMap = map;
        providers.put("nucleus", new NucleusBuildingManager(map));
        providers.put("library", new LibraryBuildingManager(map));
    }

    /**
     * Updates the current location and sets the appropriate building overlay.
     *
     * @param currentLocation New location of the user.
     */
    public void setCurrentLocation(LatLng currentLocation) {
        this.currentLocation = currentLocation;
        setBuildingOverlay();
    }

    /**
     * Returns the floor height of the current building.
     */
    public float getFloorHeight() {
        return floorHeight;
    }

    /**
     * Returns true if an indoor map overlay is visible, false otherwise.
     */
    public boolean getIsIndoorMapSet() {
        return isIndoorMapSet;
    }

    /**
     * Sets the current floor and updates the indoor map overlay image.
     *
     * @param newFloor  The new floor the user is on.
     * @param autoFloor True if this change comes from an auto-floor feature.
     */
    public void setCurrentFloor(int newFloor, boolean autoFloor) {
        if (currentProvider == null) {
            return;
        }

        if ("nucleus".equals(currentProvider.getName()) && autoFloor) {
            newFloor += 1;
        }

        List<Integer> resources = currentProvider.getFloorResources();
        if (newFloor >= 0 && newFloor < resources.size() && newFloor != this.currentFloor) {
            groundOverlay.setImage(BitmapDescriptorFactory.fromResource(resources.get(newFloor)));
            this.currentFloor = newFloor;
        }
    }

    public void increaseFloor() {
        this.setCurrentFloor(currentFloor + 1, false);
    }

    public void decreaseFloor() {
        this.setCurrentFloor(currentFloor - 1, false);
    }

    /**
     * Sets or updates the building overlay based on the user's current location.
     * If the user jumps from one building to another, the overlay is refreshed.
     */
    private void setBuildingOverlay() {
        try {
            for (BuildingOverlayProvider provider : providers.values()) {
                if (provider.contains(currentLocation)) {
                    if (!isIndoorMapSet || !provider.getName().equals(currentBuilding)) {
                        if (isIndoorMapSet && groundOverlay != null) {
                            groundOverlay.remove();
                        }
                        groundOverlay = gMap.addGroundOverlay(new GroundOverlayOptions()
                                .image(BitmapDescriptorFactory.fromResource(provider.getFloorResources().get(provider.getDefaultFloor())))
                                .positionFromBounds(provider.getBounds()));
                        isIndoorMapSet = true;
                        currentFloor = provider.getDefaultFloor();
                        floorHeight = provider.getFloorHeight();
                        currentBuilding = provider.getName();
                        currentProvider = provider;
                    }
                    return;
                }
            }

            // Not inside any building
            if (isIndoorMapSet && groundOverlay != null) {
                groundOverlay.remove();
                isIndoorMapSet = false;
                currentFloor = 0;
                currentBuilding = "";
                currentProvider = null;
            }
        } catch (Exception ex) {
            Log.e("Error with overlay, Exception:", ex.toString());
        }
    }

    /**
     * Sets an indication of available floor maps for the buildings using green polylines.
     */
    public void setIndicationOfIndoorMap() {
        // Indicator for Nucleus Building
        List<LatLng> points = BuildingPolygon.NUCLEUS_POLYGON;
        points.add(BuildingPolygon.NUCLEUS_POLYGON.get(0)); // Closing boundary
        gMap.addPolyline(new PolylineOptions().color(Color.GREEN)
                .addAll(points));

        // Indicator for the Library Building
        points = BuildingPolygon.LIBRARY_POLYGON;
        points.add(BuildingPolygon.LIBRARY_POLYGON.get(0)); // Closing boundary
        gMap.addPolyline(new PolylineOptions().color(Color.GREEN)
                .addAll(points));
    }


    // get current floor - return current floor
    public int getCurrentFloor() {
        return this.currentFloor;
    }

    // get current building - return name of current building / int represent
    public String getCurrentBuilding() {
        return this.currentBuilding;
    }
}
