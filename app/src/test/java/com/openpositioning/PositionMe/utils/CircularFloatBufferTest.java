package com.openpositioning.PositionMe.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Optional;

import org.junit.Test;

public class CircularFloatBufferTest {
    @Test
    public void emptyBufferBecomesFullAndReturnsValuesInInsertionOrder() {
        CircularFloatBuffer buffer = new CircularFloatBuffer(3);

        assertTrue(buffer.isEmpty());
        assertFalse(buffer.isFull());
        assertEquals(0, buffer.getCurrentSize());
        assertEquals(Optional.empty(), buffer.getOldest());

        buffer.putNewest(1.25f);
        buffer.putNewest(-2.5f);
        buffer.putNewest(8.0f);

        assertFalse(buffer.isEmpty());
        assertTrue(buffer.isFull());
        assertEquals(3, buffer.getCurrentSize());
        assertEquals(Arrays.asList(1.25f, -2.5f, 8.0f), buffer.getListCopy());
        assertEquals(Optional.of(1.25f), buffer.getOldest());
        assertEquals(Optional.of(-2.5f), buffer.getOldest());
        assertEquals(Optional.of(8.0f), buffer.getOldest());
        assertTrue(buffer.isEmpty());
    }

    @Test
    public void copyTracksOldestValueAfterReadingAndWritingAroundRing() {
        CircularFloatBuffer buffer = new CircularFloatBuffer(3);
        buffer.putNewest(1.0f);
        buffer.putNewest(2.0f);
        buffer.putNewest(3.0f);

        assertEquals(Optional.of(1.0f), buffer.getOldest());
        buffer.putNewest(4.0f);

        assertEquals(Arrays.asList(2.0f, 3.0f, 4.0f), buffer.getListCopy());
        assertEquals(3, buffer.getCurrentSize());
    }

    @Test
    public void listCopyIsUnavailableUntilBufferIsFull() {
        CircularFloatBuffer buffer = new CircularFloatBuffer(2);
        buffer.putNewest(1.0f);

        assertNull(buffer.getListCopy());
    }
}
