package com.openpositioning.PositionMe.data.local;

/** Factory for creating trajectory parsers. */
public final class TrajectoryParserFactory {
    private TrajectoryParserFactory() {}

    public static TrajectoryParser createJsonParser() {
        return new JsonTrajectoryParser();
    }
}
