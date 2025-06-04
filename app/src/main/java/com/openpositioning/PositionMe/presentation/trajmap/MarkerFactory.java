package com.openpositioning.PositionMe.presentation.trajmap;

import android.content.Context;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.utils.UtilFunctions;

import org.json.JSONObject;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Factory class responsible for creating map markers using icon information
 * loaded from a JSON configuration file.
 */
public class MarkerFactory {
    private static Map<String, Integer> iconMap;

    private static void ensureIconsLoaded(Context context) {
        if (iconMap != null) return;
        iconMap = new HashMap<>();
        try {
            InputStream is = context.getResources().openRawResource(R.raw.marker_icons);
            byte[] buffer = new byte[is.available()];
            int read = is.read(buffer);
            is.close();
            if (read > 0) {
                JSONObject obj = new JSONObject(new String(buffer));
                Iterator<String> keys = obj.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    String drawableName = obj.getString(key);
                    int resId = context.getResources().getIdentifier(drawableName, "drawable", context.getPackageName());
                    if (resId != 0) {
                        iconMap.put(key, resId);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static Marker createMarker(GoogleMap map,
                                      LatLng location,
                                      String title,
                                      String iconKey,
                                      Context context) {
        ensureIconsLoaded(context);
        MarkerOptions options = new MarkerOptions()
                .position(location)
                .flat(true)
                .title(title);
        Integer resId = iconMap.get(iconKey);
        if (resId != null) {
            options.icon(BitmapDescriptorFactory.fromBitmap(
                    UtilFunctions.getBitmapFromVector(context, resId)));
        }
        return map.addMarker(options);
    }
}
