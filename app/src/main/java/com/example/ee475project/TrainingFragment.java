package com.example.ee475project;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.card.MaterialCardView;

public class TrainingFragment extends Fragment {

    private static final String TAG = "TrainingFragment";

    private TrainingViewModel trainingViewModel;

    // UI Elements
    private Spinner poseSpinner;
    private MaterialCardView progressCard;
    private MaterialCardView summaryCard;
    private TextView tvCurrentPhase;
    private ProgressBar progressBar;
    private TextView tvProgressPercentage;
    private TextView tvProgressMessage;
    private TextView tvUpperSamples;
    private TextView tvLowerSamples;
    private Button btnStartTraining;
    private Button btnCancelTraining;
    private Button btnExportJson;

    // Pose types
    private final String[] poseTypes = {
            "Sitting Upright",
            "Sitting Slouched",
            "Standing Upright",
            "Standing Slouched",
            "Walking Upright",
            "Walking Slouched"
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_training, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Initialize ViewModel
        trainingViewModel = new ViewModelProvider(requireActivity()).get(TrainingViewModel.class);

        // Initialize UI elements
        poseSpinner = view.findViewById(R.id.pose_spinner);
        progressCard = view.findViewById(R.id.progress_card);
        summaryCard = view.findViewById(R.id.summary_card);
        tvCurrentPhase = view.findViewById(R.id.tv_current_phase);
        progressBar = view.findViewById(R.id.progress_bar);
        tvProgressPercentage = view.findViewById(R.id.tv_progress_percentage);
        tvProgressMessage = view.findViewById(R.id.tv_progress_message);
        tvUpperSamples = view.findViewById(R.id.tv_upper_samples);
        tvLowerSamples = view.findViewById(R.id.tv_lower_samples);
        btnStartTraining = view.findViewById(R.id.btn_start_training);
        btnCancelTraining = view.findViewById(R.id.btn_cancel_training);
        btnExportJson = view.findViewById(R.id.btn_export_json);

        // Setup pose spinner
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_spinner_item,
                poseTypes
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        poseSpinner.setAdapter(adapter);

        // Setup button listeners
        btnStartTraining.setOnClickListener(v -> startTraining());
        btnCancelTraining.setOnClickListener(v -> cancelTraining());
        btnExportJson.setOnClickListener(v -> exportJSON());

        // Observe ViewModel LiveData
        setupObservers();
    }

    private void setupObservers() {
        // Collection progress
        trainingViewModel.getCollectionProgress().observe(getViewLifecycleOwner(), progress -> {
            if (progress != null) {
                progressBar.setProgress(progress);
                tvProgressPercentage.setText(progress + "%");
            }
        });

        // Current phase
        trainingViewModel.getCurrentPhase().observe(getViewLifecycleOwner(), phase -> {
            if (phase != null) {
                tvCurrentPhase.setText(phase);

                // Update UI based on phase
                switch (phase) {
                    case "Preparing...":
                    case "Upper Back":
                    case "Lower Back":
                        // Collection in progress
                        progressCard.setVisibility(View.VISIBLE);
                        summaryCard.setVisibility(View.GONE);
                        btnStartTraining.setVisibility(View.GONE);
                        btnCancelTraining.setVisibility(View.VISIBLE);
                        btnExportJson.setVisibility(View.GONE);
                        poseSpinner.setEnabled(false);

                        if (phase.equals("Upper Back")) {
                            tvProgressMessage.setText("Collecting upper back data...\nHold your posture steady!");
                        } else if (phase.equals("Lower Back")) {
                            tvProgressMessage.setText("Collecting lower back data...\nKeep holding your posture!");
                        }
                        break;

                    case "Complete":
                        // Collection finished
                        progressCard.setVisibility(View.GONE);
                        summaryCard.setVisibility(View.VISIBLE);
                        btnStartTraining.setVisibility(View.VISIBLE);
                        btnStartTraining.setText("Start New Collection");
                        btnCancelTraining.setVisibility(View.GONE);
                        btnExportJson.setVisibility(View.VISIBLE);
                        poseSpinner.setEnabled(true);

                        // Show sample counts
                        int upperCount = trainingViewModel.getUpperBackData().size();
                        int lowerCount = trainingViewModel.getLowerBackData().size();
                        tvUpperSamples.setText("Upper back: " + upperCount + " samples");
                        tvLowerSamples.setText("Lower back: " + lowerCount + " samples");

                        Toast.makeText(getContext(),
                                "✓ Training data collected successfully!",
                                Toast.LENGTH_LONG).show();
                        break;

                    case "Cancelled":
                        // Reset UI
                        progressCard.setVisibility(View.GONE);
                        summaryCard.setVisibility(View.GONE);
                        btnStartTraining.setVisibility(View.VISIBLE);
                        btnStartTraining.setText("Start Training");
                        btnCancelTraining.setVisibility(View.GONE);
                        btnExportJson.setVisibility(View.GONE);
                        poseSpinner.setEnabled(true);
                        break;
                }
            }
        });

        // Connection status (optional - for debugging)
        trainingViewModel.getConnectionStatus().observe(getViewLifecycleOwner(), status -> {
            // Could show in a status TextView if needed
        });
    }

    private void startTraining() {
        String selectedPose = poseSpinner.getSelectedItem().toString();

        // Confirm with user
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Start Training Collection")
                .setMessage("You selected: " + selectedPose + "\n\n" +
                        "This will take 4 minutes:\n" +
                        "• 2 minutes for upper back\n" +
                        "• 2 minutes for lower back\n\n" +
                        "Please maintain your posture throughout.\n\n" +
                        "Ready to begin?")
                .setPositiveButton("Start", (dialog, which) -> {
                    Toast.makeText(getContext(),
                            "Starting collection for: " + selectedPose,
                            Toast.LENGTH_SHORT).show();
                    trainingViewModel.startTrainingCollection();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void cancelTraining() {
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Cancel Training?")
                .setMessage("This will discard all collected data. Are you sure?")
                .setPositiveButton("Yes, Cancel", (dialog, which) -> {
                    trainingViewModel.cancelTraining();
                    Toast.makeText(getContext(), "Training cancelled", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("No", null)
                .show();
    }

    private void exportJSON() {
        // TODO: Implement JSON export after collection is working
        Toast.makeText(getContext(),
                "JSON export will be implemented next!",
                Toast.LENGTH_SHORT).show();
    }
}