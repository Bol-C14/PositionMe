package com.openpositioning.PositionMe.data.local;

import static org.junit.Assert.assertEquals;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

public class TrajParserNormalizationTest {
    @Test
    public void flatAndNestedImuShapesPreserveTimestampAndSensorValues() {
        TrajParser.ImuRecord legacy = TrajParser.normalizeImuRecord(json(
                "{\"relativeTimestamp\":1234,"
                        + "\"accX\":1.25,\"accY\":-2.5,\"accZ\":9.8,"
                        + "\"gyrX\":0.1,\"gyrY\":0.2,\"gyrZ\":-0.3,"
                        + "\"rotationVectorX\":0.4,\"rotationVectorY\":0.5,"
                        + "\"rotationVectorZ\":0.6,\"rotationVectorW\":0.7}"));
        TrajParser.ImuRecord current = TrajParser.normalizeImuRecord(json(
                "{\"relativeTimestamp\":\"1234\","
                        + "\"acc\":{\"x\":1.25,\"y\":-2.5,\"z\":9.8},"
                        + "\"gyr\":{\"x\":0.1,\"y\":0.2,\"z\":-0.3},"
                        + "\"rotationVector\":{\"x\":0.4,\"y\":0.5,\"z\":0.6,\"w\":0.7}}"));

        assertEquals(1234L, legacy.relativeTimestamp);
        assertEquals(legacy.relativeTimestamp, current.relativeTimestamp);
        assertEquals(1.25f, legacy.accX, 0.0f);
        assertEquals(-2.5f, legacy.accY, 0.0f);
        assertEquals(9.8f, legacy.accZ, 1e-6f);
        assertEquals(0.1f, legacy.gyrX, 1e-6f);
        assertEquals(0.2f, legacy.gyrY, 1e-6f);
        assertEquals(-0.3f, legacy.gyrZ, 1e-6f);
        assertEquals(0.4f, legacy.rotationVectorX, 1e-6f);
        assertEquals(0.5f, legacy.rotationVectorY, 1e-6f);
        assertEquals(0.6f, legacy.rotationVectorZ, 1e-6f);
        assertEquals(0.7f, legacy.rotationVectorW, 1e-6f);
        assertImuValuesEqual(legacy, current);
    }

    @Test
    public void flatAndNestedGnssShapesPreserveCoordinatesAndStringTimestamp() {
        TrajParser.GnssRecord legacy = TrajParser.normalizeGnssRecord(json(
                "{\"relativeTimestamp\":1700000000123,"
                        + "\"latitude\":51.501234,\"longitude\":-0.141234}"));
        TrajParser.GnssRecord current = TrajParser.normalizeGnssRecord(json(
                "{\"position\":{\"relativeTimestamp\":\"1700000000123\","
                        + "\"latitude\":51.501234,\"longitude\":-0.141234}}"));

        assertEquals(1_700_000_000_123L, legacy.relativeTimestamp);
        assertEquals(legacy.relativeTimestamp, current.relativeTimestamp);
        assertEquals(51.501234, legacy.latitude, 1e-10);
        assertEquals(-0.141234, legacy.longitude, 1e-10);
        assertEquals(legacy.latitude, current.latitude, 0.0);
        assertEquals(legacy.longitude, current.longitude, 0.0);
    }

    @Test
    public void nestedGnssCoordinatesUseOuterTimestampWhenPositionOmitsIt() {
        TrajParser.GnssRecord current = TrajParser.normalizeGnssRecord(json(
                "{\"relativeTimestamp\":\"9876543210\","
                        + "\"position\":{\"latitude\":-33.86,\"longitude\":151.21}}"));

        assertEquals(9_876_543_210L, current.relativeTimestamp);
        assertEquals(-33.86, current.latitude, 1e-10);
        assertEquals(151.21, current.longitude, 1e-10);
    }

    private static JsonObject json(String value) {
        return new JsonParser().parse(value).getAsJsonObject();
    }

    private static void assertImuValuesEqual(TrajParser.ImuRecord expected, TrajParser.ImuRecord actual) {
        assertEquals(expected.accX, actual.accX, 0.0f);
        assertEquals(expected.accY, actual.accY, 0.0f);
        assertEquals(expected.accZ, actual.accZ, 0.0f);
        assertEquals(expected.gyrX, actual.gyrX, 0.0f);
        assertEquals(expected.gyrY, actual.gyrY, 0.0f);
        assertEquals(expected.gyrZ, actual.gyrZ, 0.0f);
        assertEquals(expected.rotationVectorX, actual.rotationVectorX, 0.0f);
        assertEquals(expected.rotationVectorY, actual.rotationVectorY, 0.0f);
        assertEquals(expected.rotationVectorZ, actual.rotationVectorZ, 0.0f);
        assertEquals(expected.rotationVectorW, actual.rotationVectorW, 0.0f);
    }
}
