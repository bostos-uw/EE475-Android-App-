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
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

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

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.SharedPreferences;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;


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
    private Query statusQuery;

    private float currentActiveTime = 0f;
    private int currentDailyGoal = 480;
    private double currentSlouchPercentage = 0.0;

    // ✅ ML Inference - RE-ENABLED
    private EditText homeServerUrlInput;
    private SwitchCompat mlInferenceSwitch;
    private static final String PREFS_NAME = "MLInferencePrefs";
    private static final String PREF_SERVER_URL = "server_url";
    private static final String PREF_ML_ENABLED = "ml_inference_enabled";

    // Slouch Notification System
    private static final String CHANNEL_ID = "posture_alerts";
    private static final int NOTIFICATION_ID = 1001;
    private static final int NOTIFICATION_PERMISSION_REQUEST_CODE = 100;
    private int consecutiveSlouchCount = 0;

    // Battery views
    private TextView upperBatteryText;
    private TextView lowerBatteryText;
    private ImageView upperBatteryIcon;
    private ImageView lowerBatteryIcon;

    // Battery estimation constants
    private static final float USABLE_CAPACITY_MAH = 850f;
    private static final float BASELINE_CURRENT_MA = 29f;
    private static final float RUNTIME_HOURS = USABLE_CAPACITY_MAH / BASELINE_CURRENT_MA;

    private static final String BATTERY_PREFS = "BatteryEstimation";
    private static final String PREF_LAST_NOON_RESET = "last_noon_reset";

    // Session Cleanup System
    private static final String CLEANUP_PREFS = "SessionCleanup";
    private static final String PREF_LAST_CLEANUP = "last_cleanup_timestamp";
    private static final long CLEANUP_INTERVAL_MS = 24 * 60 * 60 * 1000;

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
                }
            });

    private final ActivityResultLauncher<Intent> enableBluetoothLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    bluetoothViewModel.startCycle();
                }
            });

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
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

        // Status card views
        statusWidget = view.findViewById(R.id.status_widget);
        statusIcon = view.findViewById(R.id.status_icon);
        statusText = view.findViewById(R.id.status_text);
        statusSuggestion = view.findViewById(R.id.status_suggestion);

        // Initialize ML status card views
        mlStatusWidget = view.findViewById(R.id.ml_status_widget);
        mlStatusIcon = view.findViewById(R.id.ml_status_icon);
        mlStatusText = view.findViewById(R.id.ml_status_text);
        mlStatusSuggestion = view.findViewById(R.id.ml_status_suggestion);

        // ✅ ML Inference views
        homeServerUrlInput = view.findViewById(R.id.home_server_url_input);
        mlInferenceSwitch = view.findViewById(R.id.ml_inference_switch);
        loadMLInferencePreferences();

        // Battery views
        upperBatteryText = view.findViewById(R.id.upper_back_battery_percentage);
        lowerBatteryText = view.findViewById(R.id.lower_back_battery_percentage);
        upperBatteryIcon = view.findViewById(R.id.upper_back_battery_icon);
        lowerBatteryIcon = view.findViewById(R.id.lower_back_battery_icon);

        // ✅ ML Inference URL change listener
        homeServerUrlInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(android.text.Editable s) {
                saveServerURL(s.toString());
            }
        });

        // ✅ ML switch listener - update ViewModel IMMEDIATELY
        mlInferenceSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            bluetoothViewModel.setMLInferenceEnabled(isChecked);
            saveMLInferenceEnabled(isChecked);

            // ✅ UPDATE VIEWMODEL IMMEDIATELY so it knows for CURRENT cycle
            bluetoothViewModel.setMLInferenceEnabled(isChecked);

            if (isChecked) {
                String url = homeServerUrlInput.getText().toString().trim();
                if (url.isEmpty()) {
                    Toast.makeText(getContext(), "⚠️ Please enter server URL first", Toast.LENGTH_SHORT).show();
                    mlInferenceSwitch.setChecked(false);
                    bluetoothViewModel.setMLInferenceEnabled(false);
                } else {
                    Toast.makeText(getContext(), "✅ ML Inference enabled", Toast.LENGTH_SHORT).show();
                }
            }
        });

        // SharedViewModel for goal changes
        sharedViewModel = new ViewModelProvider(requireActivity()).get(SharedViewModel.class);
        sharedViewModel.getDailyGoal().observe(getViewLifecycleOwner(), goal -> {
            if (goal != null) {
                currentDailyGoal = goal;
                updateDailyGoal(goal);
                updateDailyGoalProgress();
            }
        });

        bluetoothViewModel = new ViewModelProvider(requireActivity()).get(BluetoothViewModel.class);
        // ✅ Sync ML inference state to ViewModel immediately on load
        bluetoothViewModel.setMLInferenceEnabled(isMLInferenceEnabled());
        Log.d(TAG, "Synced ML state to ViewModel on load: " + isMLInferenceEnabled());

        updateButtonState(bluetoothViewModel.getConnectionStatus().getValue());
        bluetoothViewModel.getConnectionStatus().observe(getViewLifecycleOwner(), this::updateButtonState);

        bluetoothViewModel.getConnectionStatus().observe(getViewLifecycleOwner(), status -> {
            checkAndResetBatteryAtNoon();
            updateBatteryEstimates();
        });

        bluetoothViewModel.getActiveTime().observe(getViewLifecycleOwner(), time -> {
            if (time != null) {
                currentActiveTime = time;
                activeTimeValue.setText(String.format(Locale.US, "%.1f min", time));
                updateSlouchTime();
                updateDailyGoalProgress();
                updateBatteryEstimates();
            }
        });

        // ✅ Observe cycle completion - trigger analysis AND ML inference
        bluetoothViewModel.getIsCycleComplete().observe(getViewLifecycleOwner(), isComplete -> {
            if (isComplete != null && isComplete) {
                Log.d(TAG, "Cycle completed! Triggering analysis...");

                // ✅ Tell BluetoothViewModel if ML is enabled (so it saves arrays)
                boolean mlEnabled = isMLInferenceEnabled();
                bluetoothViewModel.setMLInferenceEnabled(mlEnabled);
                Log.d(TAG, "ML Inference enabled: " + mlEnabled);

                // Run analysis after short delay
                new android.os.Handler().postDelayed(() -> {
                    if (isAdded()) {
                        runAnalysisAndUpdateStatus();

                        // ✅ Run ML inference AFTER analysis (if enabled)
                        if (mlEnabled) {
                            new android.os.Handler().postDelayed(() -> {
                                if (isAdded()) {
                                    sendInferenceToMLServer();
                                }
                            }, 2000);  // Wait for arrays to be saved
                        }
                    }
                }, 1500);
            }
        });

        loadTodaySlouchPercentage();
        setupRealtimeStatusListener();
        createNotificationChannel();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!hasNotificationPermission()) {
                requestNotificationPermission();
            }
        }

        checkAndRunCleanup();
    }

    /**
     * ✅ Send inference to ML server - RE-ENABLED
     */
    /**
     * Send current posture data to ML server for inference
     */
    private void sendInferenceToMLServer() {
        String serverUrl = homeServerUrlInput.getText().toString().trim();

        if (serverUrl.isEmpty()) {
            Log.d(TAG, "ML inference disabled - no server URL");
            return;
        }

        Log.d(TAG, "Generating inference JSON...");

        bluetoothViewModel.generateInferenceJSON(new BluetoothViewModel.OnInferenceJSONGeneratedListener() {
            @Override
            public void onJSONGenerated(String json) {
                Log.d(TAG, "Inference JSON generated, uploading to server...");

                bluetoothViewModel.uploadInferenceData(serverUrl, json,
                        new BluetoothViewModel.OnInferenceUploadListener() {
                            @Override
                            public void onUploadSuccess(String response) {
                                Log.d(TAG, "✅ ML Inference successful: " + response);

                                if (isAdded() && getActivity() != null) {
                                    getActivity().runOnUiThread(() -> {
                                        // ✅ Parse response and update ML status card
                                        try {
                                            org.json.JSONObject jsonResponse = new org.json.JSONObject(response);
                                            String prediction = jsonResponse.optString("final_prediction", null);
                                            updateMLStatusCard(prediction);
                                        } catch (Exception e) {
                                            Log.e(TAG, "Error parsing ML response: " + e.getMessage());
                                            updateMLStatusCard(null);
                                        }

                                        // Toast commented out for minimalism
                                        // Toast.makeText(getContext(), "ML: " + response, Toast.LENGTH_SHORT).show();
                                    });
                                }
                            }

                            @Override
                            public void onUploadFailure(String error) {
                                Log.e(TAG, "❌ ML Inference failed: " + error);

                                // Toast commented out for minimalism
                                // if (isAdded() && getActivity() != null) {
                                //     getActivity().runOnUiThread(() -> {
                                //         Toast.makeText(getContext(), "ML failed: " + error, Toast.LENGTH_SHORT).show();
                                //     });
                                // }
                            }
                        });
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "Failed to generate inference JSON: " + error);

                // Toast commented out for minimalism
                // if (isAdded() && getActivity() != null) {
                //     getActivity().runOnUiThread(() -> {
                //         Toast.makeText(getContext(), "ML error: " + error, Toast.LENGTH_SHORT).show();
                //     });
                // }
            }
        });
    }

    /**
     * Check if ML inference is enabled
     */
    private boolean isMLInferenceEnabled() {
        return mlInferenceSwitch != null && mlInferenceSwitch.isChecked();
    }

    /**
     * Load ML inference preferences
     */
    private void loadMLInferencePreferences() {
        if (getContext() == null) return;

        SharedPreferences prefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        String savedUrl = prefs.getString(PREF_SERVER_URL, "");
        homeServerUrlInput.setText(savedUrl);

        boolean mlEnabled = prefs.getBoolean(PREF_ML_ENABLED, false);
        mlInferenceSwitch.setChecked(mlEnabled);

        Log.d(TAG, "Loaded ML prefs - URL: " + savedUrl + ", Enabled: " + mlEnabled);
    }

    /**
     * Save server URL
     */
    private void saveServerURL(String url) {
        if (getContext() == null) return;
        getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_SERVER_URL, url)
                .apply();
    }

    /**
     * Save ML enabled state
     */
    private void saveMLInferenceEnabled(boolean enabled) {
        if (getContext() == null) return;
        getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_ML_ENABLED, enabled)
                .apply();
        Log.d(TAG, "Saved ML enabled: " + enabled);
    }

    /**
     * Run analysis - OPTIMIZED (uses callback result directly)
     */
    private void runAnalysisAndUpdateStatus() {
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

                Log.d(TAG, "Analysis: " + sessionsAnalyzed + " sessions, " + slouchingSessions + " slouching");

                if (sessionsAnalyzed == 0) {
                    Log.d(TAG, "No complete sessions to analyze");
                    return;
                }

                // Use result directly - no Firebase query needed
                boolean isSlouchingNow = slouchingSessions > 0;

                updateStatusCard(isSlouchingNow);

                if (isSlouchingNow) {
                    sendSlouchIndicatorIfNeeded();
                }

                // Track consecutive slouching
                if (isSlouchingNow) {
                    consecutiveSlouchCount++;
                    if (consecutiveSlouchCount == 1) {
                        sendSlouchNotification();
                    }
                } else {
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

    private void checkAndRunCleanup() {
        if (getContext() == null) return;

        SharedPreferences prefs = getContext().getSharedPreferences(CLEANUP_PREFS, Context.MODE_PRIVATE);
        long lastCleanup = prefs.getLong(PREF_LAST_CLEANUP, 0);
        long currentTime = System.currentTimeMillis();

        if (currentTime - lastCleanup > CLEANUP_INTERVAL_MS) {
            Log.d(TAG, "Running session cleanup...");

            PostureAnalyzer analyzer = new PostureAnalyzer();
            analyzer.cleanupIncompleteSessions(new PostureAnalyzer.OnCleanupCompleteListener() {
                @Override
                public void onCleanupComplete(int deletedCount) {
                    Log.d(TAG, "✓ Cleaned " + deletedCount + " sessions");
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

    private void setupRealtimeStatusListener() {
        String userId = FirebaseAuth.getInstance().getCurrentUser().getUid();

        if (statusQuery != null && statusListener != null) {
            statusQuery.removeEventListener(statusListener);
        }

        statusQuery = FirebaseDatabase.getInstance()
                .getReference("posture_sessions")
                .child(userId)
                .orderByChild("analyzed")
                .equalTo(true)
                .limitToLast(1);

        statusListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!isAdded() || getContext() == null) return;

                if (snapshot.exists() && snapshot.getChildrenCount() > 0) {
                    for (DataSnapshot sessionSnapshot : snapshot.getChildren()) {
                        PostureSession session = sessionSnapshot.getValue(PostureSession.class);
                        if (session != null) {
                            updateStatusCard(session.slouching);
                        }
                    }
                } else {
                    updateStatusCard(null);
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Status listener error: " + error.getMessage());
            }
        };

        statusQuery.addValueEventListener(statusListener);
    }

    private void sendSlouchIndicatorIfNeeded() {
        String deviceName = bluetoothViewModel.getDeviceName();
        if (deviceName != null && deviceName.equals("XIAO_Upper_Back")) {
            bluetoothViewModel.sendSlouchIndicator();
        }
    }

    private void updateStatusCard(Boolean isSlouchingNow) {
        if (!isAdded() || getContext() == null) return;

        if (isSlouchingNow == null) {
            statusWidget.setBackgroundResource(R.drawable.rounded_corner_white);
            statusIcon.setImageResource(android.R.drawable.ic_menu_info_details);
            statusIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.ios_text_secondary));
            statusText.setText("Unknown");
            statusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.ios_text_secondary));
            statusSuggestion.setText("No posture data yet");
            statusSuggestion.setTextColor(ContextCompat.getColor(requireContext(), R.color.ios_text_secondary));
        } else if (isSlouchingNow) {
            statusWidget.setBackgroundResource(R.drawable.rounded_corner_orange);
            statusIcon.setImageResource(android.R.drawable.ic_dialog_alert);
            statusIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.white));
            statusText.setText("Slouching");
            statusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.white));
            statusSuggestion.setText("Consider sitting up straight");
            statusSuggestion.setTextColor(ContextCompat.getColor(requireContext(), R.color.white));
        } else {
            statusWidget.setBackgroundResource(R.drawable.rounded_corner_green);
            statusIcon.setImageResource(android.R.drawable.checkbox_on_background);
            statusIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.white));
            statusText.setText("Good Posture");
            statusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.white));
            statusSuggestion.setText("Keep it up!");
            statusSuggestion.setTextColor(ContextCompat.getColor(requireContext(), R.color.white));
        }
    }

    private void updateButtonState(String status) {
        connectionStatusText.setText(status);
        if (status == null) status = "Disconnected";

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
            percentage = Math.min(100, (int)((uprightTime / currentDailyGoal) * 100));
        }

        dailyGoalValue.setText(String.valueOf((int) uprightTime));
        dailyGoalPercentage.setText(percentage + "%");
        dailyGoalProgress.setMax(100);
        dailyGoalProgress.setProgress(percentage);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (statusQuery != null && statusListener != null) {
            statusQuery.removeEventListener(statusListener);
        }
    }

    private void loadTodaySlouchPercentage() {
        String userId = FirebaseAuth.getInstance().getCurrentUser().getUid();
        String todayKey = getTodayDateKey();

        FirebaseDatabase.getInstance()
                .getReference("daily_stats")
                .child(userId)
                .child(todayKey)
                .addValueEventListener(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        if (snapshot.exists()) {
                            Double slouchPercentage = snapshot.child("slouch_percentage").getValue(Double.class);
                            if (slouchPercentage != null) {
                                currentSlouchPercentage = slouchPercentage;
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
                        Log.e(TAG, "Error loading slouch %: " + error.getMessage());
                    }
                });
    }

    @Override
    public void onResume() {
        super.onResume();
        if (bluetoothViewModel != null) {
            bluetoothViewModel.performInitialLoadAndDailyCheck();
        }
    }

    private void updateSlouchTime() {
        float slouchTimeMinutes = currentActiveTime * (float)(currentSlouchPercentage / 100.0);
        if (slouchTimeValue != null) {
            slouchTimeValue.setText(String.format(Locale.US, "%.1f min", slouchTimeMinutes));
        }
    }

    private String getTodayDateKey() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    private void requestBluetoothPermissions() {
        String[] permissions = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ?
                new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT} :
                new String[]{Manifest.permission.ACCESS_FINE_LOCATION};

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
            enableBluetoothLauncher.launch(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
        } else {
            bluetoothViewModel.startCycle();
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Posture Alerts", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Notifications for slouching detection");
            channel.enableVibration(true);

            NotificationManager notificationManager = requireContext().getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    private boolean hasNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(requireContext(),
                    Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(requireActivity(),
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    NOTIFICATION_PERMISSION_REQUEST_CODE);
        }
    }

    private void sendSlouchNotification() {
        if (!hasNotificationPermission()) return;

        try {
            Intent intent = new Intent(requireContext(), MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

            PendingIntent pendingIntent = PendingIntent.getActivity(requireContext(), 0, intent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

            NotificationCompat.Builder builder = new NotificationCompat.Builder(requireContext(), CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle("⚠️ Posture Alert")
                    .setContentText("You're slouching! Time to sit up straight.")
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent);

            NotificationManagerCompat.from(requireContext()).notify(NOTIFICATION_ID, builder.build());
        } catch (SecurityException e) {
            Log.e(TAG, "Notification error: " + e.getMessage());
        }
    }

    private void updateBatteryEstimates() {
        if (!isAdded() || getContext() == null) return;

        float totalConnectedHours = currentActiveTime / 60f;
        float percentUsed = (totalConnectedHours / RUNTIME_HOURS) * 100f;
        int batteryPercent = Math.max(0, Math.min(100, (int)(100f - percentUsed)));

        updateSensorBattery(upperBatteryText, upperBatteryIcon, batteryPercent);
        updateSensorBattery(lowerBatteryText, lowerBatteryIcon, batteryPercent);
    }

    private void updateSensorBattery(TextView batteryText, ImageView batteryIcon, int percent) {
        if (batteryText == null || batteryIcon == null) return;

        batteryText.setText(percent + "%");

        int color = percent > 50 ? ContextCompat.getColor(requireContext(), R.color.green) :
                percent > 20 ? ContextCompat.getColor(requireContext(), android.R.color.holo_orange_dark) :
                        ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark);

        batteryText.setTextColor(color);
        batteryIcon.setColorFilter(color);
    }

    private void checkAndResetBatteryAtNoon() {
        if (getContext() == null) return;

        SharedPreferences prefs = getContext().getSharedPreferences(BATTERY_PREFS, Context.MODE_PRIVATE);
        long currentTimeMillis = System.currentTimeMillis();
        long lastResetMillis = prefs.getLong(PREF_LAST_NOON_RESET, 0);

        java.util.Calendar midnight = java.util.Calendar.getInstance();
        midnight.set(java.util.Calendar.HOUR_OF_DAY, 0);
        midnight.set(java.util.Calendar.MINUTE, 0);
        midnight.set(java.util.Calendar.SECOND, 0);
        midnight.set(java.util.Calendar.MILLISECOND, 0);

        long noonToday = midnight.getTimeInMillis();

        if (currentTimeMillis >= noonToday && lastResetMillis < noonToday) {
            String userId = FirebaseAuth.getInstance().getCurrentUser().getUid();
            FirebaseDatabase.getInstance().getReference("users").child(userId)
                    .child("active_time").setValue(0f)
                    .addOnSuccessListener(aVoid -> {
                        prefs.edit().putLong(PREF_LAST_NOON_RESET, currentTimeMillis).apply();
                        currentActiveTime = 0f;
                        updateBatteryEstimates();
                    });
        }
    }

    /**
     * Update the ML status card based on prediction (sitting/standing)
     * @param prediction "sitting", "standing", or null for unknown
     */
    private void updateMLStatusCard(String prediction) {
        if (!isAdded() || getContext() == null) {
            return;
        }

        Log.d(TAG, "Updating ML status card: prediction = " + prediction);

        if (prediction == null || prediction.isEmpty()) {
            // No prediction - show grey/neutral state
            mlStatusWidget.setBackgroundResource(R.drawable.rounded_corner_white);
            mlStatusIcon.setImageResource(android.R.drawable.ic_menu_info_details);
            mlStatusIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.ios_text_secondary));
            mlStatusText.setText("Unknown");
            mlStatusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.ios_text_secondary));
            mlStatusSuggestion.setText("Waiting for ML prediction");
            mlStatusSuggestion.setTextColor(ContextCompat.getColor(requireContext(), R.color.ios_text_secondary));

        } else if (prediction.equalsIgnoreCase("sitting")) {
            // SITTING - Blue background
            mlStatusWidget.setBackgroundResource(R.drawable.rounded_corner_orange);
            mlStatusIcon.setImageResource(android.R.drawable.ic_menu_myplaces);
            mlStatusIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.white));
            mlStatusText.setText("Sitting");
            mlStatusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.white));
            mlStatusSuggestion.setText("Remember to take standing breaks");
            mlStatusSuggestion.setTextColor(ContextCompat.getColor(requireContext(), R.color.white));

        } else if (prediction.equalsIgnoreCase("standing")) {
            // STANDING - Purple background
            mlStatusWidget.setBackgroundResource(R.drawable.rounded_corner_green);
            mlStatusIcon.setImageResource(android.R.drawable.ic_menu_compass);
            mlStatusIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.white));
            mlStatusText.setText("Standing");
            mlStatusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.white));
            mlStatusSuggestion.setText("Great! Standing is good for you");
            mlStatusSuggestion.setTextColor(ContextCompat.getColor(requireContext(), R.color.white));

        } else {
            // Unknown prediction value
            mlStatusWidget.setBackgroundResource(R.drawable.rounded_corner_white);
            mlStatusIcon.setImageResource(android.R.drawable.ic_menu_help);
            mlStatusIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.ios_text_secondary));
            mlStatusText.setText(prediction);
            mlStatusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.ios_text_secondary));
            mlStatusSuggestion.setText("Unrecognized activity");
            mlStatusSuggestion.setTextColor(ContextCompat.getColor(requireContext(), R.color.ios_text_secondary));
        }
    }

}