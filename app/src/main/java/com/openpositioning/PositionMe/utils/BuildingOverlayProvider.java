package com.openpositioning.PositionMe.utils;

import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;

import java.util.List;

/**
 * Provides building specific information for displaying indoor maps.
 */
public interface BuildingOverlayProvider {
    /**
     * @return the unique building name used throughout the app.
     */
    String getName();

    /**
     * Checks whether the given location lies inside this building.
     */
    boolean contains(LatLng point);

    /**
     * @return resources for each floor overlay ordered by floor index.
     */
    List<Integer> getFloorResources();

    /**
     * @return the bounds used when adding the overlay to the map.
     */
    LatLngBounds getBounds();

    /**
     * @return height of a single floor for this building.
     */
    float getFloorHeight();

    /**
     * @return the default floor shown when entering the building.
     */
    int getDefaultFloor();
}
