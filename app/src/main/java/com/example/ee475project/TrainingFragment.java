package com.example.ee475project;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.card.MaterialCardView;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;

public class TrainingFragment extends Fragment {

    private static final String TAG = "TrainingFragment";

    private TrainingViewModel trainingViewModel;
    private OkHttpClient httpClient;
    private EditText serverUrlInput;

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

        // Initialize HTTP client
        httpClient = new OkHttpClient();  // ADD THIS

        // Initialize ViewModel
        trainingViewModel = new ViewModelProvider(requireActivity()).get(TrainingViewModel.class);

        // Initialize UI elements
        poseSpinner = view.findViewById(R.id.pose_spinner);
        serverUrlInput = view.findViewById(R.id.server_url_input);  // ADD THIS
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

        // Check if we already have data
        boolean hasExistingData = !trainingViewModel.getUpperBackData().isEmpty() ||
                !trainingViewModel.getLowerBackData().isEmpty();

        if (hasExistingData) {
            // Warn user that starting new collection will clear existing data
            new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("⚠️ Warning")
                    .setMessage("You have uncollected training data!\n\n" +
                            "Starting a new collection will delete the existing data.\n\n" +
                            "Do you want to continue?")
                    .setPositiveButton("Continue", (dialog, which) -> {
                        // Clear buffers and start new collection
                        trainingViewModel.clearBuffers();
                        startCollectionConfirmation(selectedPose);
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        } else {
            startCollectionConfirmation(selectedPose);
        }
    }

    private void startCollectionConfirmation(String selectedPose) {
        // CRITICAL: Store the selected pose in ViewModel
        trainingViewModel.setSelectedPoseLabel(selectedPose);

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
        String poseLabel = trainingViewModel.getSelectedPoseLabel();

        if (poseLabel == null || poseLabel.isEmpty()) {
            Toast.makeText(getContext(), "No pose label found", Toast.LENGTH_SHORT).show();
            return;
        }

        // Generate JSON from collected data
        String jsonString = trainingViewModel.generateTrainingJSON(poseLabel);

        if (jsonString == null) {
            Toast.makeText(getContext(),
                    "❌ Failed to generate JSON",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        // Show options: Upload, Save to file, or Copy to clipboard
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Export Training Data")
                .setMessage("JSON generated successfully!\n\n" +
                        "Size: " + (jsonString.length() / 1024) + " KB\n" +
                        "Upper samples: " + trainingViewModel.getUpperBackData().size() + "\n" +
                        "Lower samples: " + trainingViewModel.getLowerBackData().size() + "\n\n" +
                        "Choose export option:")
                .setPositiveButton("Upload to Server", (dialog, which) -> {
                    uploadToServer(jsonString, poseLabel);
                })
                .setNegativeButton("Save to File", (dialog, which) -> {
                    saveJSONToFile(jsonString, poseLabel);
                })
                .setNeutralButton("Copy to Clipboard", (dialog, which) -> {
                    copyToClipboard(jsonString, poseLabel);
                })
                .show();
    }

    private void saveJSONToFile(String jsonString, String poseLabel) {
        try {
            // Format filename with timestamp
            String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss",
                    java.util.Locale.US).format(new java.util.Date());
            String filename = poseLabel.toLowerCase().replace(" ", "_") + "_" + timestamp + ".json";

            // Use Android's external storage (Downloads folder)
            // This works on Android 10+ without special permissions
            java.io.File downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS);

            // Make sure directory exists
            if (!downloadsDir.exists()) {
                downloadsDir.mkdirs();
            }

            java.io.File outputFile = new java.io.File(downloadsDir, filename);

            // Write JSON to file
            java.io.FileWriter writer = new java.io.FileWriter(outputFile);
            writer.write(jsonString);
            writer.close();

            Log.d(TAG, "JSON saved to: " + outputFile.getAbsolutePath());

            // Show success with file path
            new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("✓ Export Complete")
                    .setMessage("Training data saved successfully!\n\n" +
                            "File: " + filename + "\n" +
                            "Location: Downloads folder\n\n" +
                            "You can now access this file from your phone's Downloads folder.")
                    .setPositiveButton("OK", null)
                    .show();

        } catch (Exception e) {
            Log.e(TAG, "Error saving JSON: " + e.getMessage(), e);

            // Fallback: Try to save to app's internal storage
            try {
                String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss",
                        java.util.Locale.US).format(new java.util.Date());
                String filename = poseLabel.toLowerCase().replace(" ", "_") + "_" + timestamp + ".json";

                // Use app's internal files directory (always works)
                java.io.File internalDir = requireContext().getFilesDir();
                java.io.File outputFile = new java.io.File(internalDir, filename);

                java.io.FileWriter writer = new java.io.FileWriter(outputFile);
                writer.write(jsonString);
                writer.close();

                Log.d(TAG, "JSON saved to internal storage: " + outputFile.getAbsolutePath());

                new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                        .setTitle("✓ Export Complete (Internal Storage)")
                        .setMessage("Saved to app's internal storage:\n\n" +
                                filename + "\n\n" +
                                "Path: " + outputFile.getAbsolutePath() + "\n\n" +
                                "Note: You may need to use a file manager app or connect to a computer to access this file.\n\n" +
                                "Alternatively, use 'Copy to Clipboard' to paste the data elsewhere.")
                        .setPositiveButton("OK", null)
                        .show();

            } catch (Exception e2) {
                Log.e(TAG, "Failed to save to internal storage too: " + e2.getMessage(), e2);
                Toast.makeText(getContext(),
                        "❌ Error saving file. Please use 'Copy to Clipboard' instead.",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private void copyToClipboard(String jsonString, String poseLabel) {
        try {
            android.content.ClipboardManager clipboard =
                    (android.content.ClipboardManager) requireContext()
                            .getSystemService(android.content.Context.CLIPBOARD_SERVICE);

            android.content.ClipData clip = android.content.ClipData.newPlainText(
                    "Training Data - " + poseLabel,
                    jsonString);

            clipboard.setPrimaryClip(clip);

            Toast.makeText(getContext(),
                    "✓ Copied to clipboard!",
                    Toast.LENGTH_SHORT).show();

        } catch (Exception e) {
            Log.e(TAG, "Error copying to clipboard: " + e.getMessage(), e);
            Toast.makeText(getContext(),
                    "❌ Error copying: " + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void uploadToServer(String jsonString, String poseLabel) {
        String serverUrl = serverUrlInput.getText().toString().trim();

        if (serverUrl.isEmpty()) {
            Toast.makeText(getContext(),
                    "⚠️ Please enter server URL first",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        // Add https:// prefix if missing
        if (!serverUrl.startsWith("http://") && !serverUrl.startsWith("https://")) {
            serverUrl = "https://" + serverUrl;
        }

        // Add /upload endpoint if not present
        if (!serverUrl.endsWith("/upload")) {
            if (serverUrl.endsWith("/")) {
                serverUrl = serverUrl + "upload";
            } else {
                serverUrl = serverUrl + "/upload";
            }
        }

        Log.d(TAG, "Uploading to server: " + serverUrl);

        // Show progress dialog
        android.app.ProgressDialog progressDialog = new android.app.ProgressDialog(requireContext());
        progressDialog.setMessage("Uploading training data...");
        progressDialog.setCancelable(false);
        progressDialog.show();

        try {
            MediaType JSON = MediaType.get("application/json; charset=utf-8");
            RequestBody body = RequestBody.create(jsonString, JSON);

            Request request = new Request.Builder()
                    .url(serverUrl)
                    .post(body)
                    .build();

            // Send request asynchronously
            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    Log.e(TAG, "Upload failed: " + e.getMessage(), e);

                    requireActivity().runOnUiThread(() -> {
                        progressDialog.dismiss();

                        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                                .setTitle("❌ Upload Failed")
                                .setMessage("Could not connect to server.\n\n" +
                                        "Error: " + e.getMessage() + "\n\n" +
                                        "Please check:\n" +
                                        "• Server URL is correct\n" +
                                        "• Server is running\n" +
                                        "• You have internet connection")
                                .setPositiveButton("OK", null)
                                .setNeutralButton("Save to File Instead", (dialog, which) -> {
                                    saveJSONToFile(jsonString, poseLabel);
                                })
                                .show();
                    });
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response)
                        throws IOException {
                    final String responseBody = response.body() != null ?
                            response.body().string() : "";

                    Log.d(TAG, "Server response code: " + response.code());
                    Log.d(TAG, "Server response body: " + responseBody);

                    requireActivity().runOnUiThread(() -> {
                        progressDialog.dismiss();

                        if (response.isSuccessful()) {
                            // Success!
                            new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                                    .setTitle("✅ Upload Successful!")
                                    .setMessage("Training data uploaded successfully!\n\n" +
                                            "Pose: " + poseLabel + "\n" +
                                            "Samples: " + trainingViewModel.getUpperBackData().size() +
                                            " upper, " + trainingViewModel.getLowerBackData().size() +
                                            " lower\n\n" +
                                            "Server response:\n" + responseBody)
                                    .setPositiveButton("OK", null)
                                    .show();

                            // Clear buffers after successful upload
                            trainingViewModel.clearBuffers();

                            Toast.makeText(getContext(),
                                    "✓ Data uploaded and buffers cleared",
                                    Toast.LENGTH_SHORT).show();

                        } else {
                            // Server error
                            new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                                    .setTitle("⚠️ Server Error")
                                    .setMessage("Server returned error code: " + response.code() +
                                            "\n\n" + responseBody)
                                    .setPositiveButton("OK", null)
                                    .setNeutralButton("Save to File Instead", (dialog, which) -> {
                                        saveJSONToFile(jsonString, poseLabel);
                                    })
                                    .show();
                        }
                    });
                }
            });

        } catch (Exception e) {
            progressDialog.dismiss();
            Log.e(TAG, "Error creating upload request: " + e.getMessage(), e);
            Toast.makeText(getContext(),
                    "Error: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

}