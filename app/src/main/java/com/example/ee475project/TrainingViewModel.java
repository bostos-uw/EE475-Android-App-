package com.example.ee475project;

import android.annotation.SuppressLint;
import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Locale;


import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.Map;



/**
 * ViewModel for ML Training Mode
 * - Uses HIGH priority BLE for faster sampling (~16Hz)
 * - Buffers ALL sensor readings in memory
 * - 2-minute cycles per sensor
 * - Timestamp shifting for alignment
 * - No Firebase writes during collection
 */
@SuppressLint("MissingPermission")
public class TrainingViewModel extends AndroidViewModel {

    private final BluetoothAdapter bluetoothAdapter;
    private final BluetoothLeScanner bluetoothLeScanner;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final MutableLiveData<String> connectionStatus = new MutableLiveData<>("Disconnected");
    private final MutableLiveData<Boolean> isConnected = new MutableLiveData<>(false);
    private final MutableLiveData<Integer> collectionProgress = new MutableLiveData<>(0); // 0-100%
    private final MutableLiveData<String> currentPhase = new MutableLiveData<>("Ready"); // "Upper", "Lower", "Complete"

    private final StringBuilder dataBuffer = new StringBuilder();
    private BluetoothGatt bluetoothGatt;

    private static final String TAG = "TrainingViewModel";
    private static final String DEVICE_NAME_UPPER = "XIAO_Upper_Back";
    private static final String DEVICE_NAME_LOWER = "XIAO_Lower_Back";
    private static final long SCAN_PERIOD = 10000; // 10 seconds
    private static final long CONNECTION_TIME = 120000; // 2 MINUTES (120 seconds)

    // Nordic UART Service UUIDs
    private static final UUID UART_SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID UART_TX_CHARACTERISTIC_UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID CLIENT_CHARACTERISTIC_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final ScanCallback leScanCallback;
    private final Runnable stopScanRunnable;
    private boolean isCycling = false;
    private int currentDeviceIndex = 0;
    private final String[] deviceNames = {DEVICE_NAME_UPPER, DEVICE_NAME_LOWER};

    // IMU Data parsing
    private float tempAccelX, tempAccelY, tempAccelZ;
    private float tempGyroX, tempGyroY, tempGyroZ;
    private boolean hasAccelData = false;
    private boolean hasGyroData = false;
    private String currentSensor = "";

    // ===== TRAINING DATA BUFFERS =====
    private final ArrayList<SensorReading> upperBackBuffer = new ArrayList<>();
    private final ArrayList<SensorReading> lowerBackBuffer = new ArrayList<>();
    private long upperBackStartTime = 0;
    private long lowerBackStartTime = 0;

    // Progress tracking
    private long phaseStartTime = 0;
    private Handler progressHandler = new Handler(Looper.getMainLooper());
    private Runnable progressUpdateRunnable;

    private String selectedPoseLabel = "";

    // Firebase for persistence
    private DatabaseReference databaseReference;
    private String currentUserId = null;

    // Store training data for ALL poses
    private Map<String, PoseData> allTrainingData = new HashMap<>();
    // Data class to hold upper + lower back data for one pose

    public static class PoseData {
        public ArrayList<SensorReading> upperBackData;
        public ArrayList<SensorReading> lowerBackData;
        public long collectionTimestamp;

        // ✅ ADD METADATA FIELDS
        public float sampleRateHz;
        public float durationSeconds;

        public PoseData() {
            this.upperBackData = new ArrayList<>();
            this.lowerBackData = new ArrayList<>();
            this.collectionTimestamp = System.currentTimeMillis();
            this.sampleRateHz = 0;
            this.durationSeconds = 0;
        }

        public PoseData(ArrayList<SensorReading> upper, ArrayList<SensorReading> lower) {
            this.upperBackData = new ArrayList<>(upper);
            this.lowerBackData = new ArrayList<>(lower);
            this.collectionTimestamp = System.currentTimeMillis();
            this.sampleRateHz = 0;
            this.durationSeconds = 0;
        }
    }



    private Runnable autoDisconnectRunnable = null;  // Track auto-disconnect handler





    /**
     * Data class for buffered sensor readings
     */
    public static class SensorReading {
        public long timestamp;
        public float accelX, accelY, accelZ;
        public float gyroX, gyroY, gyroZ;

        public SensorReading(long timestamp, float ax, float ay, float az, float gx, float gy, float gz) {
            this.timestamp = timestamp;
            this.accelX = ax;
            this.accelY = ay;
            this.accelZ = az;
            this.gyroX = gx;
            this.gyroY = gy;
            this.gyroZ = gz;
        }
    }

    public TrainingViewModel(@NonNull Application application) {
        super(application);
        BluetoothManager bluetoothManager = (BluetoothManager) application.getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();
        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();

        // Initialize Firebase
        databaseReference = FirebaseDatabase.getInstance().getReference();

        // Get current user ID
        if (FirebaseAuth.getInstance().getCurrentUser() != null) {
            currentUserId = FirebaseAuth.getInstance().getCurrentUser().getUid();
            loadTrainingDataFromFirebase();
        }

        leScanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                super.onScanResult(callbackType, result);
                BluetoothDevice device = result.getDevice();
                if (device != null && device.getName() != null) {
                    if (isCycling && device.getName().equals(deviceNames[currentDeviceIndex])) {
                        handler.removeCallbacks(stopScanRunnable);
                        bluetoothLeScanner.stopScan(leScanCallback);
                        connectToDevice(device);
                    }
                }
            }
        };

        stopScanRunnable = () -> {
            if (bluetoothLeScanner != null) {
                bluetoothLeScanner.stopScan(leScanCallback);
                if (connectionStatus.getValue() != null && connectionStatus.getValue().startsWith("Scanning")) {
                    connectionStatus.setValue("Scan timeout - device not found");
                }
            }
        };
    }

    // LiveData getters
    public LiveData<String> getConnectionStatus() { return connectionStatus; }
    public LiveData<Boolean> getIsConnected() { return isConnected; }
    public LiveData<Integer> getCollectionProgress() { return collectionProgress; }
    public LiveData<String> getCurrentPhase() { return currentPhase; }

    /**
     * Start the 2-cycle training data collection
     */
    public void startTrainingCollection() {
        Log.d(TAG, "Starting ML training collection (2 minutes per sensor)");

        // Clear previous data
        upperBackBuffer.clear();
        lowerBackBuffer.clear();
        upperBackStartTime = 0;
        lowerBackStartTime = 0;

        isCycling = true;
        currentDeviceIndex = 0;
        collectionProgress.setValue(0);
        currentPhase.setValue("Preparing...");

        scanForNextDevice();
    }

    /**
     * Cancel ongoing training collection
     */
    public void cancelTraining() {
        Log.d(TAG, "Training collection cancelled");
        isCycling = false;

        // ✅ FIX: Remove ALL scheduled handlers
        handler.removeCallbacksAndMessages(null);  // Nuclear option - removes ALL handlers

        if (bluetoothLeScanner != null) {
            try {
                bluetoothLeScanner.stopScan(leScanCallback);
            } catch (Exception e) {
                Log.w(TAG, "Error stopping scan: " + e.getMessage());
            }
        }

        if (bluetoothGatt != null) {
            try {
                bluetoothGatt.disconnect();
                bluetoothGatt.close();
                bluetoothGatt = null;
            } catch (Exception e) {
                Log.w(TAG, "Error disconnecting GATT: " + e.getMessage());
            }
        }

        stopProgressUpdates();
        autoDisconnectRunnable = null;
        currentPhase.setValue("Cancelled");
        collectionProgress.setValue(0);
    }

    private void scanForNextDevice() {
        if (!isCycling) {
            Log.w(TAG, "scanForNextDevice called but cycling is false");
            return;
        }

        // ✅ FIX: Safety check
        if (bluetoothLeScanner == null) {
            Log.e(TAG, "BluetoothLeScanner is null!");
            return;
        }

        String deviceName = deviceNames[currentDeviceIndex];
        Log.d(TAG, "Starting scan for device: " + deviceName);

        List<ScanFilter> filters = new ArrayList<>();
        filters.add(new ScanFilter.Builder().setDeviceName(deviceName).build());

        ScanSettings scanSettings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        try {
            bluetoothLeScanner.startScan(filters, scanSettings, leScanCallback);
            connectionStatus.setValue("Scanning for " + deviceName);

            // Update phase
            if (currentDeviceIndex == 0) {
                currentPhase.setValue("Upper Back");
            } else {
                currentPhase.setValue("Lower Back");
            }

            handler.postDelayed(stopScanRunnable, SCAN_PERIOD);
        } catch (Exception e) {
            Log.e(TAG, "Error starting scan: " + e.getMessage(), e);
            connectionStatus.setValue("Scan failed: " + e.getMessage());
        }
    }

    private void connectToDevice(BluetoothDevice device) {
        if (device != null) {
            connectionStatus.setValue("Connecting to " + device.getName());
            bluetoothGatt = device.connectGatt(getApplication(), false, gattCallback);
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            String deviceName = gatt.getDevice().getName();

            if (newState == BluetoothGatt.STATE_CONNECTED) {
                connectionStatus.postValue("Connected to " + deviceName);
                isConnected.postValue(true);

                bluetoothGatt = gatt;

                // ===== REQUEST HIGH PRIORITY FOR TRAINING =====
                boolean success = gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                Log.d(TAG, "Requested HIGH priority for training: " + (success ? "SUCCESS" : "FAILED"));

                gatt.discoverServices();

                // Mark start time for this sensor
                if (deviceName.equals(DEVICE_NAME_UPPER)) {
                    upperBackStartTime = System.currentTimeMillis();
                    Log.d(TAG, "Upper back collection started at t=" + upperBackStartTime);
                } else if (deviceName.equals(DEVICE_NAME_LOWER)) {
                    lowerBackStartTime = System.currentTimeMillis();
                    Log.d(TAG, "Lower back collection started at t=" + lowerBackStartTime);
                }

                // Start progress updates
                phaseStartTime = System.currentTimeMillis();
                startProgressUpdates();

                // ✅ FIX: Store the auto-disconnect runnable so we can cancel it later
                if (isCycling) {
                    // Cancel any existing auto-disconnect handler first
                    if (autoDisconnectRunnable != null) {
                        handler.removeCallbacks(autoDisconnectRunnable);
                        Log.d(TAG, "Removed previous auto-disconnect handler");
                    }

                    // Create new auto-disconnect handler
                    autoDisconnectRunnable = () -> {
                        Log.d(TAG, "Auto-disconnect triggered for " + deviceName);
                        gatt.disconnect();
                    };

                    handler.postDelayed(autoDisconnectRunnable, CONNECTION_TIME);
                    Log.d(TAG, "Scheduled auto-disconnect in " + (CONNECTION_TIME/1000) + " seconds");
                }

            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                Log.d(TAG, "════════════════════════════════════════");
                Log.d(TAG, "DISCONNECT EVENT for " + deviceName);
                Log.d(TAG, "Status code: " + status);

                // ✅ FIX: Cancel auto-disconnect handler immediately
                if (autoDisconnectRunnable != null) {
                    handler.removeCallbacks(autoDisconnectRunnable);
                    autoDisconnectRunnable = null;
                    Log.d(TAG, "Cancelled auto-disconnect handler");
                }

                // ✅ FIX: Cancel any pending scan timeout
                handler.removeCallbacks(stopScanRunnable);

                // Clear data buffer
                synchronized (dataBuffer) {
                    dataBuffer.setLength(0);
                }

                connectionStatus.postValue("Disconnected from " + deviceName);
                isConnected.postValue(false);

                // ✅ Disable notifications before closing
                try {
                    BluetoothGattCharacteristic characteristic = gatt.getService(UART_SERVICE_UUID)
                            .getCharacteristic(UART_TX_CHARACTERISTIC_UUID);
                    if (characteristic != null) {
                        gatt.setCharacteristicNotification(characteristic, false);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Error disabling notifications: " + e.getMessage());
                }

                gatt.close();
                Log.d(TAG, "GATT closed for " + deviceName);

                stopProgressUpdates();

                if (bluetoothGatt == gatt) {
                    bluetoothGatt = null;
                }

                if (isCycling) {
                    Log.d(TAG, "Current device index: " + currentDeviceIndex);
                    Log.d(TAG, "Upper back buffer size: " + upperBackBuffer.size());
                    Log.d(TAG, "Lower back buffer size: " + lowerBackBuffer.size());

                    // Move to next sensor
                    currentDeviceIndex = (currentDeviceIndex + 1) % deviceNames.length;
                    Log.d(TAG, "Next device index: " + currentDeviceIndex);

                    if (currentDeviceIndex == 0) {
                        // Cycle complete - both sensors done
                        Log.d(TAG, "✓ Training collection complete!");
                        Log.d(TAG, "  Upper back readings: " + upperBackBuffer.size());
                        Log.d(TAG, "  Lower back readings: " + lowerBackBuffer.size());

                        isCycling = false;
                        currentPhase.postValue("Complete");
                        collectionProgress.postValue(100);

                        applyTimestampShifting();
                        saveCurrentPoseToFirebase();
                    } else {
                        // Continue to next sensor (lower back)
                        Log.d(TAG, "Transitioning to next sensor: " + deviceNames[currentDeviceIndex]);
                        Log.d(TAG, "Waiting 2 seconds for BLE stack to settle...");

                        // ✅ FIX: Increased delay from 1s to 2s for better reliability
                        handler.postDelayed(() -> {
                            if (isCycling) {
                                Log.d(TAG, "BLE stack settled, starting scan for: " + deviceNames[currentDeviceIndex]);
                                scanForNextDevice();
                            } else {
                                Log.w(TAG, "Cycling was cancelled during delay");
                            }
                        }, 2000);  // ✅ Changed from 1000 to 2000ms
                    }
                }
                Log.d(TAG, "════════════════════════════════════════");
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                BluetoothGattCharacteristic characteristic = gatt.getService(UART_SERVICE_UUID)
                        .getCharacteristic(UART_TX_CHARACTERISTIC_UUID);
                if (characteristic != null) {
                    gatt.setCharacteristicNotification(characteristic, true);
                    BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG);
                    if (descriptor != null) {
                        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        gatt.writeDescriptor(descriptor);
                    }
                }
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            byte[] data = characteristic.getValue();
            String dataChunk = new String(data);

            synchronized (dataBuffer) {
                dataBuffer.append(dataChunk);

                String bufferContent = dataBuffer.toString();
                if (bufferContent.contains("\r") || bufferContent.contains("\n")) {
                    bufferContent = bufferContent.replace("\r\n", "\n").replace("\r", "\n");
                    dataBuffer.setLength(0);
                    dataBuffer.append(bufferContent);

                    int newlineIndex;
                    while ((newlineIndex = dataBuffer.indexOf("\n")) != -1) {
                        final String dataString = dataBuffer.substring(0, newlineIndex).trim();
                        dataBuffer.delete(0, newlineIndex + 1);

                        if (dataString.isEmpty()) {
                            continue;
                        }

                        parseAndBufferMessage(dataString);
                    }
                }
            }
        }
    };

    /**
     * Parse incoming sensor data and add to buffers
     */
    private void parseAndBufferMessage(String dataString) {
        try {
            String[] parts = dataString.split("\\|");

            if (parts.length != 2) {
                return;
            }

            String identifier = parts[0];
            String dataType = parts[1].substring(0, 1);
            String[] values = parts[1].substring(2).split(",");

            if (values.length != 3) {
                return;
            }

            float x = Float.parseFloat(values[0]);
            float y = Float.parseFloat(values[1]);
            float z = Float.parseFloat(values[2]);

            if (dataType.equals("A")) {
                tempAccelX = x;
                tempAccelY = y;
                tempAccelZ = z;
                hasAccelData = true;
                currentSensor = identifier;

            } else if (dataType.equals("G")) {
                tempGyroX = x;
                tempGyroY = y;
                tempGyroZ = z;
                hasGyroData = true;

                if (hasAccelData && hasGyroData && currentSensor.equals(identifier)) {
                    // Complete reading - add to buffer
                    long timestamp = System.currentTimeMillis();
                    SensorReading reading = new SensorReading(
                            timestamp,
                            tempAccelX, tempAccelY, tempAccelZ,
                            tempGyroX, tempGyroY, tempGyroZ
                    );

                    if (identifier.equals("UB")) {
                        upperBackBuffer.add(reading);
                    } else if (identifier.equals("LB")) {
                        lowerBackBuffer.add(reading);
                    }

                    hasAccelData = false;
                    hasGyroData = false;
                    currentSensor = "";
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing message: " + dataString, e);
        }
    }

    /**
     * Apply timestamp shifting (Option B) to align upper and lower back data
     */
    private void applyTimestampShifting() {
        if (upperBackBuffer.isEmpty() || lowerBackBuffer.isEmpty()) {
            Log.w(TAG, "Cannot apply timestamp shifting - buffers are empty");
            return;
        }

        // Calculate offset between start times
        long timeOffset = lowerBackStartTime - upperBackStartTime;

        Log.d(TAG, "Applying timestamp shifting:");
        Log.d(TAG, "  Upper back start: " + upperBackStartTime);
        Log.d(TAG, "  Lower back start: " + lowerBackStartTime);
        Log.d(TAG, "  Time offset: " + timeOffset + "ms (" + (timeOffset/1000) + "s)");

        // Shift all lower back timestamps back by the offset
        for (SensorReading reading : lowerBackBuffer) {
            reading.timestamp -= timeOffset;
        }

        Log.d(TAG, "✓ Timestamp shifting complete");
        Log.d(TAG, "  Upper back: " + upperBackBuffer.get(0).timestamp + " to " +
                upperBackBuffer.get(upperBackBuffer.size()-1).timestamp);
        Log.d(TAG, "  Lower back: " + lowerBackBuffer.get(0).timestamp + " to " +
                lowerBackBuffer.get(lowerBackBuffer.size()-1).timestamp);
    }

    /**
     * Start periodic progress updates
     */
    private void startProgressUpdates() {
        progressUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                long elapsed = System.currentTimeMillis() - phaseStartTime;
                int progress = (int)((elapsed * 100) / CONNECTION_TIME);
                progress = Math.min(progress, 100);

                // Calculate overall progress (0-50% for upper, 50-100% for lower)
                int overallProgress;
                if (currentDeviceIndex == 0) {
                    overallProgress = progress / 2; // 0-50%
                } else {
                    overallProgress = 50 + (progress / 2); // 50-100%
                }

                collectionProgress.postValue(overallProgress);

                // Continue updating
                if (progress < 100) {
                    progressHandler.postDelayed(this, 500); // Update every 500ms
                }
            }
        };
        progressHandler.post(progressUpdateRunnable);
    }

    /**
     * Stop progress updates
     */
    private void stopProgressUpdates() {
        if (progressUpdateRunnable != null) {
            progressHandler.removeCallbacks(progressUpdateRunnable);
        }
    }

    /**
     * Get collected data for upload
     */
    public ArrayList<SensorReading> getUpperBackData() {
        return upperBackBuffer;
    }

    public ArrayList<SensorReading> getLowerBackData() {
        return lowerBackBuffer;
    }

    /**
     * Clear buffers (after successful upload or when starting new collection)
     */
    public void clearBuffers() {
        upperBackBuffer.clear();
        lowerBackBuffer.clear();
        upperBackStartTime = 0;
        lowerBackStartTime = 0;
        Log.d(TAG, "Buffers cleared");
    }

    /**
     * Generate JSON from collected training data
     * @param poseLabel The selected pose label (e.g., "Sitting Upright")
     * @return JSON string ready for upload
     */
    /**
     * Generate JSON from ALL collected training data
     * @return JSON string with all poses ready for upload
     */
    /**
     * Generate JSON from ALL collected training data
     * @return JSON string with all poses ready for upload
     */

    /**
     * Extract general ML label from specific pose label
     * sitting_upright → sitting
     * standing_slouched → standing
     * walking_upright → walking
     */
    private String extractMLLabel(String poseLabel) {
        if (poseLabel.startsWith("sitting")) {
            return "sitting";
        } else if (poseLabel.startsWith("standing")) {
            return "standing";
        } else if (poseLabel.equals("walking")) {
            return "walking";
        } else {
            return "unknown";
        }
    }

    /**
     * Generate JSON from ALL collected training data in the exact format required by ML backend
     * @return JSON string with all poses ready for upload
     */
    /**
     * Generate JSON from ALL collected training data in the exact format required by ML backend
     * @return JSON string with all poses ready for upload
     */
    public String generateTrainingJSON() {
        if (allTrainingData.isEmpty()) {
            Log.e(TAG, "Cannot generate JSON - no training data available");
            return null;
        }

        try {
            String userId = currentUserId != null ? currentUserId : "unknown_user";

            Log.d(TAG, "Generating JSON for ALL poses:");
            Log.d(TAG, "  User ID: " + userId);
            Log.d(TAG, "  Number of poses: " + allTrainingData.size());

            // Build JSON manually
            StringBuilder json = new StringBuilder();
            json.append("{\n");
            json.append("  \"user_id\": \"").append(userId).append("\",\n");

            // Iterate through poses and number them as pose_1, pose_2, etc.
            int poseIndex = 1;
            int totalPoses = allTrainingData.size();

            for (Map.Entry<String, PoseData> entry : allTrainingData.entrySet()) {
                String poseLabel = entry.getKey();
                PoseData poseData = entry.getValue();

                // ✅ USE SAVED METADATA (don't recalculate)
                float sampleRateHz = poseData.sampleRateHz;
                float durationSeconds = poseData.durationSeconds;

                // ✅ FALLBACK: If metadata is missing (old data), try to calculate
                if (sampleRateHz == 0 && poseData.upperBackData.size() > 1) {
                    long firstTimestamp = poseData.upperBackData.get(0).timestamp;
                    long lastTimestamp = poseData.upperBackData.get(poseData.upperBackData.size() - 1).timestamp;
                    long durationMs = lastTimestamp - firstTimestamp;

                    if (durationMs > 0) {
                        sampleRateHz = (poseData.upperBackData.size() * 1000f) / durationMs;
                        durationSeconds = durationMs / 1000f;
                        Log.w(TAG, "Calculated metadata for " + poseLabel + " (missing from Firebase)");
                    } else {
                        Log.w(TAG, "Cannot calculate metadata for " + poseLabel + " - using defaults");
                        // Use reasonable defaults for 2-minute collection at ~16Hz
                        sampleRateHz = 16.0f;
                        durationSeconds = 120.0f;
                    }
                }

                // Extract general ML label (sitting/standing/walking)
                String mlLabel = extractMLLabel(poseLabel);

                Log.d(TAG, "  - pose_" + poseIndex + ": " + poseLabel + " (" + mlLabel + ") - " +
                        poseData.upperBackData.size() + " samples @ " +
                        String.format(Locale.US, "%.2f", sampleRateHz) + " Hz");

                // Use pose_1, pose_2, pose_3, etc.
                json.append("  \"pose_").append(poseIndex).append("\": {\n");

                // Add metadata
                json.append("    \"pose_label\": \"").append(poseLabel).append("\",\n");
                json.append("    \"ml_label\": \"").append(mlLabel).append("\",\n");
                json.append("    \"sample_rate_hz\": ").append(String.format(Locale.US, "%.2f", sampleRateHz)).append(",\n");
                json.append("    \"duration_seconds\": ").append(String.format(Locale.US, "%.2f", durationSeconds)).append(",\n");

                // Upper back data (NO TIMESTAMPS in individual points)
                json.append("    \"upper_back\": [\n");
                for (int i = 0; i < poseData.upperBackData.size(); i++) {
                    SensorReading reading = poseData.upperBackData.get(i);

                    json.append("      {");
                    json.append("\"ax\": ").append(String.format(Locale.US, "%.4f", reading.accelX)).append(", ");
                    json.append("\"ay\": ").append(String.format(Locale.US, "%.4f", reading.accelY)).append(", ");
                    json.append("\"az\": ").append(String.format(Locale.US, "%.4f", reading.accelZ)).append(", ");
                    json.append("\"gx\": ").append(String.format(Locale.US, "%.4f", reading.gyroX)).append(", ");
                    json.append("\"gy\": ").append(String.format(Locale.US, "%.4f", reading.gyroY)).append(", ");
                    json.append("\"gz\": ").append(String.format(Locale.US, "%.4f", reading.gyroZ));
                    json.append("}");

                    if (i < poseData.upperBackData.size() - 1) {
                        json.append(",");
                    }
                    json.append("\n");
                }
                json.append("    ],\n");

                // Lower back data (NO TIMESTAMPS in individual points)
                json.append("    \"lower_back\": [\n");
                for (int i = 0; i < poseData.lowerBackData.size(); i++) {
                    SensorReading reading = poseData.lowerBackData.get(i);

                    json.append("      {");
                    json.append("\"ax\": ").append(String.format(Locale.US, "%.4f", reading.accelX)).append(", ");
                    json.append("\"ay\": ").append(String.format(Locale.US, "%.4f", reading.accelY)).append(", ");
                    json.append("\"az\": ").append(String.format(Locale.US, "%.4f", reading.accelZ)).append(", ");
                    json.append("\"gx\": ").append(String.format(Locale.US, "%.4f", reading.gyroX)).append(", ");
                    json.append("\"gy\": ").append(String.format(Locale.US, "%.4f", reading.gyroY)).append(", ");
                    json.append("\"gz\": ").append(String.format(Locale.US, "%.4f", reading.gyroZ));
                    json.append("}");

                    if (i < poseData.lowerBackData.size() - 1) {
                        json.append(",");
                    }
                    json.append("\n");
                }
                json.append("    ]\n");

                json.append("  }");

                // Add comma if not last pose
                if (poseIndex < totalPoses) {
                    json.append(",");
                }
                json.append("\n");

                poseIndex++;
            }

            json.append("}");

            Log.d(TAG, "✓ JSON generated successfully (" + json.length() + " characters)");
            return json.toString();

        } catch (Exception e) {
            Log.e(TAG, "Error generating JSON: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * Save training data to Firebase for the current pose
     */
    /**
     * Save training data to Firebase for the current pose
     */
    /**
     * Save training data to Firebase for the current pose
     */
    private void saveCurrentPoseToFirebase() {
        if (currentUserId == null || selectedPoseLabel == null || selectedPoseLabel.isEmpty()) {
            Log.w(TAG, "Cannot save to Firebase - no user or pose label");
            return;
        }

        if (upperBackBuffer.isEmpty() || lowerBackBuffer.isEmpty()) {
            Log.w(TAG, "Cannot save to Firebase - buffers are empty");
            return;
        }

        String formattedLabel = selectedPoseLabel.toLowerCase().replace(" ", "_");

        Log.d(TAG, "Saving training data to Firebase: " + formattedLabel);

        // ✅ CALCULATE METADATA BEFORE SAVING - MAKE FINAL
        final float sampleRateHz;
        final float durationSeconds;

        if (upperBackBuffer.size() > 1) {
            long firstTimestamp = upperBackBuffer.get(0).timestamp;
            long lastTimestamp = upperBackBuffer.get(upperBackBuffer.size() - 1).timestamp;
            long durationMs = lastTimestamp - firstTimestamp;

            if (durationMs > 0) {
                sampleRateHz = (upperBackBuffer.size() * 1000f) / durationMs;
                durationSeconds = durationMs / 1000f;
            } else {
                sampleRateHz = 0;
                durationSeconds = 0;
            }
        } else {
            sampleRateHz = 0;
            durationSeconds = 0;
        }

        Log.d(TAG, "  Sample rate: " + String.format(Locale.US, "%.2f", sampleRateHz) + " Hz");
        Log.d(TAG, "  Duration: " + String.format(Locale.US, "%.2f", durationSeconds) + " seconds");

        // Create data structure for Firebase
        Map<String, Object> poseData = new HashMap<>();

        // ✅ SAVE METADATA (so we don't have to recalculate later)
        poseData.put("sample_rate_hz", sampleRateHz);
        poseData.put("duration_seconds", durationSeconds);
        poseData.put("sample_count", upperBackBuffer.size());
        poseData.put("collection_timestamp", System.currentTimeMillis());

        // Convert upper back data (without timestamps to save space)
        List<Map<String, Object>> upperData = new ArrayList<>();
        for (SensorReading reading : upperBackBuffer) {
            Map<String, Object> point = new HashMap<>();
            point.put("ax", reading.accelX);
            point.put("ay", reading.accelY);
            point.put("az", reading.accelZ);
            point.put("gx", reading.gyroX);
            point.put("gy", reading.gyroY);
            point.put("gz", reading.gyroZ);
            upperData.add(point);
        }

        // Convert lower back data (without timestamps)
        List<Map<String, Object>> lowerData = new ArrayList<>();
        for (SensorReading reading : lowerBackBuffer) {
            Map<String, Object> point = new HashMap<>();
            point.put("ax", reading.accelX);
            point.put("ay", reading.accelY);
            point.put("az", reading.accelZ);
            point.put("gx", reading.gyroX);
            point.put("gy", reading.gyroY);
            point.put("gz", reading.gyroZ);
            lowerData.add(point);
        }

        poseData.put("upper_back", upperData);
        poseData.put("lower_back", lowerData);

        // Save to Firebase
        databaseReference.child("users")
                .child(currentUserId)
                .child("training_data")
                .child(formattedLabel)
                .setValue(poseData)
                .addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "✓ Training data saved to Firebase: " + formattedLabel);

                    // ✅ NOW THESE VARIABLES ARE FINAL AND CAN BE USED IN LAMBDA
                    // Also update local cache with metadata
                    PoseData localData = new PoseData(upperBackBuffer, lowerBackBuffer);
                    localData.sampleRateHz = sampleRateHz;
                    localData.durationSeconds = durationSeconds;
                    allTrainingData.put(formattedLabel, localData);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to save training data to Firebase: " + e.getMessage(), e);
                });
    }


    /**
     * Load all training data from Firebase
     */
    /**
     * Load all training data from Firebase
     */
    private void loadTrainingDataFromFirebase() {
        if (currentUserId == null) {
            Log.w(TAG, "Cannot load from Firebase - no user");
            return;
        }

        Log.d(TAG, "Loading training data from Firebase...");

        databaseReference.child("users")
                .child(currentUserId)
                .child("training_data")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        allTrainingData.clear();

                        if (!snapshot.exists()) {
                            Log.d(TAG, "No training data found in Firebase");
                            return;
                        }

                        Log.d(TAG, "Firebase snapshot has " + snapshot.getChildrenCount() + " poses");

                        for (DataSnapshot poseSnapshot : snapshot.getChildren()) {
                            String poseLabel = poseSnapshot.getKey();

                            Log.d(TAG, "Loading pose: " + poseLabel);

                            try {
                                // ✅ LOAD SAVED METADATA FIRST
                                Float savedSampleRate = poseSnapshot.child("sample_rate_hz").getValue(Float.class);
                                Float savedDuration = poseSnapshot.child("duration_seconds").getValue(Float.class);
                                Long savedTimestamp = poseSnapshot.child("collection_timestamp").getValue(Long.class);

                                Log.d(TAG, "  Saved metadata - Rate: " + savedSampleRate + " Hz, Duration: " + savedDuration + "s");

                                // Check if data exists
                                if (!poseSnapshot.child("upper_back").exists()) {
                                    Log.w(TAG, "No upper_back data for: " + poseLabel);
                                    continue;
                                }

                                if (!poseSnapshot.child("lower_back").exists()) {
                                    Log.w(TAG, "No lower_back data for: " + poseLabel);
                                    continue;
                                }

                                // Load upper back data
                                ArrayList<SensorReading> upperData = new ArrayList<>();
                                DataSnapshot upperSnapshot = poseSnapshot.child("upper_back");

                                // ✅ Use dummy timestamps (we have metadata, so we don't need real timestamps)
                                long dummyTimestamp = savedTimestamp != null ? savedTimestamp : System.currentTimeMillis();

                                for (DataSnapshot reading : upperSnapshot.getChildren()) {
                                    try {
                                        Float ax = reading.child("ax").getValue(Float.class);
                                        Float ay = reading.child("ay").getValue(Float.class);
                                        Float az = reading.child("az").getValue(Float.class);
                                        Float gx = reading.child("gx").getValue(Float.class);
                                        Float gy = reading.child("gy").getValue(Float.class);
                                        Float gz = reading.child("gz").getValue(Float.class);

                                        // Null checks
                                        if (ax == null || ay == null || az == null ||
                                                gx == null || gy == null || gz == null) {
                                            Log.w(TAG, "Skipping reading with null values");
                                            continue;
                                        }

                                        // Use dummy timestamp (doesn't matter since we have metadata)
                                        upperData.add(new SensorReading(dummyTimestamp, ax, ay, az, gx, gy, gz));

                                    } catch (Exception e) {
                                        Log.e(TAG, "Error reading upper back sample: " + e.getMessage());
                                    }
                                }

                                // Load lower back data
                                ArrayList<SensorReading> lowerData = new ArrayList<>();
                                DataSnapshot lowerSnapshot = poseSnapshot.child("lower_back");

                                for (DataSnapshot reading : lowerSnapshot.getChildren()) {
                                    try {
                                        Float ax = reading.child("ax").getValue(Float.class);
                                        Float ay = reading.child("ay").getValue(Float.class);
                                        Float az = reading.child("az").getValue(Float.class);
                                        Float gx = reading.child("gx").getValue(Float.class);
                                        Float gy = reading.child("gy").getValue(Float.class);
                                        Float gz = reading.child("gz").getValue(Float.class);

                                        // Null checks
                                        if (ax == null || ay == null || az == null ||
                                                gx == null || gy == null || gz == null) {
                                            Log.w(TAG, "Skipping reading with null values");
                                            continue;
                                        }

                                        lowerData.add(new SensorReading(dummyTimestamp, ax, ay, az, gx, gy, gz));

                                    } catch (Exception e) {
                                        Log.e(TAG, "Error reading lower back sample: " + e.getMessage());
                                    }
                                }

                                if (upperData.isEmpty() || lowerData.isEmpty()) {
                                    Log.w(TAG, "Skipping pose with empty data: " + poseLabel);
                                    continue;
                                }

                                // ✅ Create PoseData and SET METADATA
                                PoseData poseData = new PoseData(upperData, lowerData);

                                // Use saved metadata if available, otherwise calculate defaults
                                if (savedSampleRate != null && savedSampleRate > 0) {
                                    poseData.sampleRateHz = savedSampleRate;
                                } else {
                                    poseData.sampleRateHz = 16.0f; // Default for 2-min collection
                                    Log.w(TAG, "No saved sample rate, using default: 16.0 Hz");
                                }

                                if (savedDuration != null && savedDuration > 0) {
                                    poseData.durationSeconds = savedDuration;
                                } else {
                                    poseData.durationSeconds = 120.0f; // Default 2 minutes
                                    Log.w(TAG, "No saved duration, using default: 120.0 seconds");
                                }

                                if (savedTimestamp != null) {
                                    poseData.collectionTimestamp = savedTimestamp;
                                }

                                // Store in local cache
                                allTrainingData.put(poseLabel, poseData);

                                Log.d(TAG, "✓ Loaded " + poseLabel + ": " +
                                        upperData.size() + " samples @ " +
                                        String.format(Locale.US, "%.2f", poseData.sampleRateHz) + " Hz, " +
                                        String.format(Locale.US, "%.2f", poseData.durationSeconds) + " sec");

                            } catch (Exception e) {
                                Log.e(TAG, "Error loading pose data: " + poseLabel, e);
                            }
                        }

                        Log.d(TAG, "=================================");
                        Log.d(TAG, "Training data loaded successfully!");
                        Log.d(TAG, "Total poses in memory: " + allTrainingData.size());
                        Log.d(TAG, "Pose labels: " + allTrainingData.keySet().toString());
                        Log.d(TAG, "=================================");
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        Log.e(TAG, "Failed to load training data: " + error.getMessage());
                    }
                });
    }

    /**
     * Delete training data for a specific pose from Firebase
     */
    public void deletePoseData(String poseLabel) {
        if (currentUserId == null) return;

        String formattedLabel = poseLabel.toLowerCase().replace(" ", "_");

        databaseReference.child("users")
                .child(currentUserId)
                .child("training_data")
                .child(formattedLabel)
                .removeValue()
                .addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "✓ Deleted pose data: " + formattedLabel);
                    allTrainingData.remove(formattedLabel);
                });
    }


    /**
     * Clear ALL training data from Firebase
     */
    public void clearAllTrainingData() {
        if (currentUserId == null) return;

        databaseReference.child("users")
                .child(currentUserId)
                .child("training_data")
                .removeValue()
                .addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "✓ All training data cleared");
                    allTrainingData.clear();
                });
    }

    /**
     * Get detailed info about all saved poses
     */
    public Map<String, String> getSavedPoseInfo() {
        Map<String, String> info = new HashMap<>();

        for (Map.Entry<String, PoseData> entry : allTrainingData.entrySet()) {
            String label = entry.getKey();
            PoseData data = entry.getValue();

            String details = String.format(Locale.US,
                    "%d samples, %.1f Hz, %.1f sec",
                    data.upperBackData.size(),
                    data.sampleRateHz,
                    data.durationSeconds
            );

            info.put(label, details);
        }

        return info;
    }

    /**
     * Check if pose exists in Firebase
     */
    public boolean hasPoseInFirebase(String poseLabel) {
        return allTrainingData.containsKey(poseLabel);
    }


    /**
     * Get all saved pose labels
     */
    public List<String> getSavedPoseLabels() {
        return new ArrayList<>(allTrainingData.keySet());
    }

    /**
     * Check if we have data for a specific pose
     */
    public boolean hasPoseData(String poseLabel) {
        String formattedLabel = poseLabel.toLowerCase().replace(" ", "_");
        return allTrainingData.containsKey(formattedLabel);
    }

    public void setSelectedPoseLabel(String label) {
        this.selectedPoseLabel = label;
    }

    public String getSelectedPoseLabel() {
        return selectedPoseLabel;
    }




}