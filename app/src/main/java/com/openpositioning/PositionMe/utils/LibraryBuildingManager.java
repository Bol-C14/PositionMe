package com.openpositioning.PositionMe.utils;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.presentation.fragment.IndoorMapFragment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Building manager providing overlay data for the Library building.
 */
public class LibraryBuildingManager implements BuildingOverlayProvider {
    private IndoorMapFragment indoorMapFragment;
    private ArrayList<LatLng> buildingPolygon;
    private final List<Integer> floorResources = Arrays.asList(
            R.drawable.libraryg,
            R.drawable.library1,
            R.drawable.library2,
            R.drawable.library3
    );

    private static final float FLOOR_HEIGHT = 3.6f;
    private static final int DEFAULT_FLOOR = 0;

    public LibraryBuildingManager(GoogleMap map) {
        indoorMapFragment = new IndoorMapFragment(map, 4);

        double N1 = 55.92281045664704;
        double W1 = 3.175184089079065;
        double N2 = 55.92306692576906;
        double W2 = 3.174771893078224;

        buildingPolygon = new ArrayList<>();
        buildingPolygon.add(new LatLng(N1, -W1));
        buildingPolygon.add(new LatLng(N1, -W2));
        buildingPolygon.add(new LatLng(N2, -W2));
        buildingPolygon.add(new LatLng(N2, -W1));

        indoorMapFragment.addFloor(0, R.drawable.libraryg, new LatLngBounds(buildingPolygon.get(0), buildingPolygon.get(2)));
        indoorMapFragment.addFloor(1, R.drawable.library1, new LatLngBounds(buildingPolygon.get(0), buildingPolygon.get(2)));
        indoorMapFragment.addFloor(2, R.drawable.library2, new LatLngBounds(buildingPolygon.get(0), buildingPolygon.get(2)));
        indoorMapFragment.addFloor(3, R.drawable.library3, new LatLngBounds(buildingPolygon.get(0), buildingPolygon.get(2)));
    }

    public IndoorMapFragment getIndoorMapManager() {
        return indoorMapFragment;
    }

    private boolean isPointInBuilding(LatLng point) {
        int intersectCount = 0;
        for (int j = 0; j < buildingPolygon.size(); j++) {
            LatLng vertA = buildingPolygon.get(j);
            LatLng vertB = buildingPolygon.get((j + 1) % buildingPolygon.size());
            if (rayCastIntersect(point, vertA, vertB)) {
                intersectCount++;
            }
        }
        return ((intersectCount % 2) == 1);
    }

    private boolean rayCastIntersect(LatLng point, LatLng vertA, LatLng vertB) {
        double aY = vertA.latitude;
        double bY = vertB.latitude;
        double aX = vertA.longitude;
        double bX = vertB.longitude;
        double pY = point.latitude;
        double pX = point.longitude;
        if ((aY > pY && bY > pY) || (aY < pY && bY < pY) || (aX < pX && bX < pX)) {
            return false;
        }
        double m = (aY - bY) / (aX - bX);
        double bee = -aX * m + aY;
        double x = (pY - bee) / m;
        return x > pX;
    }

    // BuildingOverlayProvider implementation
    @Override
    public String getName() {
        return "library";
    }

    @Override
    public boolean contains(LatLng point) {
        return isPointInBuilding(point);
    }

    @Override
    public List<Integer> getFloorResources() {
        return floorResources;
    }

    @Override
    public LatLngBounds getBounds() {
        return new LatLngBounds(buildingPolygon.get(0), buildingPolygon.get(2));
    }

    @Override
    public float getFloorHeight() {
        return FLOOR_HEIGHT;
    }

    @Override
    public int getDefaultFloor() {
        return DEFAULT_FLOOR;
    }
}
