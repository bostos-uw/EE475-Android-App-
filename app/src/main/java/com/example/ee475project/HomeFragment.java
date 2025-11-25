package com.example.ee475project;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import android.widget.ImageView;
import androidx.constraintlayout.widget.ConstraintLayout;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class HomeFragment extends Fragment {

    private static final String TAG = "HomeFragment";

    private TextView dailyGoalValue;
    private TextView dailyGoalMinutes;
    private TextView dailyGoalPercentage;
    private ProgressBar dailyGoalProgress;
    private Button connectButton;
    private TextView connectionStatusText;
    private TextView activeTimeValue;
    private TextView slouchTimeValue;

    // Status card views
    private ConstraintLayout statusWidget;
    private ImageView statusIcon;
    private TextView statusText;
    private TextView statusSuggestion;

    private BluetoothViewModel bluetoothViewModel;
    private SharedViewModel sharedViewModel;

    // Status card Firebase listener
    private ValueEventListener statusListener;
    private DatabaseReference statusRef;

    private float currentActiveTime = 0f;
    private int currentDailyGoal = 480; // Default 480 minutes (8 hours)
    private double currentSlouchPercentage = 0.0;

    private final ActivityResultLauncher<String[]> requestPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(),
            permissions -> {
                boolean allPermissionsGranted = true;
                for (Boolean granted : permissions.values()) {
                    allPermissionsGranted &= granted;
                }
                if (allPermissionsGranted) {
                    enableBluetooth();
                } else {
                    // Handle permission denial
                }
            });

    private final ActivityResultLauncher<Intent> enableBluetoothLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    bluetoothViewModel.startCycle();
                } else {
                    // Handle Bluetooth not enabled
                }
            });

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Initialize views
        dailyGoalValue = view.findViewById(R.id.daily_goal_value);
        dailyGoalMinutes = view.findViewById(R.id.daily_goal_minutes);
        dailyGoalPercentage = view.findViewById(R.id.daily_goal_percentage);
        dailyGoalProgress = view.findViewById(R.id.daily_goal_progress);
        connectButton = view.findViewById(R.id.connect_button);
        connectionStatusText = view.findViewById(R.id.connection_status_text);
        activeTimeValue = view.findViewById(R.id.active_time_value);
        slouchTimeValue = view.findViewById(R.id.slouch_time_value);

        // Initialize status card views
        statusWidget = view.findViewById(R.id.status_widget);
        statusIcon = view.findViewById(R.id.status_icon);
        statusText = view.findViewById(R.id.status_text);
        statusSuggestion = view.findViewById(R.id.status_suggestion);

        // Use SharedViewModel to observe goal changes
        sharedViewModel = new ViewModelProvider(requireActivity()).get(SharedViewModel.class);
        sharedViewModel.getDailyGoal().observe(getViewLifecycleOwner(), goal -> {
            if (goal != null) {
                currentDailyGoal = goal;
                updateDailyGoal(goal);
                updateDailyGoalProgress(); // Recalculate progress when goal changes
            }
        });

        bluetoothViewModel = new ViewModelProvider(requireActivity()).get(BluetoothViewModel.class);

        // Set initial button state
        updateButtonState(bluetoothViewModel.getConnectionStatus().getValue());

        bluetoothViewModel.getConnectionStatus().observe(getViewLifecycleOwner(), this::updateButtonState);

        // Observe active time changes
        bluetoothViewModel.getActiveTime().observe(getViewLifecycleOwner(), time -> {
            if (time != null) {
                currentActiveTime = time;
                activeTimeValue.setText(String.format(Locale.US, "%.1f min", time));

                // Update slouch time whenever active time changes
                updateSlouchTime();

                // Update daily goal progress
                updateDailyGoalProgress();
            }
        });

        // Load today's slouch percentage from Firebase
        loadTodaySlouchPercentage();
    }

    private void loadCurrentPostureStatus() {
        String userId = FirebaseAuth.getInstance().getCurrentUser().getUid();

        statusRef = FirebaseDatabase.getInstance()
                .getReference("posture_sessions")
                .child(userId);

        // Remove old listener if exists
        if (statusListener != null) {
            statusRef.removeEventListener(statusListener);
        }

        // Create new listener
        statusListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                // SAFETY CHECK: Only update if fragment is attached
                if (!isAdded() || getContext() == null) {
                    Log.d(TAG, "Fragment not attached, skipping status update");
                    return;
                }

                // Query for most recent ANALYZED session
                DatabaseReference analyzedRef = FirebaseDatabase.getInstance()
                        .getReference("posture_sessions")
                        .child(userId);

                analyzedRef.orderByChild("analyzed").equalTo(true)
                        .limitToLast(1)
                        .addListenerForSingleValueEvent(new ValueEventListener() {
                            @Override
                            public void onDataChange(@NonNull DataSnapshot analyzedSnapshot) {
                                if (!isAdded() || getContext() == null) {
                                    return;
                                }

                                if (analyzedSnapshot.exists()) {
                                    // Get the most recent analyzed session
                                    for (DataSnapshot sessionSnapshot : analyzedSnapshot.getChildren()) {
                                        PostureSession session = sessionSnapshot.getValue(PostureSession.class);

                                        if (session != null) {
                                            Log.d(TAG, "Current Status - Last analyzed session: " + session.sessionId +
                                                    ", slouching=" + session.slouching +
                                                    ", timestamp=" + session.timestamp);

                                            // Update with the last known status (keeps showing until new one arrives)
                                            updateStatusCard(session.slouching != null && session.slouching);
                                        }
                                    }
                                } else {
                                    // No analyzed sessions exist at all - show Unknown
                                    Log.d(TAG, "No analyzed sessions found - showing Unknown");
                                    updateStatusCard(null);
                                }
                            }

                            @Override
                            public void onCancelled(@NonNull DatabaseError error) {
                                Log.e(TAG, "Error loading analyzed sessions: " + error.getMessage());
                            }
                        });
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Error loading current status: " + error.getMessage());
            }
        };

        // Listen to ALL sessions (this triggers re-check when new sessions added)
        statusRef.addValueEventListener(statusListener);
    }

    /**
     * Update the status card UI based on slouching state
     * @param isSlouchingNow true = slouching, false = upright, null = no data/not analyzed
     */
    private void updateStatusCard(Boolean isSlouchingNow) {
        // SAFETY CHECK: Only update if fragment is attached
        if (!isAdded() || getContext() == null) {
            Log.d(TAG, "Fragment not attached, skipping UI update");
            return;
        }

        // Background always stays white
        statusWidget.setBackgroundResource(R.drawable.rounded_corner_white);

        if (isSlouchingNow == null) {
            // No data ever - show grey
            statusIcon.setImageResource(android.R.drawable.ic_menu_info_details);
            statusText.setText("Unknown");
            statusText.setTextColor(getResources().getColor(R.color.ios_text_secondary, null));
            statusSuggestion.setText("No posture data yet");
            statusSuggestion.setTextColor(getResources().getColor(R.color.ios_text_secondary, null));
            Log.d(TAG, "Status card: Unknown (no data)");

        } else if (isSlouchingNow) {
            // SLOUCHING - Orange text
            statusIcon.setImageResource(android.R.drawable.ic_dialog_alert);
            statusText.setText("Slouching");
            statusText.setTextColor(getResources().getColor(R.color.ios_orange, null));
            statusSuggestion.setText("Consider sitting up straight");
            statusSuggestion.setTextColor(getResources().getColor(R.color.ios_orange, null));
            Log.d(TAG, "Status card: Slouching (orange text)");

        } else {
            // UPRIGHT - Green text
            statusIcon.setImageResource(android.R.drawable.checkbox_on_background);
            statusText.setText("Good Posture");
            statusText.setTextColor(getResources().getColor(R.color.green, null));
            statusSuggestion.setText("Keep it up!");
            statusSuggestion.setTextColor(getResources().getColor(R.color.green, null));
            Log.d(TAG, "Status card: Good Posture (green text)");
        }
    }



    private void updateButtonState(String status) {
        connectionStatusText.setText(status);
        if (status == null) {
            status = "Disconnected";
        }

        if (status.startsWith("Scanning for") || status.equals("Scanning...")) {
            connectButton.setText("Cancel");
            connectButton.setOnClickListener(v -> bluetoothViewModel.cancelScan());
        } else if (status.startsWith("Connecting to")) {
            connectButton.setText("Cancel");
            connectButton.setOnClickListener(v -> bluetoothViewModel.disconnect());
        } else if (status.startsWith("Connected to")) {
            connectButton.setText("Disconnect");
            connectButton.setOnClickListener(v -> bluetoothViewModel.disconnect());
        } else { // Primarily "Disconnected"
            connectButton.setText("Connect");
            connectButton.setOnClickListener(v -> requestBluetoothPermissions());
        }
    }

    private void updateDailyGoal(int dailyGoal) {
        dailyGoalMinutes.setText("of " + dailyGoal + " minutes");
    }

    private void updateDailyGoalProgress() {
        // Calculate slouch time first
        float slouchTimeMinutes = currentActiveTime * (float)(currentSlouchPercentage / 100.0);

        // Calculate upright time: simply subtract slouch from active
        float uprightTime = currentActiveTime - slouchTimeMinutes;

        // Calculate percentage: (upright_time / daily_goal) × 100
        int percentage = 0;
        if (currentDailyGoal > 0) {
            percentage = (int) ((uprightTime / currentDailyGoal) * 100);
            // Cap at 100%
            percentage = Math.min(percentage, 100);
        }

        // Update the big number (upright time in minutes)
        dailyGoalValue.setText(String.valueOf((int) uprightTime));

        // Update the percentage text
        dailyGoalPercentage.setText(percentage + "%");

        // Update progress bar
        dailyGoalProgress.setMax(100);
        dailyGoalProgress.setProgress(percentage);

        Log.d(TAG, "=== Daily Goal Calculation ===");
        Log.d(TAG, "Active Time: " + currentActiveTime + " min");
        Log.d(TAG, "Slouch Percentage: " + currentSlouchPercentage + "%");
        Log.d(TAG, "Slouch Time: " + slouchTimeMinutes + " min");
        Log.d(TAG, "Upright Time: " + uprightTime + " min");
        Log.d(TAG, "Daily Goal: " + currentDailyGoal + " min");
        Log.d(TAG, "Completion: " + percentage + "%");
        Log.d(TAG, "============================");
    }

    public void onDestroyView() {
        super.onDestroyView();

        // CRITICAL: Remove Firebase listener to prevent crashes
        if (statusRef != null && statusListener != null) {
            statusRef.removeEventListener(statusListener);
            Log.d(TAG, "Status listener removed");
        }
    }


    private void loadTodaySlouchPercentage() {
        String userId = FirebaseAuth.getInstance().getCurrentUser().getUid();
        String todayKey = getTodayDateKey();

        DatabaseReference statsRef = FirebaseDatabase.getInstance()
                .getReference("daily_stats")
                .child(userId)
                .child(todayKey);

        statsRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    Double slouchPercentage = snapshot.child("slouch_percentage").getValue(Double.class);

                    if (slouchPercentage != null) {
                        currentSlouchPercentage = slouchPercentage;
                        Log.d(TAG, "Today's slouch percentage: " + currentSlouchPercentage + "%");

                        // Update slouch time with new percentage
                        updateSlouchTime();

                        // ADDED: Update daily goal progress when slouch percentage changes
                        updateDailyGoalProgress();
                    }
                } else {
                    // No data for today yet
                    currentSlouchPercentage = 0.0;
                    updateSlouchTime();
                    updateDailyGoalProgress(); // ADDED
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Error loading slouch percentage: " + error.getMessage());
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();

        // Manually trigger the daily check when the fragment becomes visible
        if (bluetoothViewModel != null) {
            bluetoothViewModel.performInitialLoadAndDailyCheck();
        }

        // Load current posture status
        loadCurrentPostureStatus();

        // Auto-analyze with a slight delay to ensure session is saved
        new android.os.Handler().postDelayed(() -> {
            if (isAdded()) {
                autoAnalyzeIfNeeded();
            }
        }, 1000); // 1 second delay
    }

    private void autoAnalyzeIfNeeded() {
        String userId = FirebaseAuth.getInstance().getCurrentUser().getUid();

        DatabaseReference sessionsRef = FirebaseDatabase.getInstance()
                .getReference("posture_sessions")
                .child(userId);

        // Check if there are any unanalyzed sessions
        sessionsRef.orderByChild("analyzed").equalTo(false)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        if (snapshot.getChildrenCount() > 0) {
                            Log.d(TAG, "Found " + snapshot.getChildrenCount() +
                                    " unanalyzed sessions. Auto-analyzing...");

                            // Create analyzer and run analysis silently
                            PostureAnalyzer analyzer = new PostureAnalyzer();
                            analyzer.analyzeUnprocessedSessions(new PostureAnalyzer.OnAnalysisCompleteListener() {
                                @Override
                                public void onAnalysisComplete(int sessionsAnalyzed, int slouchingSessions) {
                                    Log.d(TAG, "Auto-analysis complete: " + sessionsAnalyzed + " sessions");
                                    // Status card will update automatically via Firebase listener
                                }

                                @Override
                                public void onAnalysisError(String error) {
                                    Log.e(TAG, "Auto-analysis error: " + error);
                                }
                            });
                        }
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        Log.e(TAG, "Error checking for unanalyzed sessions: " + error.getMessage());
                    }
                });
    }


    private void updateSlouchTime() {
        // Calculate slouch time: active_time × (slouch_percentage / 100)
        float slouchTimeMinutes = currentActiveTime * (float)(currentSlouchPercentage / 100.0);

        // Update UI
        if (slouchTimeValue != null) {
            slouchTimeValue.setText(String.format(Locale.US, "%.1f min", slouchTimeMinutes));
            Log.d(TAG, "Slouch time updated: " + slouchTimeMinutes + " min " +
                    "(Active: " + currentActiveTime + " min, Slouch %: " + currentSlouchPercentage + "%)");
        }
    }

    private String getTodayDateKey() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        return sdf.format(new Date());
    }

    private void requestBluetoothPermissions() {
        String[] permissions;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions = new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
            };
        } else {
            permissions = new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION
            };
        }

        if (!hasPermissions(permissions)) {
            requestPermissionLauncher.launch(permissions);
        } else {
            enableBluetooth();
        }
    }

    private boolean hasPermissions(String[] permissions) {
        if (isAdded()) {
            for (String permission : permissions) {
                if (ContextCompat.checkSelfPermission(requireContext(), permission) != PackageManager.PERMISSION_GRANTED) {
                    return false;
                }
            }
        }
        return true;
    }

    private void enableBluetooth() {
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            enableBluetoothLauncher.launch(enableBtIntent);
        } else {
            bluetoothViewModel.startCycle();
        }
    }
}
