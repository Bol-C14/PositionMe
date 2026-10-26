package com.openpositioning.PositionMe.presentation.fragment;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavDirections;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.button.MaterialButton;

import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.data.remote.ServerCommunications;
import com.openpositioning.PositionMe.domain.recording.DurableSessionState;
import com.openpositioning.PositionMe.domain.recording.ExportStatus;
import com.openpositioning.PositionMe.domain.recording.RecoveryReport;
import com.openpositioning.PositionMe.domain.recording.SessionSummary;
import com.openpositioning.PositionMe.presentation.viewitems.TrajDownloadViewHolder;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.sensors.Observer;
import com.openpositioning.PositionMe.presentation.viewitems.TrajDownloadListAdapter;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.Date;
import java.text.DateFormat;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A simple {@link Fragment} subclass. The files fragments displays a list of trajectories already
 * uploaded with some metadata, and enabled re-downloading them to the device's local storage.
 *
 * @see UploadFragment sub-menu for uploading recordings that failed during recording.
 * @see com.openpositioning.PositionMe.Traj the data structure sent and received.
 * @see ServerCommunications the class handling communication with the server.
 *
 * @author Mate Stodulka
 */
public class FilesFragment extends Fragment implements Observer {

    // UI elements
    private RecyclerView filesList;
    private TrajDownloadListAdapter listAdapter;
    private CardView uploadCard;
    private RecyclerView localSessionsList;
    private TextView localSessionsEmpty;
    private LocalSessionsAdapter localSessionsAdapter;
    private SensorFusion sensorFusion;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Set<UUID> recoveringSessions = new HashSet<>();
    private final Set<UUID> exportingSessions = new HashSet<>();
    private int viewGeneration;

    // Class handling HTTP communication
    private ServerCommunications serverCommunications;

    /**
     * Default public constructor, empty.
     */
    public FilesFragment() {
        // Required empty public constructor
    }

    /**
     * {@inheritDoc}
     * Initialise the server communication class and register the FilesFragment as an Observer to
     * receive the async http responses.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sensorFusion = SensorFusion.getInstance();
    }

    /**
     * {@inheritDoc}
     * Sets the title in the action bar.
     */
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View rootView = inflater.inflate(R.layout.fragment_files, container, false);
        getActivity().setTitle("Trajectory recordings");
        return rootView;
    }

    /**
     * {@inheritDoc}
     * Unregisters the observer from the server communications class.
     */
    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        serverCommunications = new ServerCommunications(context);
        serverCommunications.registerObserver(this);
    }

    @Override
    public void onDestroyView() {
        viewGeneration++;
        filesList = null;
        localSessionsList = null;
        localSessionsEmpty = null;
        localSessionsAdapter = null;
        uploadCard = null;
        super.onDestroyView();
    }

    /**
     * {@inheritDoc}
     * Initialises UI elements, including a navigation card to the {@link UploadFragment} and a
     * RecyclerView displaying online trajectories.
     *
     * @see TrajDownloadViewHolder the View Holder for the list.
     * @see TrajDownloadListAdapter the list adapter for displaying the recycler view.
     * @see com.openpositioning.PositionMe.R.layout#item_trajectorycard_view the elements in the list.
     */
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewGeneration++;

        filesList = view.findViewById(R.id.filesList);
        localSessionsList = view.findViewById(R.id.localSessionsList);
        localSessionsEmpty = view.findViewById(R.id.localSessionsEmpty);
        localSessionsAdapter = new LocalSessionsAdapter();
        localSessionsList.setLayoutManager(new LinearLayoutManager(requireContext()));
        localSessionsList.setAdapter(localSessionsAdapter);
        loadLocalSessions();
        uploadCard = view.findViewById(R.id.uploadCard);
        uploadCard.setOnClickListener(new View.OnClickListener() {
            /**
             * {@inheritDoc}
             * Navigates to {@link UploadFragment}.
             */
            @Override
            public void onClick(View view) {
                NavDirections action = FilesFragmentDirections.actionFilesFragmentToUploadFragment();
                Navigation.findNavController(view).navigate(action);
            }
        });
        serverCommunications.sendInfoRequest();
        // Force RecyclerView refresh to ensure icon states are correct
        int generation = viewGeneration;
        mainHandler.postDelayed(() -> {
            if (generation == viewGeneration && filesList != null && filesList.getAdapter() != null) {
                filesList.getAdapter().notifyDataSetChanged();
            }
        }, 500);
    }

    private void loadLocalSessions() {
        final int generation = viewGeneration;
        final LocalSessionsAdapter targetAdapter = localSessionsAdapter;
        sensorFusion.listRecordingSessionsAsync().whenComplete((sessions, failure) ->
                mainHandler.post(() -> {
                    if (!isAdded() || generation != viewGeneration || getView() == null
                            || targetAdapter == null || targetAdapter != localSessionsAdapter
                            || localSessionsEmpty == null) return;
                    if (failure != null || sessions == null) {
                        targetAdapter.setSessions(Collections.emptyList());
                        localSessionsEmpty.setText(R.string.saved_sessions_load_failed);
                        localSessionsEmpty.setVisibility(View.VISIBLE);
                        return;
                    }
                    targetAdapter.setSessions(sessions);
                    localSessionsEmpty.setVisibility(sessions.isEmpty() ? View.VISIBLE : View.GONE);
                    if (sessions.isEmpty()) localSessionsEmpty.setText(R.string.saved_sessions_empty);
                }));
    }

    private void recoverSession(UUID id) {
        if (!recoveringSessions.add(id)) return;
        if (localSessionsAdapter != null) localSessionsAdapter.notifySessionChanged(id);
        final int generation = viewGeneration;
        Toast.makeText(requireContext(), R.string.recovering_session, Toast.LENGTH_SHORT).show();
        sensorFusion.recoverRecordingSessionAsync(id).whenComplete((summary, failure) ->
                mainHandler.post(() -> {
                    recoveringSessions.remove(id);
                    if (!isAdded() || generation != viewGeneration || getView() == null) return;
                    if (localSessionsAdapter != null) localSessionsAdapter.notifySessionChanged(id);
                    if (failure == null && summary != null) {
                        int message = sensorFusion.hasRecoveredTrajectoryCheckpoint()
                                ? R.string.recovered_session_ready : R.string.recovered_json_only;
                        Toast.makeText(requireContext(), message,
                                Toast.LENGTH_LONG).show();
                        // Recovery exposes retained data for export. The live correction screen
                        // still depends on this process's GNSS/PDR context, which is not recovered.
                    } else {
                        Toast.makeText(requireContext(), R.string.recovery_failed, Toast.LENGTH_LONG).show();
                    }
                    if (getView() != null) loadLocalSessions();
                }));
    }

    private void exportSession(UUID id) {
        if (!exportingSessions.add(id)) return;
        if (localSessionsAdapter != null) localSessionsAdapter.notifySessionChanged(id);
        final int generation = viewGeneration;
        Toast.makeText(requireContext(), R.string.exporting_session, Toast.LENGTH_SHORT).show();
        sensorFusion.exportRecordingSessionAsync(id).whenComplete((file, failure) ->
                mainHandler.post(() -> {
                    exportingSessions.remove(id);
                    if (!isAdded() || generation != viewGeneration || getView() == null) return;
                    if (localSessionsAdapter != null) localSessionsAdapter.notifySessionChanged(id);
                    if (failure == null) {
                        Toast.makeText(requireContext(), R.string.export_complete, Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(requireContext(), R.string.export_failed_brief, Toast.LENGTH_LONG).show();
                    }
                    loadLocalSessions();
                }));
    }

    private final class LocalSessionsAdapter extends RecyclerView.Adapter<LocalSessionHolder> {
        private List<SessionSummary> sessions = Collections.emptyList();

        void setSessions(List<SessionSummary> sessions) {
            this.sessions = sessions == null ? Collections.emptyList() : sessions;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public LocalSessionHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout content = new LinearLayout(parent.getContext());
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(16, 12, 16, 12);
            CardView card = new CardView(parent.getContext());
            card.setRadius(16f);
            card.setCardElevation(3f);
            card.setUseCompatPadding(true);
            card.addView(content);
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMargins(0, 4, 0, 4);
            card.setLayoutParams(params);

            TextView title = new TextView(parent.getContext());
            title.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
            TextView detail = new TextView(parent.getContext());
            detail.setPadding(0, 4, 0, 4);
            detail.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            LinearLayout actions = new LinearLayout(parent.getContext());
            actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            MaterialButton recover = new MaterialButton(parent.getContext(), null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle);
            recover.setText(R.string.recover_session);
            MaterialButton export = new MaterialButton(parent.getContext());
            export.setText(R.string.export_session);
            actions.addView(recover);
            actions.addView(export);
            content.addView(title);
            content.addView(detail);
            content.addView(actions);
            return new LocalSessionHolder(card, title, detail, recover, export);
        }

        @Override
        public void onBindViewHolder(@NonNull LocalSessionHolder holder, int position) {
            SessionSummary summary = sessions.get(position);
            DurableSessionState state = summary.getState();
            int title = state == DurableSessionState.OPEN ? R.string.session_active
                    : state == DurableSessionState.INTERRUPTED ? R.string.session_incomplete
                    : state == DurableSessionState.CORRUPT ? R.string.session_corrupt
                    : state == DurableSessionState.FAILED ? R.string.session_failed
                    : state == DurableSessionState.RECOVERED ? R.string.session_recovered
                    : R.string.session_saved;
            holder.title.setText(title);
            String started = DateFormat.getDateTimeInstance().format(
                    new Date(summary.getMetadata().getWallStartEpochMillis()));
            String detail = started + " · " + getString(R.string.session_records, summary.getRecordCount());
            if (state == DurableSessionState.INTERRUPTED || state == DurableSessionState.RECOVERED) {
                detail += "\n" + getString(R.string.session_recovery_gap);
            }
            RecoveryReport report = summary.getRecoveryReport();
            if (report != null && report.hasLoss()) {
                String reason = report.getReason();
                detail += "\n" + getString(R.string.session_recovery_loss,
                        reason == null || reason.trim().isEmpty() ? getString(R.string.unknown_recovery_loss) : reason);
            }
            if (summary.getExportStatus() == ExportStatus.FAILED) {
                String exportFailure = summary.getExportFailure();
                if (exportFailure != null && !exportFailure.trim().isEmpty()) {
                    detail += "\n" + getString(R.string.session_export_failure, exportFailure);
                }
            }
            if (state == DurableSessionState.FAILED) {
                String recordingFailure = summary.getFailure();
                if (recordingFailure != null && !recordingFailure.trim().isEmpty()) {
                    detail += "\n" + getString(R.string.session_recording_failure, recordingFailure);
                }
            }
            holder.detail.setText(detail);
            boolean recoverable = state == DurableSessionState.INTERRUPTED
                    || state == DurableSessionState.FAILED;
            holder.recover.setVisibility(recoverable ? View.VISIBLE : View.GONE);
            holder.recover.setEnabled(!recoveringSessions.contains(summary.getId()));
            holder.recover.setOnClickListener(v -> recoverSession(summary.getId()));
            ExportStatus exportStatus = summary.getExportStatus();
            boolean exportable = state == DurableSessionState.SAVED
                    || state == DurableSessionState.RECOVERED;
            boolean exportInProgress = exportingSessions.contains(summary.getId());
            holder.export.setText(exportInProgress ? R.string.exporting_session
                    : exportStatus == ExportStatus.FAILED || exportStatus == ExportStatus.PENDING
                    ? R.string.retry_export : exportStatus == ExportStatus.EXPORTED
                    ? R.string.exported_session : R.string.export_session);
            holder.export.setEnabled(exportable && !exportInProgress);
            holder.export.setOnClickListener(v -> exportSession(summary.getId()));
        }

        @Override
        public int getItemCount() { return sessions.size(); }

        void notifySessionChanged(UUID id) {
            for (int i = 0; i < sessions.size(); i++) {
                if (sessions.get(i).getId().equals(id)) {
                    notifyItemChanged(i);
                    return;
                }
            }
        }
    }

    private static final class LocalSessionHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView detail;
        final MaterialButton recover;
        final MaterialButton export;

        LocalSessionHolder(@NonNull View itemView, TextView title, TextView detail,
                           MaterialButton recover, MaterialButton export) {
            super(itemView);
            this.title = title;
            this.detail = detail;
            this.recover = recover;
            this.export = export;
        }
    }

    /**
     * {@inheritDoc}
     * Called by {@link ServerCommunications} when the response to the HTTP info request is received.
     *
     * @param singletonStringList   a single string wrapped in an object array containing the http
     *                              response from the server.
     */
    @Override
    public void update(Object[] singletonStringList) {
        // Cast input as a string
        String infoString = (String) singletonStringList[0];
        // Check if the string is non-null and non-empty before processing
        if(infoString != null && !infoString.isEmpty()) {
            final int generation = viewGeneration;
            // Process string
            List<Map<String, String>> entryList = processInfoResponse(infoString);
            // Start a handler to be able to modify UI elements
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    // Update the RecyclerView with data from the server
                    if (generation == viewGeneration) updateView(entryList);
                }
            });
        }
    }

    /**
     * Parses the info response string from the HTTP communication.
     * Process the data using the Json library and return the matching Java data structure as a
     * List of Maps of \<String, String\>. Throws a JSONException if the data is not valid.
     *
     * @param infoString    HTTP info request response as a single string
     * @return              List of Maps of String to String containing ID, owner ID, and date.
     */
    private List<Map<String, String>> processInfoResponse(String infoString) {
        // Initialise empty list
        List<Map<String, String>> entryList = new ArrayList<>();
        try {
            // Attempt to decode using known JSON pattern
            JSONArray jsonArray = new JSONArray(infoString);
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject trajectoryEntry = jsonArray.getJSONObject(i);
                Map<String, String> entryMap = new HashMap<>();
                entryMap.put("owner_id", String.valueOf(trajectoryEntry.get("owner_id")));
                entryMap.put("date_submitted", (String) trajectoryEntry.get("date_submitted"));
                entryMap.put("id", String.valueOf(trajectoryEntry.get("id")));
                // Add decoded map to list of entries
                entryList.add(entryMap);
            }
        } catch (JSONException e) {
            System.err.println("JSON reading failed");
            e.printStackTrace();
        }
        // Sort the list by the ID fields of the maps
        entryList.sort(Comparator.comparing(m -> Integer.parseInt(m.get("id")), Comparator.nullsLast(Comparator.naturalOrder())));
        return entryList;
    }

    /**
     * Update the RecyclerView in the FilesFragment with new data.
     * Must be called from a UI thread. Initialises a new Layout Manager, and passes it to the
     * RecyclerView. Initialises a {@link TrajDownloadListAdapter} with the input array and setting
     * up a listener so that trajectories are downloaded when clicked, and a pop-up message is
     * displayed to notify the user.
     *
     * @param entryList List of Maps of String to String containing metadata about the uploaded
     *                  trajectories (ID, owner ID, date).
     */
    private void updateView(List<Map<String, String>> entryList) {
        if (isAdded() && getView() != null && filesList != null) {
            LinearLayoutManager manager = new LinearLayoutManager(requireContext());
            filesList.setLayoutManager(manager);
            filesList.setHasFixedSize(true);
            listAdapter = new TrajDownloadListAdapter(requireContext(), entryList, position -> {
                Map<String, String> selectedItem = entryList.get(position);
                serverCommunications.downloadTrajectory(position, selectedItem.get("id"), selectedItem.get("date_submitted"));
            });
            filesList.setAdapter(listAdapter);
            listAdapter.notifyDataSetChanged();
        }
    }
}
