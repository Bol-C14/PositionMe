package com.openpositioning.PositionMe.data.local;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import com.openpositioning.PositionMe.Traj;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Simple repository for persisting {@link Traj.Trajectory} objects to the app's
 * private storage. The data is written using {@link TrajectoryIO}.
 */
public class FileRepository {
    private static final String TAG = "FileRepository";
    private final Context context;

    public FileRepository(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Save the trajectory to the app-specific documents directory.
     *
     * @param trajectory trajectory to persist
     * @return resulting file or {@code null} if failed
     */
    public File saveTrajectory(Traj.Trajectory trajectory) {
        File dir;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
            if (dir == null) {
                dir = context.getFilesDir();
            }
        } else {
            dir = context.getFilesDir();
        }
        if (dir != null && !dir.exists()) {
            dir.mkdirs();
        }
        String time = new SimpleDateFormat("dd-MM-yy-HH-mm-ss").format(new Date());
        File file = new File(dir, "trajectory_" + time + ".txt");
        try {
            TrajectoryIO.writeTrajectory(file, trajectory);
            Log.i(TAG, "Trajectory written to: " + file.getAbsolutePath());
            return file;
        } catch (Exception e) {
            Log.e(TAG, "Failed to save trajectory", e);
            return null;
        }
    }

    /**
     * Load a trajectory from a file.
     */
    public Traj.Trajectory readTrajectory(File file) throws Exception {
        return TrajectoryIO.readTrajectory(file);
    }
}
