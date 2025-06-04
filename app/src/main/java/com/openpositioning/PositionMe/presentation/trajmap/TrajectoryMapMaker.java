package com.openpositioning.PositionMe.presentation.trajmap;

import android.content.Context;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.openpositioning.PositionMe.presentation.trajmap.MarkerFactory;
import com.openpositioning.PositionMe.presentation.trajmap.POILocationProvider;

import java.util.List;

/**
 * Utility class for managing and updating markers on a Google Map, representing various points of interest within a building,
 * such as medical rooms, emergency exits, lifts, toilets, drinking water fountains, and accessible routes.
 *
 * This class provides static methods to update the markers based on the current floor and building. Each method clears any
 * existing markers of the specified type, retrieves the relevant locations for the current floor and building (currently
 * hardcoded for the "nucleus" building), and adds new markers at those locations.
 *
 * Marker icons are created using {@link UtilFunctions#getBitmapFromVector(Context, int)} to convert vector drawables into
 * BitmapDescriptor objects suitable for Google Map markers.
 *
 * All methods require a {@link GoogleMap} instance to add markers to, the current floor and building as integers and strings
 * respectively, a {@link List} to store the created {@link Marker} objects (which is cleared and updated by each method),
 * and a {@link Context} for accessing resources.
 *
 * @author Lai Gan
 */

public class TrajectoryMapMaker {

    // updateMedicalRoomMarkers
    public static void updateMedicalRoomMarkers(
            GoogleMap gMap,
            int currentFloor,
            String currentBuilding,
            List<Marker> medicalRoomMarkers,
            Context context
    ) {
        // Remove existing markers first to avoid duplicates
        for (Marker marker : medicalRoomMarkers) {
            marker.remove();
        }
        medicalRoomMarkers.clear();

        // Retrieve locations from configuration
        List<LatLng> medicalRoomLocations = POILocationProvider.getLocations(
                "medicalRoom", currentBuilding, currentFloor, context);

        // If not null, add Markers sequentially.
        if (medicalRoomLocations != null) {
            for (LatLng location : medicalRoomLocations) {
                Marker marker = MarkerFactory.createMarker(
                        gMap,
                        location,
                        "Medical Room",
                        "medicalRoom",
                        context
                );
                if (marker != null) {
                    medicalRoomMarkers.add(marker);
                }
            }
        }
    }


    /**
     * Updates the emergency exit markers on the map based on the current floor and building.
     * Clears any existing markers and adds new markers for the specified location.
     *
     * @param gMap The GoogleMap instance to add markers to.
     * @param currentFloor The current floor of the building.
     * @param currentBuilding The current building name.
     * @param emergencyExitMarkers A list to store the added emergency exit markers.  This list is cleared and repopulated with new markers.
     * @param context The application context, used for accessing resources.
     */
    public static void updateEmergencyExitMarkers(
            GoogleMap gMap,
            int currentFloor,
            String currentBuilding,
            List<Marker> emergencyExitMarkers,
            Context context
    ) {
        for (Marker marker : emergencyExitMarkers) {
            marker.remove();
        }
        emergencyExitMarkers.clear();

        List<LatLng> exitLocations = POILocationProvider.getLocations(
                "emergencyExit", currentBuilding, currentFloor, context);


        if (exitLocations != null) {
            for (LatLng location : exitLocations) {
                Marker marker = MarkerFactory.createMarker(
                        gMap,
                        location,
                        "Emergency Exit",
                        "emergencyExit",
                        context
                );
                if (marker != null) {
                    emergencyExitMarkers.add(marker);
                }
            }
        }
    }

    /**
     * Updates the lift markers on the Google Map based on the current floor and building.
     * This method clears any existing lift markers and adds new markers at predefined locations
     * for the specified floor and building. Currently, only the "nucleus" building is supported.
     *
     * @param gMap The Google Map instance on which to display the markers.
     * @param currentFloor The current floor of the building.
     * @param currentBuilding The current building name (e.g., "nucleus").
     * @param liftMarkers A list to store the Marker objects representing the lifts.  This list is cleared and updated by the method.
     * @param context The application context, used to access resources.
     */
    public static void updateLiftMarkers(
            GoogleMap gMap,
            int currentFloor,
            String currentBuilding,
            List<Marker> liftMarkers,
            Context context
    ) {
        for (Marker marker : liftMarkers) {
            marker.remove();
        }
        liftMarkers.clear();

        List<LatLng> liftLocations = POILocationProvider.getLocations(
                "lift", currentBuilding, currentFloor, context);

        if (liftLocations != null) {
            for (LatLng location : liftLocations) {
                Marker marker = MarkerFactory.createMarker(
                        gMap,
                        location,
                        "Lift",
                        "lift",
                        context
                );
                if (marker != null) {
                    liftMarkers.add(marker);
                }
            }
        }
    }

    /**
     * Updates the markers on the map representing accessible toilet locations based on the current floor and building.
     *
     * This method clears any existing accessible toilet markers from the map and the provided list,
     * then adds new markers for the accessible toilets on the specified floor of the given building.
     * The toilet locations are hardcoded for each floor and building combination.  If no accessible
     * toilets are defined for the current floor/building, no new markers are added.
     *
     * @param gMap The GoogleMap object on which to display the markers.
     * @param currentFloor The current floor number of the building.
     * @param currentBuilding The name or identifier of the current building.
     * @param accessibleToiletMarkers A list to store the Marker objects representing the accessible toilets.  This list is cleared and updated by the method.
     * @param context The application context, used to access resources like the toilet icon.
     */
    public static void updateAccessibleToiletMarkers(
            GoogleMap gMap,
            int currentFloor,
            String currentBuilding,
            List<Marker> accessibleToiletMarkers,
            Context context
    ) {
        for (Marker marker : accessibleToiletMarkers) {
            marker.remove();
        }
        accessibleToiletMarkers.clear();

        List<LatLng> toiletLocations = POILocationProvider.getLocations(
                "accessibleToilet", currentBuilding, currentFloor, context);

        if (toiletLocations != null) {
            for (LatLng location : toiletLocations) {
                Marker marker = MarkerFactory.createMarker(
                        gMap,
                        location,
                        "Accessible Toilet",
                        "accessibleToilet",
                        context
                );
                if (marker != null) {
                    accessibleToiletMarkers.add(marker);
                }
            }
        }
    }

    /**
     * Updates the drinking water markers on the map based on the current floor and building.
     * Clears any existing markers and adds new markers for drinking water locations on the specified floor of the building.
     *
     * @param gMap The GoogleMap instance to add markers to.
     * @param currentFloor The current floor of the building.
     * @param currentBuilding The current building.
     * @param drinkingWaterMarkers A list to store the added drinking water markers.  Markers that are added to the map are also added to this list so they can be cleared later.
     * @param context The application context.
     */ // Example: Update Drinking Water marker
    public static void updateDrinkingWaterMarkers(
            GoogleMap gMap,
            int currentFloor,
            String currentBuilding,
            List<Marker> drinkingWaterMarkers,
            Context context
    ) {
        for (Marker marker : drinkingWaterMarkers) {
            marker.remove();
        }
        drinkingWaterMarkers.clear();

        List<LatLng> waterLocations = POILocationProvider.getLocations(
                "drinkingWater", currentBuilding, currentFloor, context);

        if (waterLocations != null) {
            for (LatLng location : waterLocations) {
                Marker marker = MarkerFactory.createMarker(
                        gMap,
                        location,
                        "Drinking Water",
                        "drinkingWater",
                        context
                );
                if (marker != null) {
                    drinkingWaterMarkers.add(marker);
                }
            }
        }
    }

    /**
     * Updates the toilet markers displayed on the map based on the currently selected floor and building.
     * Clears any existing toilet markers and adds new ones if toilet locations are defined for the
     * specified floor and building.
     *
     * @param gMap The Google Map instance on which to display the markers.
     * @param currentFloor The currently selected floor of the building.
     * @param currentBuilding The currently selected building.
     * @param toiletMarkers A list to store the currently displayed toilet markers.  This list is cleared and updated within the method.
     * @param context The application context, used to access resources like the toilet icon.
     */
    public static void updateToiletMarkers(
            GoogleMap gMap,
            int currentFloor,
            String currentBuilding,
            List<Marker> toiletMarkers,
            Context context
    ) {
        for (Marker marker : toiletMarkers) {
            marker.remove();
        }
        toiletMarkers.clear();

        List<LatLng> toiletLocations = POILocationProvider.getLocations(
                "toilet", currentBuilding, currentFloor, context);

        if (toiletLocations != null) {
            for (LatLng location : toiletLocations) {
                Marker marker = MarkerFactory.createMarker(
                        gMap,
                        location,
                        "Toilet",
                        "toilet",
                        context
                );
                if (marker != null) {
                    toiletMarkers.add(marker);
                }
            }
        }
    }


    /**
     * Updates the markers indicating accessible routes on the map.
     * This method clears any existing accessible route markers and adds new markers based on the
     * current floor and building.  The locations of accessible routes are hardcoded for the "nucleus"
     * building and specific floors.  If the current location doesn't correspond to a known accessible
     * route location, no markers are added.
     *
     * @param gMap The GoogleMap instance on which to display the markers.
     * @param currentFloor The current floor of the building.
     * @param currentBuilding The current building name.
     * @param accessibleRouteMarkers A list to store the Marker objects representing accessible routes.
     *                               Markers added in this method are appended to this list.  Markers
     *                               previously present in this list will be removed from the map and
     *                               cleared from the list.
     * @param context The application context.  Used for accessing resources (e.g., the marker icon).
     */
    public static void updateAccessibleRouteMarkers(
            GoogleMap gMap,
            int currentFloor,
            String currentBuilding,
            List<Marker> accessibleRouteMarkers,
            Context context
    ) {
        for (Marker marker : accessibleRouteMarkers) {
            marker.remove();
        }
        accessibleRouteMarkers.clear();

        List<LatLng> accessibleLocations = POILocationProvider.getLocations(
                "accessibleRoute", currentBuilding, currentFloor, context);

        if (accessibleLocations != null) {
            for (LatLng location : accessibleLocations) {
                Marker marker = MarkerFactory.createMarker(
                        gMap,
                        location,
                        "Accessible Route",
                        "accessibleRoute",
                        context
                );
                if (marker != null) {
                    accessibleRouteMarkers.add(marker);
                }
            }
        }
    }

}
