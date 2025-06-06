package com.openpositioning.PositionMe.data.local;

import com.google.protobuf.util.JsonFormat;
import com.openpositioning.PositionMe.Traj;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Utility class for reading and writing {@link Traj.Trajectory} files.
 * <p>
 * The trajectories are stored as JSON text files. Use
 * {@link #writeTrajectory(File, Traj.Trajectory)} to persist a trajectory and
 * {@link #readTrajectory(File)} to load it back.
 */
public class TrajectoryIO {

    /**
     * Writes the given trajectory to a file in JSON format.
     *
     * @param file destination file
     * @param traj trajectory to write
     * @throws IOException if writing fails
     */
    public static void writeTrajectory(File file, Traj.Trajectory traj) throws IOException {
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(JsonFormat.printer().print(traj));
            writer.flush();
        }
    }

    /**
     * Reads a trajectory from a JSON file.
     *
     * @param file source file
     * @return parsed trajectory
     * @throws IOException if reading fails
     */
    public static Traj.Trajectory readTrajectory(File file) throws IOException {
        String json = new String(Files.readAllBytes(file.toPath()));
        Traj.Trajectory.Builder builder = Traj.Trajectory.newBuilder();
        JsonFormat.parser().merge(json, builder);
        return builder.build();
    }
}
