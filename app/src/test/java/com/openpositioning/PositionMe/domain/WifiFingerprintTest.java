package com.openpositioning.PositionMe.domain;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class WifiFingerprintTest {
    @Test public void fingerprintOwnsItsObservationList() {
        List<WifiFingerprint.AccessPoint> source = new ArrayList<>();
        source.add(new WifiFingerprint.AccessPoint("ap-1", -65));
        WifiFingerprint fingerprint = new WifiFingerprint(10L, source);
        source.clear();
        assertEquals(1, fingerprint.getAccessPoints().size());
        assertEquals(-65, fingerprint.getAccessPoints().get(0).getRssiDbm());
        assertThrows(UnsupportedOperationException.class, () -> fingerprint.getAccessPoints().clear());
    }
}
