package com.openpositioning.PositionMe.presentation.fragment;

import android.content.Context;
import android.os.Bundle;
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
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavDirections;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.data.remote.ServerCommunications;
import com.openpositioning.PositionMe.data.remote.RemoteTrajectorySummary;
import com.openpositioning.PositionMe.domain.recording.DurableSessionState;
import com.openpositioning.PositionMe.domain.recording.ExportStatus;
import com.openpositioning.PositionMe.domain.recording.RecoveryReport;
import com.openpositioning.PositionMe.domain.recording.SessionSummary;
import com.openpositioning.PositionMe.presentation.files.FilesScreenState;
import com.openpositioning.PositionMe.presentation.files.FilesViewModel;
import com.openpositioning.PositionMe.presentation.files.FilesViewModel.OperationKind;
import com.openpositioning.PositionMe.presentation.files.FilesViewModel.OperationOutcome;
import com.openpositioning.PositionMe.presentation.files.FilesViewModel.OperationStatus;
import com.openpositioning.PositionMe.presentation.files.SensorFusionLocalSessionCommands;
import com.openpositioning.PositionMe.presentation.viewitems.TrajDownloadListAdapter;
import com.openpositioning.PositionMe.sensors.SensorFusion;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Offline-first catalog of saved sessions and explicitly requested remote trajectories. */
public class FilesFragment extends Fragment {
    private FilesViewModel viewModel;
    private ServerCommunications serverCommunications;
    private ServerCommunications.CatalogRequest catalogRequest;
    private long activeRequestGeneration = -1;
    private RecyclerView localSessionsList;
    private LocalSessionsAdapter localSessionsAdapter;
    private TextView localSessionsStatus;
    private MaterialButton localSessionsRetry;
    private RecyclerView filesList;
    private TrajDownloadListAdapter remoteAdapter;
    private final List<Map<String, String>> remoteEntriesBacking = new ArrayList<>();
    private TextView remoteStatus;
    private MaterialButton remoteLoadButton;

    public FilesFragment() { }

    @Override public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        serverCommunications = new ServerCommunications(context.getApplicationContext());
    }

    @Override public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        viewModel = new ViewModelProvider(this, new ViewModelProvider.Factory() {
            @NonNull @Override public <T extends androidx.lifecycle.ViewModel> T create(
                    @NonNull Class<T> modelClass) {
                return modelClass.cast(new FilesViewModel(
                        new SensorFusionLocalSessionCommands(SensorFusion.getInstance())));
            }
        }).get(FilesViewModel.class);
    }

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater,
                                                  @Nullable ViewGroup container,
                                                  @Nullable Bundle savedInstanceState) {
        requireActivity().setTitle(R.string.files_m3_title);
        return inflater.inflate(R.layout.fragment_files, container, false);
    }

    @Override public void onResume() {
        super.onResume();
        viewModel.refreshLocalSessions();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        localSessionsList = view.findViewById(R.id.localSessionsList);
        localSessionsStatus = view.findViewById(R.id.localSessionsStatus);
        localSessionsRetry = view.findViewById(R.id.localSessionsRetry);
        localSessionsAdapter = new LocalSessionsAdapter();
        localSessionsList.setLayoutManager(new LinearLayoutManager(requireContext()));
        localSessionsList.setAdapter(localSessionsAdapter);
        filesList = view.findViewById(R.id.filesList);
        filesList.setLayoutManager(new LinearLayoutManager(requireContext()));
        remoteStatus = view.findViewById(R.id.remoteCatalogStatus);
        remoteLoadButton = view.findViewById(R.id.remoteCatalogLoad);
        remoteLoadButton.setOnClickListener(v -> loadRemoteCatalog());
        CardView uploadCard = view.findViewById(R.id.uploadCard);
        uploadCard.setOnClickListener(v -> {
            NavDirections action = FilesFragmentDirections.actionFilesFragmentToUploadFragment();
            Navigation.findNavController(v).navigate(action);
        });
        viewModel.getState().observe(getViewLifecycleOwner(), this::render);
    }

    private void render(FilesViewModel.UiState state) {
        if (localSessionsAdapter == null || localSessionsStatus == null) return;
        localSessionsAdapter.setState(state);
        localSessionsRetry.setVisibility(state.localFailed ? View.VISIBLE : View.GONE);
        localSessionsRetry.setOnClickListener(v -> viewModel.refreshLocalSessions());
        if (state.localLoading) {
            localSessionsStatus.setText(R.string.files_m3_local_loading);
            localSessionsStatus.setVisibility(View.VISIBLE);
            localSessionsStatus.setOnClickListener(null);
        } else if (state.localFailed) {
            localSessionsStatus.setText(R.string.files_m3_local_failed);
            localSessionsStatus.setVisibility(View.VISIBLE);
            localSessionsStatus.setOnClickListener(null);
        } else if (state.sessions.isEmpty()) {
            localSessionsStatus.setText(R.string.files_m3_local_empty);
            localSessionsStatus.setVisibility(View.VISIBLE);
            localSessionsStatus.setOnClickListener(null);
        } else {
            localSessionsStatus.setVisibility(View.GONE);
            localSessionsStatus.setOnClickListener(null);
        }
        FilesScreenState.RemoteStatus remote = state.remote.getRemoteStatus();
        remoteLoadButton.setEnabled(remote != FilesScreenState.RemoteStatus.LOADING);
        remoteLoadButton.setText(remote == FilesScreenState.RemoteStatus.FAILED
                ? R.string.files_m3_retry_remote : remote == FilesScreenState.RemoteStatus.LOADING
                ? R.string.files_m3_loading_remote : R.string.files_m3_load_remote);
        switch (remote) {
            case NOT_LOADED:
                remoteStatus.setText(R.string.files_m3_remote_not_loaded);
                break;
            case LOADING:
                remoteStatus.setText(R.string.files_m3_remote_loading);
                break;
            case EMPTY:
                remoteStatus.setText(R.string.files_m3_remote_empty);
                break;
            case FAILED:
                remoteStatus.setText(R.string.files_m3_remote_failed);
                break;
            case LOADED:
                remoteStatus.setText(getString(R.string.files_m3_remote_count,
                        state.remoteEntries.size()));
                break;
        }
        if (remote == FilesScreenState.RemoteStatus.LOADED) {
            List<Map<String, String>> rows = toDownloadRows(state.remoteEntries);
            if (!remoteEntriesBacking.equals(rows)) {
                remoteEntriesBacking.clear();
                remoteEntriesBacking.addAll(rows);
                if (remoteAdapter != null) remoteAdapter.notifyDataSetChanged();
            }
            if (remoteAdapter == null) {
                remoteAdapter = new TrajDownloadListAdapter(requireContext(), remoteEntriesBacking,
                        position -> {
                            Map<String, String> selected = remoteEntriesBacking.get(position);
                            serverCommunications.downloadTrajectory(position, selected.get("id"),
                                    selected.get("date_submitted"));
                            Toast.makeText(requireContext(), R.string.files_m3_download_started,
                                    Toast.LENGTH_SHORT).show();
                        });
            }
            filesList.setAdapter(remoteAdapter);
            filesList.setVisibility(View.VISIBLE);
        } else {
            remoteEntriesBacking.clear();
            if (remoteAdapter != null) remoteAdapter.notifyDataSetChanged();
            filesList.setVisibility(View.GONE);
        }
    }

    private void loadRemoteCatalog() {
        if (catalogRequest != null) catalogRequest.cancel();
        long generation = viewModel.beginRemoteLoad();
        activeRequestGeneration = generation;
        catalogRequest = serverCommunications.loadRemoteCatalog(new ServerCommunications.CatalogCallback() {
            @Override public void onSuccess(List<RemoteTrajectorySummary> entries) {
                viewModel.remoteLoadSucceeded(generation, entries);
            }

            @Override public void onFailure(Exception failure) {
                viewModel.remoteLoadFailed(generation);
            }
        });
    }

    private static List<Map<String, String>> toDownloadRows(List<RemoteTrajectorySummary> entries) {
        List<Map<String, String>> rows = new ArrayList<>(entries.size());
        for (RemoteTrajectorySummary entry : entries) {
            Map<String, String> row = new java.util.HashMap<>();
            row.put("id", entry.getId());
            row.put("owner_id", entry.getOwnerId());
            row.put("date_submitted", entry.getDateSubmitted());
            rows.add(row);
        }
        return rows;
    }

    @Override public void onDestroyView() {
        if (catalogRequest != null) {
            catalogRequest.cancel();
            if (activeRequestGeneration >= 0) viewModel.cancelRemoteLoad(activeRequestGeneration);
            catalogRequest = null;
            activeRequestGeneration = -1;
        }
        if (remoteAdapter != null) remoteAdapter.dispose();
        remoteAdapter = null;
        remoteEntriesBacking.clear();
        localSessionsList = null;
        localSessionsStatus = null;
        localSessionsRetry = null;
        localSessionsAdapter = null;
        filesList = null;
        remoteStatus = null;
        remoteLoadButton = null;
        super.onDestroyView();
    }

    private final class LocalSessionsAdapter extends RecyclerView.Adapter<LocalSessionHolder> {
        private FilesViewModel.UiState state;

        void setState(FilesViewModel.UiState state) {
            this.state = state;
            notifyDataSetChanged();
        }

        @NonNull @Override public LocalSessionHolder onCreateViewHolder(@NonNull ViewGroup parent,
                                                                        int viewType) {
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
            LinearLayout actions = new LinearLayout(parent.getContext());
            actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            MaterialButton recover = new MaterialButton(parent.getContext(), null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle);
            recover.setText(R.string.files_m3_recover);
            MaterialButton export = new MaterialButton(parent.getContext());
            export.setText(R.string.files_m3_export);
            actions.addView(recover);
            actions.addView(export);
            content.addView(title);
            content.addView(detail);
            content.addView(actions);
            return new LocalSessionHolder(card, title, detail, recover, export);
        }

        @Override public void onBindViewHolder(@NonNull LocalSessionHolder holder, int position) {
            SessionSummary summary = state.sessions.get(position);
            DurableSessionState status = summary.getState();
            holder.title.setText(status == DurableSessionState.OPEN ? R.string.files_m3_session_active
                    : status == DurableSessionState.INTERRUPTED ? R.string.files_m3_session_incomplete
                    : status == DurableSessionState.CORRUPT ? R.string.files_m3_session_corrupt
                    : status == DurableSessionState.FAILED ? R.string.files_m3_session_failed
                    : status == DurableSessionState.RECOVERED ? R.string.files_m3_session_recovered
                    : R.string.files_m3_session_saved);
            String detail = DateFormat.getDateTimeInstance().format(
                    new Date(summary.getMetadata().getWallStartEpochMillis())) + " · "
                    + getString(R.string.files_m3_records, summary.getRecordCount());
            if (status == DurableSessionState.INTERRUPTED || status == DurableSessionState.RECOVERED) {
                detail += "\n" + getString(R.string.files_m3_recovery_gap);
            }
            RecoveryReport report = summary.getRecoveryReport();
            if (report != null && report.hasLoss()) {
                String reason = report.getReason();
                detail += "\n" + getString(R.string.files_m3_recovery_loss,
                        reason == null || reason.trim().isEmpty()
                                ? getString(R.string.files_m3_unknown_loss) : reason);
            }
            if (summary.getExportStatus() == ExportStatus.FAILED) {
                String reason = summary.getExportFailure();
                if (reason != null && !reason.trim().isEmpty()) {
                    detail += "\n" + getString(R.string.files_m3_export_failure, reason);
                }
            }
            OperationOutcome outcome = state.outcomes.get(summary.getId());
            if (outcome != null) {
                if (outcome.status == OperationStatus.PENDING) {
                    detail += "\n" + getString(outcome.kind == OperationKind.RECOVERY
                            ? R.string.files_m3_recovery_pending : R.string.files_m3_export_pending);
                } else if (outcome.status == OperationStatus.FAILED) {
                    detail += "\n" + getString(outcome.kind == OperationKind.RECOVERY
                            ? R.string.files_m3_recovery_error : R.string.files_m3_export_error,
                            outcome.message);
                } else {
                    detail += "\n" + getString(outcome.kind == OperationKind.RECOVERY
                            ? R.string.files_m3_recovery_complete : R.string.files_m3_export_complete);
                }
            }
            holder.detail.setText(detail);
            boolean recoverable = status == DurableSessionState.INTERRUPTED
                    || status == DurableSessionState.FAILED;
            holder.recover.setVisibility(recoverable ? View.VISIBLE : View.GONE);
            holder.recover.setEnabled(!state.recovering.contains(summary.getId()));
            holder.recover.setOnClickListener(v -> {
                viewModel.recover(summary.getId());
                Toast.makeText(requireContext(), R.string.files_m3_recovery_started,
                        Toast.LENGTH_SHORT).show();
            });
            boolean exportable = status == DurableSessionState.SAVED
                    || status == DurableSessionState.RECOVERED;
            boolean inProgress = state.exporting.contains(summary.getId());
            ExportStatus exportStatus = summary.getExportStatus();
            holder.export.setText(inProgress ? R.string.files_m3_exporting
                    : exportStatus == ExportStatus.FAILED || exportStatus == ExportStatus.PENDING
                    ? R.string.files_m3_retry_export : exportStatus == ExportStatus.EXPORTED
                    ? R.string.files_m3_exported : R.string.files_m3_export);
            holder.export.setEnabled(exportable && !inProgress);
            holder.export.setOnClickListener(v -> {
                viewModel.export(summary.getId());
                Toast.makeText(requireContext(), R.string.files_m3_export_started,
                        Toast.LENGTH_SHORT).show();
            });
        }

        @Override public int getItemCount() { return state == null ? 0 : state.sessions.size(); }
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
}
