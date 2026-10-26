package com.openpositioning.PositionMe.data.remote;

import org.junit.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class RemoteTrajectoryCatalogParserTest {
    @Test public void parsesAndSortsValidEntries() throws Exception {
        List<RemoteTrajectorySummary> entries = RemoteTrajectoryCatalogParser.parse(
                "[{\"id\":\"9\",\"owner_id\":2,\"date_submitted\":\"2026-01-02T03:04:05\"},"
                        + "{\"id\":\"3\",\"owner_id\":1,\"date_submitted\":\"2026-01-01T03:04:05\"}]");
        assertEquals(2, entries.size());
        assertEquals("3", entries.get(0).getId());
        assertEquals("1", entries.get(0).getOwnerId());
        assertEquals("9", entries.get(1).getId());
    }

    @Test public void emptyArrayIsAValidEmptyCatalog() throws Exception {
        assertEquals(0, RemoteTrajectoryCatalogParser.parse("[]").size());
    }

    @Test public void malformedResponseFailsInsteadOfBecomingAnEmptyCatalog() throws Exception {
        try {
            RemoteTrajectoryCatalogParser.parse("not-json");
            fail("Expected malformed response to fail");
        } catch (IOException expected) {
            // Expected: callers can show retryable failure instead of a false empty state.
        }
    }

    @Test public void missingMetadataFailsInsteadOfDroppingAnEntry() throws Exception {
        try {
            RemoteTrajectoryCatalogParser.parse("[{\"id\":\"1\"}]");
            fail("Expected incomplete entry to fail");
        } catch (IOException expected) {
            // Expected.
        }
    }
}
