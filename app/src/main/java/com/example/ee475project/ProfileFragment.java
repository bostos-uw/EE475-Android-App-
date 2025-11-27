package com.example.ee475project;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import android.app.AlertDialog;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import org.json.JSONObject;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;


public class ProfileFragment extends Fragment {

    private static final String TAG = "ProfileFragment";

    private ActivityResultLauncher<String> requestPermissionLauncher;
    private EditText goalInput;
    private TextView goalTextPreview;
    private Button saveGoalButton;
    private Button signOutButton;
    private Button startCalibrationButton;
    private Button testServerButton;  // NEW: Test server button
    private EditText serverUrlInput;   // NEW: Input for server URL
    private TextView calibrationStatusText;
    private SharedViewModel sharedViewModel;
    private BluetoothViewModel bluetoothViewModel;
    private TextView upperBackStatusText, lowerBackStatusText;
    private View upperBackStatusIndicator, lowerBackStatusIndicator;

    private FirebaseAuth mAuth;
    private DatabaseReference mDatabase;
    private CalibrationHelper calibrationHelper;

    private OkHttpClient httpClient;  // NEW: HTTP client

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestPermissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
            // We can handle the permission result here if needed
        });
        sharedViewModel = new ViewModelProvider(requireActivity()).get(SharedViewModel.class);
        bluetoothViewModel = new ViewModelProvider(requireActivity()).get(BluetoothViewModel.class);

        mAuth = FirebaseAuth.getInstance();
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser != null) {
            mDatabase = FirebaseDatabase.getInstance().getReference("users").child(currentUser.getUid());
        }

        // NEW: Initialize HTTP client
        httpClient = new OkHttpClient();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_profile, container, false);
    }


    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // ===== EXISTING CODE =====
        goalInput = view.findViewById(R.id.goal_input);
        goalTextPreview = view.findViewById(R.id.goal_text_preview);
        saveGoalButton = view.findViewById(R.id.save_goal_button);
        signOutButton = view.findViewById(R.id.sign_out_button);
        upperBackStatusText = view.findViewById(R.id.upper_back_status_text);
        upperBackStatusIndicator = view.findViewById(R.id.upper_back_status_indicator);
        lowerBackStatusText = view.findViewById(R.id.lower_back_status_text);
        lowerBackStatusIndicator = view.findViewById(R.id.lower_back_status_indicator);

        // ===== NEW CODE - Server Test =====
        testServerButton = view.findViewById(R.id.test_server_button);
        serverUrlInput = view.findViewById(R.id.server_url_input);

        testServerButton.setOnClickListener(v -> testServerConnection());

        // ===== CALIBRATION CODE =====
        startCalibrationButton = view.findViewById(R.id.start_calibration_button);
        calibrationStatusText = view.findViewById(R.id.calibration_status_text);

        // Initialize CalibrationHelper WITH BluetoothViewModel
        calibrationHelper = new CalibrationHelper(
                requireContext(),
                bluetoothViewModel,
                new CalibrationHelper.OnCalibrationCompleteListener() {
                    @Override
                    public void onCalibrationComplete(CalibrationData data) {
                        updateCalibrationStatus(true);
                        Toast.makeText(getContext(),
                                "Calibration saved! Algorithm will use your personalized thresholds.",
                                Toast.LENGTH_LONG).show();
                    }

                    @Override
                    public void onCalibrationCancelled() {
                        Toast.makeText(getContext(), "Calibration cancelled", Toast.LENGTH_SHORT).show();
                    }
                }
        );

        // Load calibration status from Firebase
        loadCalibrationStatus();


        // Calibration button click listener
        startCalibrationButton.setOnClickListener(v -> {
            // Check current calibration status
            if (calibrationStatusText.getText().toString().contains("Calibrated")) {
                // User has calibration data - show reset confirmation
                new AlertDialog.Builder(requireContext())
                        .setTitle("Reset Calibration")
                        .setMessage("This will delete your current calibration data.\n\n" +
                                "You'll need to complete a new calibration.\n\n" +
                                "Are you sure you want to reset?")
                        .setPositiveButton("Reset", (dialog, which) -> {
                            resetCalibrationData();
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            } else {
                // No calibration data - start fresh calibration
                startFreshCalibration();
            }
        });

        sharedViewModel.getDailyGoal().observe(getViewLifecycleOwner(), goal -> {
            if (goal != null) {
                goalInput.setText(String.valueOf(goal));
                updateGoalPreview(goal);
            }
        });

        goalInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!s.toString().isEmpty()) {
                    try {
                        updateGoalPreview(Integer.parseInt(s.toString()));
                    } catch (NumberFormatException e) {
                        // ignore
                    }
                }
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        saveGoalButton.setOnClickListener(v -> {
            if (mDatabase != null) {
                try {
                    int newGoal = Integer.parseInt(goalInput.getText().toString());
                    mDatabase.child("dailyGoal").setValue(newGoal)
                            .addOnSuccessListener(aVoid -> {
                                if (isAdded()) {
                                    Toast.makeText(getContext(), "Goal saved!", Toast.LENGTH_SHORT).show();
                                }
                            })
                            .addOnFailureListener(e -> {
                                Log.e(TAG, "Failed to save goal.", e);
                                if (isAdded()) {
                                    Toast.makeText(getContext(), "Failed to save goal: " + e.getMessage(), Toast.LENGTH_LONG).show();
                                }
                            });
                } catch (NumberFormatException e) {
                    Log.e(TAG, "Invalid goal input", e);
                    if (isAdded()) {
                        Toast.makeText(getContext(), "Invalid goal format.", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        });

        signOutButton.setOnClickListener(v -> {
            FirebaseAuth.getInstance().signOut();
            Intent intent = new Intent(getActivity(), LoginActivity.class);
            startActivity(intent);
            if (getActivity() != null) {
                getActivity().finish();
            }
        });

        SwitchCompat notificationSwitch = view.findViewById(R.id.notification_switch);
        notificationSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                if (!isNotificationPermissionGranted()) {
                    requestNotificationPermission();
                }
            }
        });

        bluetoothViewModel.getConnectionStatus().observe(getViewLifecycleOwner(), status -> {
            boolean isUpperConnected = status != null && status.contains("XIAO_Upper_Back");
            boolean isLowerConnected = status != null && status.contains("XIAO_Lower_Back");

            updateDeviceStatus(isUpperConnected, upperBackStatusText, upperBackStatusIndicator);
            updateDeviceStatus(isLowerConnected, lowerBackStatusText, lowerBackStatusIndicator);
        });
    }

    // ===== NEW METHOD: Test Server Connection =====
    private void testServerConnection() {
        String serverUrl = serverUrlInput.getText().toString().trim();

        if (serverUrl.isEmpty()) {
            Toast.makeText(getContext(), "Please enter server URL", Toast.LENGTH_SHORT).show();
            return;
        }

        // Make sure URL has https:// prefix
        if (!serverUrl.startsWith("http://") && !serverUrl.startsWith("https://")) {
            serverUrl = "https://" + serverUrl;
        }

        // Ensure URL ends with /test
        if (!serverUrl.endsWith("/test")) {
            if (serverUrl.endsWith("/")) {
                serverUrl = serverUrl + "test";
            } else {
                serverUrl = serverUrl + "/test";
            }
        }

        Log.d(TAG, "Testing server connection to: " + serverUrl);
        Toast.makeText(getContext(), "Sending test request...", Toast.LENGTH_SHORT).show();

        try {
            // Create JSON payload
            JSONObject jsonBody = new JSONObject();
            jsonBody.put("message", "hello from Android");

            MediaType JSON = MediaType.get("application/json; charset=utf-8");
            RequestBody body = RequestBody.create(jsonBody.toString(), JSON);

            // Build request
            Request request = new Request.Builder()
                    .url(serverUrl)
                    .post(body)
                    .build();

            // Send request asynchronously
            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    Log.e(TAG, "Server test failed: " + e.getMessage(), e);
                    requireActivity().runOnUiThread(() -> {
                        Toast.makeText(getContext(),
                                "❌ Connection failed: " + e.getMessage(),
                                Toast.LENGTH_LONG).show();
                    });
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    final String responseBody = response.body() != null ? response.body().string() : "";
                    Log.d(TAG, "Server response: " + responseBody);

                    requireActivity().runOnUiThread(() -> {
                        if (response.isSuccessful()) {
                            try {
                                JSONObject jsonResponse = new JSONObject(responseBody);
                                String reply = jsonResponse.optString("reply", "No reply field");

                                new AlertDialog.Builder(requireContext())
                                        .setTitle("✅ Server Connected!")
                                        .setMessage("Response from server:\n\n" + reply)
                                        .setPositiveButton("OK", null)
                                        .show();

                                Log.d(TAG, "Server test successful: " + reply);
                            } catch (Exception e) {
                                Toast.makeText(getContext(),
                                        "✅ Connected but unexpected response format",
                                        Toast.LENGTH_LONG).show();
                            }
                        } else {
                            Toast.makeText(getContext(),
                                    "❌ Server error: " + response.code(),
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "Error creating request: " + e.getMessage(), e);
            Toast.makeText(getContext(), "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ===== CALIBRATION METHODS =====

    private void loadCalibrationStatus() {
        String userId = FirebaseAuth.getInstance().getCurrentUser().getUid();
        DatabaseReference calibrationRef = FirebaseDatabase.getInstance()
                .getReference("calibration_data")
                .child(userId);

        calibrationRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    CalibrationData data = snapshot.getValue(CalibrationData.class);
                    if (data != null && data.isCalibrated) {
                        updateCalibrationStatus(true);
                    } else {
                        updateCalibrationStatus(false);
                    }
                } else {
                    updateCalibrationStatus(false);
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Error loading calibration status: " + error.getMessage());
            }
        });
    }

    private void updateCalibrationStatus(boolean isCalibrated) {
        if (isCalibrated) {
            calibrationStatusText.setText("✓ Calibrated");
            calibrationStatusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.ios_green));
            startCalibrationButton.setText("Reset Calibration");
        } else {
            calibrationStatusText.setText("Not calibrated");
            calibrationStatusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.ios_red));
            startCalibrationButton.setText("Start Calibration");
        }
    }

    // ===== EXISTING METHODS =====

    private void updateDeviceStatus(boolean isConnected, TextView statusText, View statusIndicator) {
        if (isConnected) {
            statusText.setText("Connected");
            statusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.ios_green));
            statusIndicator.setBackgroundResource(R.drawable.connected_dot);
        } else {
            statusText.setText("Not Connected");
            statusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.ios_red));
            statusIndicator.setBackgroundResource(R.drawable.disconnected_dot);
        }
    }

    private void updateGoalPreview(int minutes) {
        int hours = minutes / 60;
        int mins = minutes % 60;
        goalTextPreview.setText(hours + "h " + mins + "m per day");
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    private boolean isNotificationPermissionGranted() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void resetCalibrationData() {
        String userId = FirebaseAuth.getInstance().getCurrentUser().getUid();
        DatabaseReference calibrationRef = FirebaseDatabase.getInstance()
                .getReference("calibration_data")
                .child(userId);

        calibrationRef.removeValue()
                .addOnSuccessListener(aVoid -> {
                    Toast.makeText(getContext(),
                            "Calibration data reset. You can now calibrate again.",
                            Toast.LENGTH_SHORT).show();
                    updateCalibrationStatus(false);
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(getContext(),
                            "Failed to reset calibration: " + e.getMessage(),
                            Toast.LENGTH_SHORT).show();
                });
    }

    private void startFreshCalibration() {
        calibrationHelper = new CalibrationHelper(
                requireContext(),
                bluetoothViewModel,
                new CalibrationHelper.OnCalibrationCompleteListener() {
                    @Override
                    public void onCalibrationComplete(CalibrationData data) {
                        updateCalibrationStatus(true);
                        Toast.makeText(getContext(),
                                "Calibration saved! Algorithm will use your personalized thresholds.",
                                Toast.LENGTH_LONG).show();
                    }

                    @Override
                    public void onCalibrationCancelled() {
                        Toast.makeText(getContext(), "Calibration cancelled", Toast.LENGTH_SHORT).show();
                        loadCalibrationStatus();
                    }
                }
        );

        Toast.makeText(getContext(), "Starting calibration cycle...", Toast.LENGTH_SHORT).show();
        calibrationHelper.startCalibration();
    }


}