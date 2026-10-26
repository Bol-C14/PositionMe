package com.openpositioning.PositionMe.presentation.permissions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** Pure-Java permission mapping and denial policy, kept independent of Android framework classes. */
public final class PermissionPolicy {
    public static final String FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION";
    public static final String COARSE_LOCATION = "android.permission.ACCESS_COARSE_LOCATION";
    public static final String ACTIVITY_RECOGNITION = "android.permission.ACTIVITY_RECOGNITION";
    public static final String BLUETOOTH_SCAN = "android.permission.BLUETOOTH_SCAN";
    public static final String BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT";
    public static final String POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS";
    public static final String NEARBY_WIFI_DEVICES = "android.permission.NEARBY_WIFI_DEVICES";

    private static final int API_Q = 29;
    private static final int API_S = 31;
    private static final int API_T = 33;

    private PermissionPolicy() { }

    /** Fine location is required for recording; Android 12+ requires coarse and fine together. */
    public static List<String> requiredPermissions(int apiLevel) {
        List<String> permissions = new ArrayList<>();
        if (apiLevel >= API_S) permissions.add(COARSE_LOCATION);
        permissions.add(FINE_LOCATION);
        if (apiLevel >= API_Q) permissions.add(ACTIVITY_RECOGNITION);
        return Collections.unmodifiableList(permissions);
    }

    /** Radio and notification permissions are optional enhancements to a recording. */
    public static List<String> optionalPermissions(int apiLevel) {
        List<String> permissions = new ArrayList<>();
        if (apiLevel >= API_S) {
            permissions.add(BLUETOOTH_SCAN);
            permissions.add(BLUETOOTH_CONNECT);
        }
        if (apiLevel >= API_T) {
            permissions.add(NEARBY_WIFI_DEVICES);
            permissions.add(POST_NOTIFICATIONS);
        }
        return Collections.unmodifiableList(permissions);
    }

    public static List<String> missingRequiredPermissions(int apiLevel, Set<String> granted) {
        return missing(requiredPermissions(apiLevel), granted);
    }

    /**
     * Returns the request set for an explicit recording action. On API 31+, upgrading an
     * approximate grant to precise must request both location permissions in one request,
     * even though coarse location is already granted.
     */
    public static List<String> permissionsToRequest(int apiLevel, Set<String> granted) {
        List<String> result = new ArrayList<>();
        boolean fineMissing = !granted.contains(FINE_LOCATION);
        if (fineMissing && apiLevel >= API_S) {
            result.add(COARSE_LOCATION);
            result.add(FINE_LOCATION);
        } else if (fineMissing) {
            result.add(FINE_LOCATION);
        } else if (apiLevel >= API_S && !granted.contains(COARSE_LOCATION)) {
            result.add(COARSE_LOCATION);
        }
        if (apiLevel >= API_Q && !granted.contains(ACTIVITY_RECOGNITION)) {
            result.add(ACTIVITY_RECOGNITION);
        }
        return Collections.unmodifiableList(result);
    }

    public static List<String> missingOptionalPermissions(int apiLevel, Set<String> granted) {
        return missing(optionalPermissions(apiLevel), granted);
    }

    /**
     * Android does not expose a direct permanent-denial bit. Once a permission was requested,
     * a subsequent denial with no rationale is treated as requiring app Settings.
     */
    public static boolean shouldOpenSettings(String permission, Set<String> previouslyRequested,
                                             Set<String> rationaleRequired) {
        return previouslyRequested.contains(permission) && !rationaleRequired.contains(permission);
    }

    public static List<String> permanentlyDenied(List<String> denied,
                                                  Set<String> previouslyRequested,
                                                  Set<String> rationaleRequired) {
        List<String> result = new ArrayList<>();
        for (String permission : denied) {
            if (shouldOpenSettings(permission, previouslyRequested, rationaleRequired)) {
                result.add(permission);
            }
        }
        return Collections.unmodifiableList(result);
    }

    private static List<String> missing(List<String> requested, Set<String> granted) {
        List<String> result = new ArrayList<>();
        for (String permission : requested) {
            if (!granted.contains(permission)) result.add(permission);
        }
        return Collections.unmodifiableList(result);
    }
}
