package com.example.ee475project;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
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
import com.google.firebase.database.Query;
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

    // Status card Firebase listener - FIXED: Use Query instead of DatabaseReference
    private ValueEventListener statusListener;
    private Query statusQuery;  // Changed from DatabaseReference to Query

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
                updateDailyGoalProgress();
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
                updateSlouchTime();
                updateDailyGoalProgress();
            }
        });

        // ===== NEW: Observe cycle completion to trigger analysis =====
        bluetoothViewModel.getIsCycleComplete().observe(getViewLifecycleOwner(), isComplete -> {
            if (isComplete != null && isComplete) {
                Log.d(TAG, "Cycle completed! Triggering auto-analysis...");
                // Small delay to ensure session is saved to Firebase
                new android.os.Handler().postDelayed(() -> {
                    if (isAdded()) {
                        runAnalysisAndUpdateStatus();
                    }
                }, 1500);
            }
        });

        // Load today's slouch percentage from Firebase
        loadTodaySlouchPercentage();

        // Set up real-time status listener
        setupRealtimeStatusListener();
    }

    /**
     * NEW: Set up a REAL-TIME listener on analyzed sessions
     * This continuously listens for changes to the most recent analyzed session
     */
    private void setupRealtimeStatusListener() {
        String userId = FirebaseAuth.getInstance().getCurrentUser().getUid();

        // Remove old listener if exists
        if (statusQuery != null && statusListener != null) {
            statusQuery.removeEventListener(statusListener);
        }

        // Create a query for the most recent analyzed session
        statusQuery = FirebaseDatabase.getInstance()
                .getReference("posture_sessions")
                .child(userId)
                .orderByChild("analyzed")
                .equalTo(true)
                .limitToLast(1);

        statusListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!isAdded() || getContext() == null) {
                    Log.d(TAG, "Fragment not attached, skipping status update");
                    return;
                }

                Log.d(TAG, "Status listener triggered - analyzed sessions count: " + snapshot.getChildrenCount());

                if (snapshot.exists() && snapshot.getChildrenCount() > 0) {
                    // Get the most recent analyzed session
                    for (DataSnapshot sessionSnapshot : snapshot.getChildren()) {
                        PostureSession session = sessionSnapshot.getValue(PostureSession.class);

                        if (session != null) {
                            Log.d(TAG, "Latest analyzed session: " + session.sessionId +
                                    ", slouching=" + session.slouching +
                                    ", timestamp=" + session.timestamp);

                            // Update status card based on slouching value
                            updateStatusCard(session.slouching);
                        }
                    }
                } else {
                    // No analyzed sessions exist - show Unknown
                    Log.d(TAG, "No analyzed sessions found - showing Unknown");
                    updateStatusCard(null);
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Error in status listener: " + error.getMessage());
            }
        };

        // Add the continuous listener
        statusQuery.addValueEventListener(statusListener);
        Log.d(TAG, "Real-time status listener set up");
    }

    /**
     * NEW: Run analysis and the status will auto-update via the listener
     */
    private void runAnalysisAndUpdateStatus() {
        Log.d(TAG, "Running analysis after cycle completion...");

        PostureAnalyzer analyzer = new PostureAnalyzer();
        analyzer.analyzeUnprocessedSessions(new PostureAnalyzer.OnAnalysisCompleteListener() {
            @Override
            public void onAnalysisComplete(int sessionsAnalyzed, int slouchingSessions) {
                Log.d(TAG, "Analysis complete: " + sessionsAnalyzed + " sessions, " +
                        slouchingSessions + " slouching");
                // Status card will auto-update via the Firebase listener!
                // No manual update needed here
            }

            @Override
            public void onAnalysisError(String error) {
                Log.e(TAG, "Analysis error: " + error);
            }
        });
    }

    /**
     * Update the status card UI based on slouching state
     * @param isSlouchingNow true = slouching, false = upright, null = no data/not analyzed
     */
    private void updateStatusCard(Boolean isSlouchingNow) {
        if (!isAdded() || getContext() == null) {
            Log.d(TAG, "Fragment not attached, skipping UI update");
            return;
        }

        Log.d(TAG, "Updating status card: isSlouchingNow = " + isSlouchingNow);

        if (isSlouchingNow == null) {
            // No data - show grey/neutral state
            statusWidget.setBackgroundResource(R.drawable.rounded_corner_white);
            statusIcon.setImageResource(android.R.drawable.ic_menu_info_details);
            statusIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.ios_text_secondary));
            statusText.setText("Unknown");
            statusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.ios_text_secondary));
            statusSuggestion.setText("No posture data yet");
            statusSuggestion.setTextColor(ContextCompat.getColor(requireContext(), R.color.ios_text_secondary));
            Log.d(TAG, "Status card: Unknown (grey)");

        } else if (isSlouchingNow) {
            // SLOUCHING - Orange background
            statusWidget.setBackgroundResource(R.drawable.rounded_corner_orange);
            statusIcon.setImageResource(android.R.drawable.ic_dialog_alert);
            statusIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.white));
            statusText.setText("Slouching");
            statusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.white));
            statusSuggestion.setText("Consider sitting up straight");
            statusSuggestion.setTextColor(ContextCompat.getColor(requireContext(), R.color.white));
            Log.d(TAG, "Status card: Slouching (orange)");

        } else {
            // UPRIGHT - Green background
            statusWidget.setBackgroundResource(R.drawable.rounded_corner_green);
            statusIcon.setImageResource(android.R.drawable.checkbox_on_background);
            statusIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.white));
            statusText.setText("Good Posture");
            statusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.white));
            statusSuggestion.setText("Keep it up!");
            statusSuggestion.setTextColor(ContextCompat.getColor(requireContext(), R.color.white));
            Log.d(TAG, "Status card: Good Posture (green)");
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
        } else {
            connectButton.setText("Connect");
            connectButton.setOnClickListener(v -> requestBluetoothPermissions());
        }
    }

    private void updateDailyGoal(int dailyGoal) {
        dailyGoalMinutes.setText("of " + dailyGoal + " minutes");
    }

    private void updateDailyGoalProgress() {
        float slouchTimeMinutes = currentActiveTime * (float)(currentSlouchPercentage / 100.0);
        float uprightTime = currentActiveTime - slouchTimeMinutes;

        int percentage = 0;
        if (currentDailyGoal > 0) {
            percentage = (int) ((uprightTime / currentDailyGoal) * 100);
            percentage = Math.min(percentage, 100);
        }

        dailyGoalValue.setText(String.valueOf((int) uprightTime));
        dailyGoalPercentage.setText(percentage + "%");
        dailyGoalProgress.setMax(100);
        dailyGoalProgress.setProgress(percentage);

        Log.d(TAG, "Daily Goal: Upright=" + uprightTime + "min, " + percentage + "% of " + currentDailyGoal);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        // CRITICAL: Remove Firebase listener to prevent crashes
        if (statusQuery != null && statusListener != null) {
            statusQuery.removeEventListener(statusListener);
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
                        updateSlouchTime();
                        updateDailyGoalProgress();
                    }
                } else {
                    currentSlouchPercentage = 0.0;
                    updateSlouchTime();
                    updateDailyGoalProgress();
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

        // Trigger the daily check when the fragment becomes visible
        if (bluetoothViewModel != null) {
            bluetoothViewModel.performInitialLoadAndDailyCheck();
        }

        // Check for any unanalyzed sessions (in case app was backgrounded)
        new android.os.Handler().postDelayed(() -> {
            if (isAdded()) {
                checkAndAnalyzeIfNeeded();
            }
        }, 500);
    }

    /**
     * Check if there are unanalyzed sessions and analyze them
     */
    private void checkAndAnalyzeIfNeeded() {
        String userId = FirebaseAuth.getInstance().getCurrentUser().getUid();

        DatabaseReference sessionsRef = FirebaseDatabase.getInstance()
                .getReference("posture_sessions")
                .child(userId);

        sessionsRef.orderByChild("analyzed").equalTo(false)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        if (snapshot.getChildrenCount() > 0) {
                            Log.d(TAG, "Found " + snapshot.getChildrenCount() +
                                    " unanalyzed sessions on resume. Analyzing...");
                            runAnalysisAndUpdateStatus();
                        }
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        Log.e(TAG, "Error checking for unanalyzed sessions: " + error.getMessage());
                    }
                });
    }

    private void updateSlouchTime() {
        float slouchTimeMinutes = currentActiveTime * (float)(currentSlouchPercentage / 100.0);

        if (slouchTimeValue != null) {
            slouchTimeValue.setText(String.format(Locale.US, "%.1f min", slouchTimeMinutes));
            Log.d(TAG, "Slouch time: " + slouchTimeMinutes + " min");
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