package com.openpositioning.PositionMe.presentation.permissions;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PermissionPolicyTest {
    @Test
    public void requiredPermissionsMatchPlatformRuntimeGroups() {
        assertEquals(Arrays.asList(PermissionPolicy.FINE_LOCATION),
                PermissionPolicy.requiredPermissions(28));
        assertEquals(Arrays.asList(PermissionPolicy.FINE_LOCATION, PermissionPolicy.ACTIVITY_RECOGNITION),
                PermissionPolicy.requiredPermissions(29));
        assertEquals(Arrays.asList(PermissionPolicy.COARSE_LOCATION, PermissionPolicy.FINE_LOCATION,
                        PermissionPolicy.ACTIVITY_RECOGNITION),
                PermissionPolicy.requiredPermissions(31));
    }

    @Test
    public void optionalPermissionsAreVersionGatedAndSeparateFromRequired() {
        assertTrue(PermissionPolicy.optionalPermissions(30).isEmpty());
        assertEquals(Arrays.asList(PermissionPolicy.BLUETOOTH_SCAN, PermissionPolicy.BLUETOOTH_CONNECT),
                PermissionPolicy.optionalPermissions(31));
        assertEquals(Arrays.asList(PermissionPolicy.BLUETOOTH_SCAN, PermissionPolicy.BLUETOOTH_CONNECT,
                        PermissionPolicy.NEARBY_WIFI_DEVICES, PermissionPolicy.POST_NOTIFICATIONS),
                PermissionPolicy.optionalPermissions(33));
        assertFalse(PermissionPolicy.requiredPermissions(33)
                .contains(PermissionPolicy.POST_NOTIFICATIONS));
    }

    @Test
    public void computesMissingGrantsWithoutTurningOptionalDenialsIntoRequiredDenials() {
        Set<String> granted = new HashSet<>(Arrays.asList(
                PermissionPolicy.COARSE_LOCATION, PermissionPolicy.ACTIVITY_RECOGNITION,
                PermissionPolicy.BLUETOOTH_SCAN));
        assertEquals(Collections.singletonList(PermissionPolicy.FINE_LOCATION),
                PermissionPolicy.missingRequiredPermissions(31, granted));
        assertEquals(Arrays.asList(PermissionPolicy.BLUETOOTH_CONNECT,
                        PermissionPolicy.NEARBY_WIFI_DEVICES, PermissionPolicy.POST_NOTIFICATIONS),
                PermissionPolicy.missingOptionalPermissions(33, granted));
    }

    @Test
    public void preciseLocationUpgradeRequestsCoarseAndFineTogetherWhenCoarseIsAlreadyGranted() {
        Set<String> approximateGrant = new HashSet<>(Arrays.asList(
                PermissionPolicy.COARSE_LOCATION, PermissionPolicy.ACTIVITY_RECOGNITION));
        assertEquals(Arrays.asList(PermissionPolicy.COARSE_LOCATION, PermissionPolicy.FINE_LOCATION),
                PermissionPolicy.permissionsToRequest(31, approximateGrant));
        assertEquals(Collections.singletonList(PermissionPolicy.FINE_LOCATION),
                PermissionPolicy.permissionsToRequest(30, approximateGrant));
    }

    @Test
    public void settingsActionRequiresPriorRequestAndNoRationale() {
        Set<String> requested = Collections.singleton(PermissionPolicy.FINE_LOCATION);
        assertFalse(PermissionPolicy.shouldOpenSettings(PermissionPolicy.FINE_LOCATION,
                Collections.emptySet(), Collections.emptySet()));
        assertFalse(PermissionPolicy.shouldOpenSettings(PermissionPolicy.FINE_LOCATION,
                requested, requested));
        assertTrue(PermissionPolicy.shouldOpenSettings(PermissionPolicy.FINE_LOCATION,
                requested, Collections.emptySet()));
        assertEquals(Collections.singletonList(PermissionPolicy.FINE_LOCATION),
                PermissionPolicy.permanentlyDenied(Arrays.asList(PermissionPolicy.FINE_LOCATION,
                                PermissionPolicy.ACTIVITY_RECOGNITION), requested, Collections.emptySet()));
    }
}
