package com.example.ee475project;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.EditText;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import android.widget.ImageView;
import android.widget.Toast;

import androidx.constraintlayout.widget.ConstraintLayout;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.Query;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import android.Manifest;



import okhttp3.OkHttpClient;


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

    private EditText homeServerUrlInput;
    private SwitchCompat mlInferenceSwitch;
    private OkHttpClient httpClient;
    private static final String PREFS_NAME = "MLInferencePrefs";
    private static final String PREF_SERVER_URL = "server_url";
    private static final String PREF_ML_ENABLED = "ml_inference_enabled";

    // ===== Slouch Notification System =====
    private static final String CHANNEL_ID = "posture_alerts";
    private static final int NOTIFICATION_ID = 1001;
    private static final int NOTIFICATION_PERMISSION_REQUEST_CODE = 100;
    private int consecutiveSlouchCount = 0;  // Track consecutive slouching sessions

    private TextView upperBatteryText;
    private TextView lowerBatteryText;
    private ImageView upperBatteryIcon;
    private ImageView lowerBatteryIcon;

    // Battery estimation constants
    private static final float BATTERY_CAPACITY_MAH = 1000f;
    private static final float BASELINE_CURRENT_MA = 29f;
    private static final float USABLE_CAPACITY_MAH = 850f;
    private static final float RUNTIME_HOURS = USABLE_CAPACITY_MAH / BASELINE_CURRENT_MA;  // ~29 hours

    private static final String BATTERY_PREFS = "BatteryEstimation";
    private static final String PREF_LAST_NOON_RESET = "last_noon_reset";

    private static final String CLEANUP_PREFS = "SessionCleanup";
    private static final String PREF_LAST_CLEANUP = "last_cleanup_timestamp";
    private static final long CLEANUP_INTERVAL_MS = 60 * 60 * 1000; // 1 hr

    // ML Status card views
    private ConstraintLayout mlStatusWidget;
    private ImageView mlStatusIcon;
    private TextView mlStatusText;
    private TextView mlStatusSuggestion;



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

        checkAndRunCleanup();


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

        // Initialize ML status card views
        mlStatusWidget = view.findViewById(R.id.ml_status_widget);
        mlStatusIcon = view.findViewById(R.id.ml_status_icon);
        mlStatusText = view.findViewById(R.id.ml_status_text);
        mlStatusSuggestion = view.findViewById(R.id.ml_status_suggestion);

        // http request init
        homeServerUrlInput = view.findViewById(R.id.home_server_url_input);
        mlInferenceSwitch = view.findViewById(R.id.ml_inference_switch);
        httpClient = new OkHttpClient();
        loadMLInferencePreferences();

        // Battery Text Init
        upperBatteryText = view.findViewById(R.id.upper_back_battery_percentage);
        lowerBatteryText = view.findViewById(R.id.lower_back_battery_percentage);
        upperBatteryIcon = view.findViewById(R.id.upper_back_battery_icon);
        lowerBatteryIcon = view.findViewById(R.id.lower_back_battery_icon);


        // Save preferences when URL changes
        homeServerUrlInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(android.text.Editable s) {
                // Save URL whenever user types
                saveServerURL(s.toString());
            }
        });

        // Save ML enabled state when switch changes
        mlInferenceSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            saveMLInferenceEnabled(isChecked);

            bluetoothViewModel.setMLInferenceEnabled(isChecked);

            if (isChecked) {
                String url = homeServerUrlInput.getText().toString().trim();
                if (url.isEmpty()) {
                    Toast.makeText(getContext(),
                            "⚠️ Please enter server URL first",
                            Toast.LENGTH_SHORT).show();
                    mlInferenceSwitch.setChecked(false);
                }
            }
        });

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

        // tracks connection status for battery depletion
        bluetoothViewModel.getConnectionStatus().observe(getViewLifecycleOwner(), status -> {
            // Check if it's a new day at noon - reset battery if needed
            checkAndResetBatteryAtNoon();

            // Update battery display based on current connection
            updateBatteryEstimates();
        });

        // Observe active time changes
        bluetoothViewModel.getActiveTime().observe(getViewLifecycleOwner(), time -> {
            if (time != null) {
                currentActiveTime = time;
                activeTimeValue.setText(String.format(Locale.US, "%.1f min", time));
                updateSlouchTime();
                updateDailyGoalProgress();
                updateBatteryEstimates();
            }
        });

        // ===== Observe cycle completion to trigger analysis AND ML inference =====
        bluetoothViewModel.getIsCycleComplete().observe(getViewLifecycleOwner(), isComplete -> {
            if (isComplete != null && isComplete) {
                String sessionId = bluetoothViewModel.getCompletedSessionId().getValue();
                Log.d(TAG, "Cycle completed! Session: " + sessionId);

                boolean mlEnabled = isMLInferenceEnabled();
                bluetoothViewModel.setMLInferenceEnabled(mlEnabled);

                new android.os.Handler().postDelayed(() -> {
                    if (isAdded() && sessionId != null) {
                        PostureAnalyzer analyzer = new PostureAnalyzer();
                        analyzer.analyzeSpecificSession(sessionId,
                                new PostureAnalyzer.OnAnalysisCompleteListener() {
                                    @Override
                                    public void onAnalysisComplete(int analyzed, int slouching) {
                                        if (!isAdded()) return;
                                        Log.d(TAG, "✓ Analyzed: " + analyzed + ", slouching: " + slouching);

                                        if (analyzed > 0) {
                                            boolean isSlouchingNow = slouching > 0;
                                            updateStatusCard(isSlouchingNow);
                                            loadTodaySlouchPercentage();

                                            // ✅ LED indicator
                                            if (isSlouchingNow) {
                                                sendSlouchIndicatorIfNeeded();
                                            }

                                            // ✅ Notification tracking
                                            if (isSlouchingNow) {
                                                consecutiveSlouchCount++;
                                                Log.d(TAG, "Consecutive slouch count: " + consecutiveSlouchCount);

                                                if (consecutiveSlouchCount == 1) {
                                                    sendSlouchNotification();
                                                }
                                            } else {
                                                if (consecutiveSlouchCount > 0) {
                                                    Log.d(TAG, "Posture corrected! Resetting counter");
                                                }
                                                consecutiveSlouchCount = 0;
                                            }
                                        }
                                    }

                                    @Override
                                    public void onAnalysisError(String error) {
                                        Log.e(TAG, "Analysis error: " + error);
                                    }
                                });
                    }
                }, 1000);
                // ✅ ML inference (if enabled)
                if (mlEnabled) {
                    new android.os.Handler().postDelayed(() -> {
                        if (isAdded()) {
                            sendInferenceToMLServer(sessionId);
                        }
                    }, 1500);
                }
            }
        });


        // Load today's slouch percentage from Firebase
        loadTodaySlouchPercentage();

        // Set up real-time status listener
        setupRealtimeStatusListener();

        // ✅ Create notification channel for posture alerts
        createNotificationChannel();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!hasNotificationPermission()) {
                Log.d(TAG, "Requesting notification permission...");
                requestNotificationPermission();
            }
        }
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
     * Run PostureAnalyzer and update status - OPTIMIZED
     * Uses analysis result directly instead of re-querying Firebase
     */
    /**
     * Run PostureAnalyzer and update status - WITH RETRY LOGIC
     */
    private void runAnalysisAndUpdateStatus() {
        runAnalysisAndUpdateStatus(true);  // Allow retry by default
    }

    private void runAnalysisAndUpdateStatus(boolean allowRetry) {
        String userId = FirebaseAuth.getInstance().getCurrentUser().getUid();

        if (userId == null) {
            Log.w(TAG, "Cannot run analysis - user not authenticated");
            return;
        }

        PostureAnalyzer analyzer = new PostureAnalyzer();
        analyzer.analyzeUnprocessedSessions(new PostureAnalyzer.OnAnalysisCompleteListener() {
            @Override
            public void onAnalysisComplete(int sessionsAnalyzed, int slouchingSessions) {
                if (!isAdded()) return;

                Log.d(TAG, "Analysis complete: " + sessionsAnalyzed + " sessions, " +
                        slouchingSessions + " slouching");

                // ✅ NEW: Retry logic if no sessions found
                if (sessionsAnalyzed == 0 && allowRetry) {
                    Log.d(TAG, "No sessions found - retrying in 1.5 seconds...");
                    new android.os.Handler().postDelayed(() -> {
                        if (isAdded()) {
                            Log.d(TAG, "Retry: Running analysis again...");
                            runAnalysisAndUpdateStatus(false);  // Don't allow infinite retries
                        }
                    }, 1500);
                    return;  // Don't update UI yet
                }

                if (sessionsAnalyzed == 0) {
                    Log.d(TAG, "No complete sessions to analyze");
                    return;
                }

                // ✅ Use result directly - no Firebase re-query needed!
                boolean isSlouchingNow = slouchingSessions > 0;

                // Update status card immediately
                updateStatusCard(isSlouchingNow);

                // Send LED indicator if slouching
                if (isSlouchingNow) {
                    sendSlouchIndicatorIfNeeded();
                }

                // Track consecutive slouching for notifications
                if (isSlouchingNow) {
                    consecutiveSlouchCount++;
                    Log.d(TAG, "Consecutive slouch count: " + consecutiveSlouchCount);

                    if (consecutiveSlouchCount == 1) {
                        sendSlouchNotification();
                    }
                } else {
                    if (consecutiveSlouchCount > 0) {
                        Log.d(TAG, "Posture corrected! Resetting counter");
                    }
                    consecutiveSlouchCount = 0;
                }
            }

            @Override
            public void onAnalysisError(String error) {
                if (!isAdded()) return;
                Log.e(TAG, "Analysis error: " + error);
            }
        });
    }

    /**
     * Send slouch indicator command to Upper Back device to turn on red LED
     */
    private void sendSlouchIndicatorIfNeeded() {
        // Only send to upper back device
        String deviceName = bluetoothViewModel.getDeviceName();

        if (deviceName != null && deviceName.equals("XIAO_Upper_Back")) {
            boolean sent = bluetoothViewModel.sendSlouchIndicator();

            if (sent) {
                Log.d(TAG, "✓ Slouch indicator command sent to device");
                // Optional: Show toast for debugging
                // Toast.makeText(getContext(), "⚠️ Slouch detected! Check red LED", Toast.LENGTH_SHORT).show();
            } else {
                Log.w(TAG, "✗ Failed to send slouch indicator");
            }
        }
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

    /**
     * Send current posture data to ML server for inference
     */
    /**
     * Send current posture data to ML server for inference
     */
    private void sendInferenceToMLServer(String sessionId) {
        String serverUrl = homeServerUrlInput.getText().toString().trim();

        if (serverUrl.isEmpty()) {
            Log.d(TAG, "ML inference disabled - no server URL");
            return;
        }

        if (sessionId == null) {
            Log.e(TAG, "ML inference - no session ID provided");
            return;
        }

        Log.d(TAG, "═══════════════════════════════════════════════════════════");
        Log.d(TAG, "ML INFERENCE for session: " + sessionId);
        Log.d(TAG, "═══════════════════════════════════════════════════════════");

        // Use the specific session ID
        bluetoothViewModel.generateInferenceJSONForSession(sessionId,
                new BluetoothViewModel.OnInferenceJSONGeneratedListener() {
                    @Override
                    public void onJSONGenerated(String json) {
                        Log.d(TAG, "Inference JSON generated, uploading to server...");

                        bluetoothViewModel.uploadInferenceData(serverUrl, json,
                                new BluetoothViewModel.OnInferenceUploadListener() {
                                    @Override
                                    public void onUploadSuccess(String response) {
                                        Log.d(TAG, "✅ ML Inference successful: " + response);

                                        try {
                                            org.json.JSONObject jsonResponse = new org.json.JSONObject(response);
                                            String finalPrediction = jsonResponse.optString("final_prediction", "Unknown");
                                            updateMLStatusCard(finalPrediction);
                                        } catch (org.json.JSONException e) {
                                            Log.e(TAG, "Error parsing ML response: " + e.getMessage());
                                            updateMLStatusCard("Error");
                                        }
                                    }

                                    @Override
                                    public void onUploadFailure(String error) {
                                        Log.e(TAG, "❌ ML Inference failed: " + error);
                                        updateMLStatusCard("Connection Error");
                                    }
                                });
                    }

                    @Override
                    public void onError(String error) {
                        Log.e(TAG, "Failed to generate inference JSON: " + error);
                    }
                });
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

//        Log.d(TAG, "Daily Goal: Upright=" + uprightTime + "min, " + percentage + "% of " + currentDailyGoal);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        // Remove Firebase listener to prevent crashes
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
                .limitToLast(1)
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
//            Log.d(TAG, "Slouch time: " + slouchTimeMinutes + " min");
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

    /**
     * Load saved ML inference preferences (server URL and enabled state)
     */
    private void loadMLInferencePreferences() {
        if (getContext() == null) return;

        android.content.SharedPreferences prefs = getContext().getSharedPreferences(
                PREFS_NAME,
                android.content.Context.MODE_PRIVATE
        );

        // Load server URL (default to your ngrok URL)
        String savedUrl = prefs.getString(
                PREF_SERVER_URL,
                "shocking-hatlike-leonila.ngrok-free.dev"  // ✅ Default URL
        );
        homeServerUrlInput.setText(savedUrl);

        // Load ML enabled state (default to false for safety)
        boolean mlEnabled = prefs.getBoolean(PREF_ML_ENABLED, false);
        mlInferenceSwitch.setChecked(mlEnabled);

        Log.d(TAG, "Loaded ML preferences - URL: " + savedUrl + ", Enabled: " + mlEnabled);
    }

    /**
     * Save server URL to SharedPreferences
     */
    private void saveServerURL(String url) {
        if (getContext() == null) return;

        android.content.SharedPreferences.Editor editor = getContext()
                .getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .edit();

        editor.putString(PREF_SERVER_URL, url);
        editor.apply();  // Use apply() for async save

        Log.d(TAG, "Saved server URL: " + url);
    }

    /**
     * Save ML inference enabled state to SharedPreferences
     */
    private void saveMLInferenceEnabled(boolean enabled) {
        if (getContext() == null) return;

        android.content.SharedPreferences.Editor editor = getContext()
                .getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .edit();

        editor.putBoolean(PREF_ML_ENABLED, enabled);
        editor.apply();

        Log.d(TAG, "Saved ML inference enabled: " + enabled);
    }

    /**
     * Create notification channel (required for Android 8.0+)
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = "Posture Alerts";
            String description = "Notifications for slouching detection";
            int importance = NotificationManager.IMPORTANCE_HIGH;  // High priority for heads-up notification

            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
            channel.setDescription(description);
            channel.enableVibration(true);
            channel.setVibrationPattern(new long[]{0, 500, 200, 500});  // Vibration pattern

            NotificationManager notificationManager = requireContext().getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
                Log.d(TAG, "✓ Notification channel created");
            }
        }
    }
    /**
     * Check if notification permission is granted (Android 13+)
     */
    private boolean hasNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(requireContext(),
                    Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        }
        return true;  // Pre-Android 13 doesn't need runtime permission
    }

    /**
     * Request notification permission (Android 13+)
     */
    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!hasNotificationPermission()) {
                ActivityCompat.requestPermissions(requireActivity(),
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        NOTIFICATION_PERMISSION_REQUEST_CODE);
            }
        }
    }

    /**
     * Handle permission request result
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == NOTIFICATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "✓ Notification permission granted");
                Toast.makeText(requireContext(), "Slouch notifications enabled", Toast.LENGTH_SHORT).show();
            } else {
                Log.w(TAG, "✗ Notification permission denied");
                Toast.makeText(requireContext(), "Notification permission denied. You won't receive slouch alerts.", Toast.LENGTH_LONG).show();
            }
        }
    }
    /**
     * Send push notification for slouching alert
     */
    private void sendSlouchNotification() {
        // Check permission first
        if (!hasNotificationPermission()) {
            Log.w(TAG, "Cannot send notification - permission not granted");
            requestNotificationPermission();  // Ask for permission
            return;
        }

        try {
            // Create intent to open app when notification is tapped
            Intent intent = new Intent(requireContext(), MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

            PendingIntent pendingIntent = PendingIntent.getActivity(
                    requireContext(),
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
            );

            // Build notification
            NotificationCompat.Builder builder = new NotificationCompat.Builder(requireContext(), CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)  // System alert icon
                    .setContentTitle("⚠️ Posture Alert")
                    .setContentText("You've been slouching! Time to sit up straight.")
                    .setStyle(new NotificationCompat.BigTextStyle()
                            .bigText("You've been slouching for 2 consecutive sessions. Take a moment to adjust your posture and stretch!"))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .setAutoCancel(true)  // Dismiss when tapped
                    .setContentIntent(pendingIntent)
                    .setVibrate(new long[]{0, 500, 200, 500});  // Vibration pattern

            // Send notification
            NotificationManagerCompat notificationManager = NotificationManagerCompat.from(requireContext());
            notificationManager.notify(NOTIFICATION_ID, builder.build());

            Log.d(TAG, "✅ Slouch notification sent");

        } catch (SecurityException e) {
            Log.e(TAG, "Security exception sending notification: " + e.getMessage());
            requestNotificationPermission();
        }
    }

    /**
     * Update battery estimates based on ACTUAL connection time (not just wall clock time)
     */
    private void updateBatteryEstimates() {
        if (!isAdded() || getContext() == null) {
            return;
        }

        // Get total connected minutes from Firebase activeTime
        // This already tracks cumulative connection time
        float totalConnectedMinutes = currentActiveTime;  // This is your existing activeTime tracking

        // Convert to hours
        float totalConnectedHours = totalConnectedMinutes / 60f;

        // Calculate battery percentage
        // Battery depletes based on ACTUAL connected time, not wall clock time
        float percentUsed = (totalConnectedHours / RUNTIME_HOURS) * 100f;
        int batteryPercent = Math.max(0, Math.min(100, (int)(100f - percentUsed)));

        // Update both sensors (same battery percentage since they share power source)
        updateSensorBattery(upperBatteryText, upperBatteryIcon, batteryPercent);
        updateSensorBattery(lowerBatteryText, lowerBatteryIcon, batteryPercent);

//        Log.d(TAG, String.format(Locale.US,
//                "Battery: %d%% (%.1f min connected / %.1f min total runtime)",
//                batteryPercent, totalConnectedMinutes, RUNTIME_HOURS * 60f));
    }


    /**
     * Update a single sensor's battery display
     */
    private void updateSensorBattery(TextView batteryText, ImageView batteryIcon, int percent) {
        if (batteryText == null || batteryIcon == null) {
            return;
        }

        batteryText.setText(percent + "%");

        // Color based on battery level
        int color;
        if (percent > 50) {
            color = ContextCompat.getColor(requireContext(), R.color.green);  // Green: >50%
        } else if (percent > 20) {
            color = ContextCompat.getColor(requireContext(), android.R.color.holo_orange_dark);  // Orange: 20-50%
        } else {
            color = ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark);  // Red: <20%
        }

        batteryText.setTextColor(color);
        batteryIcon.setColorFilter(color);
    }

    /**
     * Check if it's past noon and we haven't reset today yet
     * Reset battery to 100% (by resetting activeTime) at noon daily
     */
    private void checkAndResetBatteryAtNoon() {
        if (getContext() == null) return;

        android.content.SharedPreferences prefs = getContext().getSharedPreferences(
                BATTERY_PREFS,
                android.content.Context.MODE_PRIVATE
        );

        long currentTimeMillis = System.currentTimeMillis();
        long lastResetMillis = prefs.getLong(PREF_LAST_NOON_RESET, 0);

        // Get today's noon timestamp
        java.util.Calendar midnightToday = java.util.Calendar.getInstance();
        midnightToday.set(java.util.Calendar.HOUR_OF_DAY, 0);
        midnightToday.set(java.util.Calendar.MINUTE, 0);
        midnightToday.set(java.util.Calendar.SECOND, 0);
        midnightToday.set(java.util.Calendar.MILLISECOND, 0);

        long noonTodayMillis = midnightToday.getTimeInMillis();

        // Check if we've passed noon since last reset
        if (currentTimeMillis >= noonTodayMillis && lastResetMillis < noonTodayMillis) {
//            Log.d(TAG, "🔋 Noon reached - Resetting battery to 100%");

            // Reset via Firebase (same as your existing daily reset system)
            String userId = FirebaseAuth.getInstance().getCurrentUser().getUid();
            DatabaseReference userRef = FirebaseDatabase.getInstance()
                    .getReference("users")
                    .child(userId);

            userRef.child("active_time").setValue(0f)
                    .addOnSuccessListener(aVoid -> {
                        Log.d(TAG, "✓ Battery reset successful (active_time = 0)");

                        // Save reset timestamp
                        prefs.edit()
                                .putLong(PREF_LAST_NOON_RESET, currentTimeMillis)
                                .apply();

                        // Update battery display
                        currentActiveTime = 0f;
                        updateBatteryEstimates();

                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "✗ Failed to reset battery: " + e.getMessage());
                    });
        }
    }

    /**
     * Check if ML inference is currently enabled
     */
    private boolean isMLInferenceEnabled() {
        return mlInferenceSwitch != null && mlInferenceSwitch.isChecked();
    }

    private void checkAndRunCleanup() {
        if (getContext() == null) return;

        android.content.SharedPreferences prefs = getContext().getSharedPreferences(
                CLEANUP_PREFS, android.content.Context.MODE_PRIVATE);
        long lastCleanup = prefs.getLong(PREF_LAST_CLEANUP, 0);
        long currentTime = System.currentTimeMillis();

        if (currentTime - lastCleanup > CLEANUP_INTERVAL_MS) {
            Log.d(TAG, "Running session cleanup...");

            PostureAnalyzer analyzer = new PostureAnalyzer();
            analyzer.cleanupIncompleteSessions(new PostureAnalyzer.OnCleanupCompleteListener() {
                @Override
                public void onCleanupComplete(int deletedCount) {
                    Log.d(TAG, "✓ Cleaned up " + deletedCount + " incomplete sessions");
                    if (getContext() != null) {
                        prefs.edit().putLong(PREF_LAST_CLEANUP, System.currentTimeMillis()).apply();
                    }
                }

                @Override
                public void onCleanupError(String error) {
                    Log.e(TAG, "Cleanup error: " + error);
                }
            });
        }
    }

    /**
     * Update the ML status card based on prediction result
     * @param prediction The prediction string from ML server (e.g., "Sitting", "Standing", "Walking")
     */
    private void updateMLStatusCard(String prediction) {
        if (!isAdded() || getContext() == null) return;

        requireActivity().runOnUiThread(() -> {
            if (mlStatusText == null || mlStatusIcon == null || mlStatusSuggestion == null) return;

            Log.d(TAG, "Updating ML status card: " + prediction);

            mlStatusText.setText(prediction);

            // Set icon and colors based on prediction
            if (prediction.equalsIgnoreCase("Sitting")) {
                mlStatusIcon.setImageResource(android.R.drawable.ic_menu_myplaces);
                mlStatusIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.green));
                mlStatusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.green));
                mlStatusSuggestion.setText("Sitting posture detected");
            } else if (prediction.equalsIgnoreCase("Standing")) {
                mlStatusIcon.setImageResource(android.R.drawable.ic_menu_gallery);
                mlStatusIcon.setColorFilter(ContextCompat.getColor(requireContext(), android.R.color.holo_blue_dark));
                mlStatusText.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_blue_dark));
                mlStatusSuggestion.setText("Standing position detected");
            } else {
                // Unknown or other prediction
                mlStatusIcon.setImageResource(android.R.drawable.ic_menu_info_details);
                mlStatusIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.ios_text_secondary));
                mlStatusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.ios_text_secondary));
                mlStatusSuggestion.setText("Activity: " + prediction);
            }
        });
    }


}