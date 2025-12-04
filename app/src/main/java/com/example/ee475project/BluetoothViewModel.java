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

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import java.io.IOException;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@SuppressLint("MissingPermission")
public class BluetoothViewModel extends AndroidViewModel {

    private final BluetoothAdapter bluetoothAdapter;
    private final BluetoothLeScanner bluetoothLeScanner;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final MutableLiveData<String> connectionStatus = new MutableLiveData<>("Disconnected");
    private final MutableLiveData<Boolean> isConnected = new MutableLiveData<>(false);
    private final MutableLiveData<Boolean> isCycleComplete = new MutableLiveData<>(false);
    private final MutableLiveData<ImuData> upperBackData = new MutableLiveData<>();
    private final MutableLiveData<ImuData> lowerBackData = new MutableLiveData<>();
    private final MutableLiveData<Float> totalConnectionTime = new MutableLiveData<>(0f);
    private final MutableLiveData<Float> activeTime = new MutableLiveData<>(0f);
    private long connectionStartTime;
    private final Handler timerHandler = new Handler(Looper.getMainLooper());

    private final MutableLiveData<String> completedSessionId = new MutableLiveData<>(null);

    private final DatabaseReference userDbRef;
    private final StringBuilder dataBuffer = new StringBuilder();

    private final OkHttpClient httpClient;

    private BluetoothGatt bluetoothGatt;

    private static final String TAG = "BluetoothViewModel";
    private static final String DEVICE_NAME_UPPER = "XIAO_Upper_Back";
    private static final String DEVICE_NAME_LOWER = "XIAO_Lower_Back";
    private static final long SCAN_PERIOD = 10000; // Stops scanning after 10 seconds.
    private static final long CONNECTION_TIME = 10000; // 10 seconds

    // Nordic UART Service UUIDs
    private static final UUID UART_SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID UART_TX_CHARACTERISTIC_UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID UART_RX_CHARACTERISTIC_UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E"); // For writing to device
    private static final UUID CLIENT_CHARACTERISTIC_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final ScanCallback leScanCallback;
    private final Runnable stopScanRunnable;
    private boolean isCycling = false;
    private int currentDeviceIndex = 0;
    private final String[] deviceNames = {DEVICE_NAME_UPPER, DEVICE_NAME_LOWER};

    // IMU Data
    private float tempAccelX, tempAccelY, tempAccelZ;
    private float tempGyroX, tempGyroY, tempGyroZ;
    private boolean hasAccelData = false;
    private boolean hasGyroData = false;
    private String currentSensor = "";  // "UB" or "LB"

    // Firebase Session Tracking
    private String currentSessionId = null;
    private long sessionStartTime = 0;
    private DatabaseReference sessionsRef;

    // Buffers for inference data collection
    private List<SensorData> inferenceUpperBackBuffer = new ArrayList<>();
    private List<SensorData> inferenceLowerBackBuffer = new ArrayList<>();
    private long inferenceUpperBackStartTime = 0;
    private long inferenceLowerBackStartTime = 0;

    private boolean isMLInferenceEnabled = false;

    // Add this field near the top with other fields
    private Runnable scheduledDisconnectRunnable = null;

    // Add this field near the top with other fields
    private boolean isConnecting = false;




    public BluetoothViewModel(@NonNull Application application) {
        super(application);
        BluetoothManager bluetoothManager = (BluetoothManager) application.getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();
        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        httpClient = new OkHttpClient();
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();


        if (user != null) {
            userDbRef = FirebaseDatabase.getInstance().getReference("users").child(user.getUid());
            performInitialLoadAndDailyCheck(); // Perform the check on initialization
            sessionsRef = FirebaseDatabase.getInstance()
                    .getReference("posture_sessions")
                    .child(user.getUid());
        } else {
            userDbRef = null;
        }

        leScanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                super.onScanResult(callbackType, result);

                // ✅ GUARD: Skip if already connecting or connected
                if (isConnecting || Boolean.TRUE.equals(isConnected.getValue())) {
                    return;
                }


                BluetoothDevice device = result.getDevice();
                if (device != null && device.getName() != null) {
                    if (isCycling && device.getName().equals(deviceNames[currentDeviceIndex])) {
                        isConnecting = true;
                        handler.removeCallbacks(stopScanRunnable);
                        bluetoothLeScanner.stopScan(leScanCallback);
                        connectToDevice(device);
                    } else if (!isCycling && (device.getName().startsWith(DEVICE_NAME_UPPER) || device.getName().startsWith(DEVICE_NAME_LOWER))) {
                        isConnecting = true;
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
                    connectionStatus.setValue("Disconnected");
                }
            }
        };
    }

    // This method is now public to be callable from the HomeFragment
    public void performInitialLoadAndDailyCheck() {
        if (userDbRef == null) return;

        userDbRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                String todayKey = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());

                if (snapshot.exists()) {
                    Float totalTime = snapshot.child("total_connection_time").getValue(Float.class);
                    totalConnectionTime.setValue(totalTime != null ? totalTime : 0f);

                    String lastActiveDate = snapshot.child("last_active_date").getValue(String.class);

                    if (!todayKey.equals(lastActiveDate)) {
                        // New day detected, reset all daily values in Firebase and LiveData
                        Log.d(TAG, "New day detected. Resetting daily stats in Firebase.");
                        activeTime.postValue(0f); // Update LiveData
                        userDbRef.child("active_time").setValue(0f);
                        userDbRef.child("slouch_time").setValue(0f);
                        userDbRef.child("last_active_date").setValue(todayKey);
                    } else {
                        // Same day, load the existing active time
                        Float actTime = snapshot.child("active_time").getValue(Float.class);
                        activeTime.setValue(actTime != null ? actTime : 0f);
                    }
                } else {
                    // First time user, initialize the date and other fields
                    Log.d(TAG, "First time user setup in Firebase.");
                    userDbRef.child("last_active_date").setValue(todayKey);
                    userDbRef.child("active_time").setValue(0f);
                    userDbRef.child("slouch_time").setValue(0f);
                    userDbRef.child("total_connection_time").setValue(0f);
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Failed to load user data.", error.toException());
            }
        });
    }

    public LiveData<String> getConnectionStatus() {
        return connectionStatus;
    }

    public LiveData<Boolean> getIsConnected() {
        return isConnected;
    }

    public LiveData<Boolean> getIsCycleComplete() {
        return isCycleComplete;
    }

    public LiveData<ImuData> getUpperBackData() {
        return upperBackData;
    }

    public LiveData<ImuData> getLowerBackData() {
        return lowerBackData;
    }

    public LiveData<Float> getTotalConnectionTime() {
        return totalConnectionTime;
    }

    public LiveData<Float> getActiveTime() {
        return activeTime;
    }

    public void startScan() {
        isCycling = false;
        if (bluetoothLeScanner != null && !Boolean.TRUE.equals(isConnected.getValue())) { // prevent scan if already connected
            List<ScanFilter> filters = new ArrayList<>();
            filters.add(new ScanFilter.Builder().setDeviceName(DEVICE_NAME_UPPER).build());
            filters.add(new ScanFilter.Builder().setDeviceName(DEVICE_NAME_LOWER).build());

            ScanSettings scanSettings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();

            bluetoothLeScanner.startScan(filters, scanSettings, leScanCallback);
            connectionStatus.setValue("Scanning...");

            // Stop scanning after a predefined scan period.
            handler.postDelayed(stopScanRunnable, SCAN_PERIOD);
        }
    }

    public void startCycle() {
        Log.d(TAG, "startCycle called");
        isCycling = true;
        isConnecting = false;  // ✅ Reset connecting flag
        currentDeviceIndex = 0;
        isCycleComplete.setValue(false);
        scanForNextDevice();
    }

    private void scanForNextDevice() {
        if (!isCycling) {
            Log.d(TAG, "scanForNextDevice: Not cycling, skipping");
            return;
        }

        // ✅ GUARD: Don't scan if already connecting or connected
        if (isConnecting) {
            Log.w(TAG, "scanForNextDevice: Already connecting, skipping");
            return;
        }

        if (Boolean.TRUE.equals(isConnected.getValue())) {
            Log.w(TAG, "scanForNextDevice: Already connected, skipping");
            return;
        }

        String deviceName = deviceNames[currentDeviceIndex];
        Log.d(TAG, "Scanning for: " + deviceName + " (index=" + currentDeviceIndex + ")");

        List<ScanFilter> filters = new ArrayList<>();
        filters.add(new ScanFilter.Builder().setDeviceName(deviceName).build());

        ScanSettings scanSettings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        bluetoothLeScanner.startScan(filters, scanSettings, leScanCallback);
        connectionStatus.setValue("Scanning for " + deviceName);

        handler.postDelayed(stopScanRunnable, SCAN_PERIOD);
    }


    // Update cancelScan
    public void cancelScan() {
        Log.d(TAG, "cancelScan called");
        isCycling = false;
        isConnecting = false;  // ✅ Reset connecting flag
        isCycleComplete.setValue(false);

        if (scheduledDisconnectRunnable != null) {
            handler.removeCallbacks(scheduledDisconnectRunnable);
            scheduledDisconnectRunnable = null;
        }

        if (bluetoothLeScanner != null) {
            handler.removeCallbacks(stopScanRunnable);
            bluetoothLeScanner.stopScan(leScanCallback);
            connectionStatus.setValue("Disconnected");
        }
    }

    // Update disconnect
    public void disconnect() {
        Log.d(TAG, "disconnect called");
        isCycling = false;
        isConnecting = false;  // ✅ Reset connecting flag

        if (scheduledDisconnectRunnable != null) {
            handler.removeCallbacks(scheduledDisconnectRunnable);
            scheduledDisconnectRunnable = null;
        }

        if (bluetoothGatt != null) {
            bluetoothGatt.disconnect();
        }
    }

    private void connectToDevice(BluetoothDevice device) {
        if (device != null) {
            connectionStatus.setValue("Connecting to " + device.getName());
            bluetoothGatt = device.connectGatt(getApplication(), false, gattCallback);
        }
    }

    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            if (userDbRef == null) return;
            long millis = System.currentTimeMillis() - connectionStartTime;
            float minutes = millis / (1000f * 60);

            // Use a local variable for calculation to avoid race conditions with LiveData
            Float currentActive = activeTime.getValue();
            if (currentActive == null) currentActive = 0f;
            float newActiveTime = currentActive + minutes;

            activeTime.postValue(newActiveTime);
            userDbRef.child("active_time").setValue(newActiveTime);

            // Also update total connection time
            Float currentTotal = totalConnectionTime.getValue();
            if (currentTotal == null) currentTotal = 0f;
            float newTotalTime = currentTotal + (minutes / 60); // minutes to hours
            totalConnectionTime.postValue(newTotalTime);
            userDbRef.child("total_connection_time").setValue(newTotalTime);

            connectionStartTime = System.currentTimeMillis(); // Reset start time for next interval
            timerHandler.postDelayed(this, 1000); // Update every second
        }
    };


    // Replace your onConnectionStateChange method:
    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            String deviceName = gatt.getDevice().getName();

            if (newState == BluetoothGatt.STATE_CONNECTED) {
                // ✅ Cancel any pending disconnect from previous connection
                if (scheduledDisconnectRunnable != null) {
                    handler.removeCallbacks(scheduledDisconnectRunnable);
                    scheduledDisconnectRunnable = null;
                }

                Log.d(TAG, "════════════════════════════════════════");
                Log.d(TAG, "CONNECTED to: " + deviceName + " (index=" + currentDeviceIndex + ")");
                Log.d(TAG, "════════════════════════════════════════");
                Log.d(TAG, "📋 Current session ID: " + currentSessionId);

                // ✅ CREATE NEW SESSION at the START of a cycle (when upper back connects)
                if (isCycling && currentDeviceIndex == 0 && sessionsRef != null) {
                    String oldSessionId = currentSessionId;
                    currentSessionId = generateSessionId();
                    sessionStartTime = System.currentTimeMillis();

                    PostureSession session = new PostureSession(
                            currentSessionId,
                            FirebaseAuth.getInstance().getCurrentUser().getUid(),
                            sessionStartTime
                    );

                    sessionsRef.child(currentSessionId).setValue(session)
                            .addOnSuccessListener(aVoid -> {
                                Log.d(TAG, "✓ NEW SESSION: " + currentSessionId);
                            })
                            .addOnFailureListener(e ->
                                    Log.e(TAG, "✗ Failed to create session: " + e.getMessage()));

                    inferenceUpperBackBuffer.clear();
                    inferenceLowerBackBuffer.clear();
                    inferenceUpperBackStartTime = 0;
                    inferenceLowerBackStartTime = 0;
                }

                connectionStatus.postValue("Connected to " + deviceName);
                isConnected.postValue(true);
                isConnecting = false;  // ✅ Clear connecting flag - now connected

                connectionStartTime = System.currentTimeMillis();
                timerHandler.post(timerRunnable);

                bluetoothGatt = gatt;
                gatt.discoverServices();

                if (isCycling) {
                    final BluetoothGatt gattToDisconnect = gatt;
                    scheduledDisconnectRunnable = () -> {
                        Log.d(TAG, "Scheduled disconnect for: " + deviceName);
                        if (gattToDisconnect != null && gattToDisconnect == bluetoothGatt) {
                            gattToDisconnect.disconnect();
                        }
                    };
                    handler.postDelayed(scheduledDisconnectRunnable, CONNECTION_TIME);
                }

            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                // ✅ Cancel scheduled disconnect
                if (scheduledDisconnectRunnable != null) {
                    handler.removeCallbacks(scheduledDisconnectRunnable);
                    scheduledDisconnectRunnable = null;
                }

                Log.d(TAG, "DISCONNECTED from: " + deviceName + " (index=" + currentDeviceIndex + ")");

                timerHandler.removeCallbacks(timerRunnable);
                synchronized (dataBuffer) {
                    dataBuffer.setLength(0);
                }
                connectionStatus.postValue("Disconnected from " + deviceName);
                isConnected.postValue(false);
                isConnecting = false;  // ✅ Clear connecting flag

                gatt.close();

                if (bluetoothGatt == gatt) {
                    bluetoothGatt = null;
                }

                if (isCycling) {
                    int previousIndex = currentDeviceIndex;
                    currentDeviceIndex = (currentDeviceIndex + 1) % deviceNames.length;

                    Log.d(TAG, "Cycle index: " + previousIndex + " → " + currentDeviceIndex);

                    if (currentDeviceIndex == 0) {
                        // CYCLE COMPLETE
                        Log.d(TAG, "════════════════════════════════════════");
                        Log.d(TAG, "✓ CYCLE COMPLETE: " + currentSessionId);
                        Log.d(TAG, "════════════════════════════════════════");

                        saveInferenceDataToFirebase();
                        completedSessionId.postValue(currentSessionId);
                        isCycleComplete.postValue(true);

                        handler.postDelayed(() -> {
                            if (isCycling && !isConnecting && !Boolean.TRUE.equals(isConnected.getValue())) {
                                Log.d(TAG, "Starting new cycle...");
                                scanForNextDevice();
                            }
                        }, 3500);  // ✅ Increased delay to 3.5s for stability

                    } else {
                        // Continue to lower back
                        handler.postDelayed(() -> {
                            if (isCycling && !isConnecting && !Boolean.TRUE.equals(isConnected.getValue())) {
                                scanForNextDevice();
                            }
                        }, 1500);  // ✅ Increased delay to 1.5s
                    }
                }
            }
        }

        /*
         * Save collected inference data arrays to Firebase
         * ONLY saves if ML inference is enabled - otherwise skips for performance
         */
        private void saveInferenceDataToFirebase() {
            if (currentSessionId == null || sessionsRef == null) {
                Log.w(TAG, "Cannot save inference data - no session or reference");
                return;
            }

            // PERFORMANCE OPTIMIZATION: Skip array saving if ML inference is disabled
            if (!isMLInferenceEnabled) {
                Log.d(TAG, "⚡ Skipping array save (ML inference disabled) - performance optimized");

                // Clear buffers to free memory
                inferenceUpperBackBuffer.clear();
                inferenceLowerBackBuffer.clear();
                inferenceUpperBackStartTime = 0;
                inferenceLowerBackStartTime = 0;

                return;
            }

            if (inferenceUpperBackBuffer.isEmpty() || inferenceLowerBackBuffer.isEmpty()) {
                Log.w(TAG, "Cannot save inference data - buffers are empty");
                return;
            }

            Log.d(TAG, "💾 Saving inference arrays to Firebase (ML inference enabled):");
            Log.d(TAG, "  Upper back: " + inferenceUpperBackBuffer.size() + " samples");
            Log.d(TAG, "  Lower back: " + inferenceLowerBackBuffer.size() + " samples");

            DatabaseReference sessionRef = sessionsRef.child(currentSessionId);

            // ✅ Save arrays for ML inference
            sessionRef.child("upperBackArray").setValue(inferenceUpperBackBuffer)
                    .addOnSuccessListener(aVoid -> {
                        Log.d(TAG, "✓ Upper back array saved (for ML inference)");
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "✗ Failed to save upper back array: " + e.getMessage());
                    });

            sessionRef.child("lowerBackArray").setValue(inferenceLowerBackBuffer)
                    .addOnSuccessListener(aVoid -> {
                        Log.d(TAG, "✓ Lower back array saved (for ML inference)");
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "✗ Failed to save lower back array: " + e.getMessage());
                    });

            // ✅ Clear buffers for next cycle
            inferenceUpperBackBuffer.clear();
            inferenceLowerBackBuffer.clear();
            inferenceUpperBackStartTime = 0;
            inferenceLowerBackStartTime = 0;
        }



        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                BluetoothGattCharacteristic characteristic = gatt.getService(UART_SERVICE_UUID).getCharacteristic(UART_TX_CHARACTERISTIC_UUID);
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

                        parseSplitMessage(dataString);
                    }
                }
            }
        }
    };  // END of gattCallback

    private String generateSessionId() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
        return "session_" + sdf.format(new Date());
    }

    private void parseSplitMessage(String dataString) {
//        Log.d(TAG, "📥 RAW DATA: " + dataString);  // ← ADD THIS LINE
        try {
            String[] parts = dataString.split("\\|");

            if (parts.length != 2) {
                Log.w(TAG, "Malformed data packet (expected 2 parts): " + dataString);
                return;
            }

            String identifier = parts[0];
            String dataType = parts[1].substring(0, 1);
            String[] values = parts[1].substring(2).split(",");

            if (values.length != 3) {
                Log.w(TAG, "Malformed data values (expected 3 values): " + dataString);
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
                    ImuData imuData = new ImuData(
                            tempAccelX, tempAccelY, tempAccelZ,
                            tempGyroX, tempGyroY, tempGyroZ
                    );

                    if (currentSessionId != null) {
                        long currentTime = System.currentTimeMillis();

                        SensorData sensorData = new SensorData(
                                tempAccelX, tempAccelY, tempAccelZ,
                                tempGyroX, tempGyroY, tempGyroZ,
                                currentTime
                        );

                        // ✅ Save to Firebase with debug logging
                        DatabaseReference sensorRef = sessionsRef.child(currentSessionId);
                        if (identifier.equals("UB")) {
                            Log.d(TAG, "💾 SAVING upperBack to session: " + currentSessionId);
                            sensorRef.child("upperBack").setValue(sensorData)
                                    .addOnSuccessListener(aVoid -> Log.d(TAG, "✓ upperBack SAVED"))
                                    .addOnFailureListener(e -> Log.e(TAG, "✗ upperBack FAILED: " + e.getMessage()));
                            upperBackData.postValue(imuData);
                        } else if (identifier.equals("LB")) {
//                            Log.d(TAG, "💾 SAVING lowerBack to session: " + currentSessionId);
                            sensorRef.child("lowerBack").setValue(sensorData)
                                    .addOnSuccessListener(aVoid -> Log.d(TAG, "✓ lowerBack SAVED"))
                                    .addOnFailureListener(e -> Log.e(TAG, "✗ lowerBack FAILED: " + e.getMessage()));
                            lowerBackData.postValue(imuData);
                        }

                        // ✅ NEW: ALSO add to inference buffers (separate system)
                        if (identifier.equals("UB")) {
                            if (inferenceUpperBackBuffer.isEmpty()) {
                                inferenceUpperBackStartTime = currentTime;
                            }
                            inferenceUpperBackBuffer.add(sensorData);

                        } else if (identifier.equals("LB")) {
                            if (inferenceLowerBackBuffer.isEmpty()) {
                                inferenceLowerBackStartTime = currentTime;
                            }
                            inferenceLowerBackBuffer.add(sensorData);
                        }
                    }
                    hasAccelData = false;
                    hasGyroData = false;
                    currentSensor = "";
                }
            }
        } catch (NumberFormatException e) {
            Log.e(TAG, "Error parsing float values from: " + dataString, e);
        } catch (Exception e) {
            Log.e(TAG, "Error parsing split message: " + dataString, e);
        }
    }

    // ===== NEW: Send haptic feedback command to device =====
    /**
     * Sends a haptic feedback command to the currently connected device
     * @return true if command was queued successfully, false otherwise
     */
    public boolean sendHapticCommand() {
        if (bluetoothGatt == null) {
            Log.w(TAG, "Cannot send haptic command - not connected");
            return false;
        }

        try {
            BluetoothGattCharacteristic rxCharacteristic = bluetoothGatt.getService(UART_SERVICE_UUID)
                    .getCharacteristic(UART_RX_CHARACTERISTIC_UUID);

            if (rxCharacteristic == null) {
                Log.e(TAG, "RX characteristic not found!");
                return false;
            }

            // Simple command: "HAPTIC\n" (8 bytes)
            String command = "HAPTIC\n";
            rxCharacteristic.setValue(command.getBytes());

            boolean success = bluetoothGatt.writeCharacteristic(rxCharacteristic);

            if (success) {
                Log.d(TAG, "✓ Haptic command sent: " + command.trim());
            } else {
                Log.e(TAG, "✗ Failed to send haptic command");
            }

            return success;

        } catch (Exception e) {
            Log.e(TAG, "Error sending haptic command: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * Get the name of the currently connected device
     * @return device name or null if not connected
     */
    public String getDeviceName() {
        if (bluetoothGatt != null && bluetoothGatt.getDevice() != null) {
            return bluetoothGatt.getDevice().getName();
        }
        return null;
    }

    /**
     * Callback for inference JSON generation
     */
    public interface OnInferenceJSONGeneratedListener {
        void onJSONGenerated(String json);
        void onError(String error);
    }

    /**
     * Generate inference JSON from MOST RECENT session data (async)
     * Uses the arrays saved during the last cycle
     * Format: { user_id, sample_rate_hz, duration_seconds, sample_count, upper_back[], lower_back[] }
     */
    public void generateInferenceJSON(OnInferenceJSONGeneratedListener listener) {
        if (sessionsRef == null) {
            Log.w(TAG, "No sessions reference available");
            if (listener != null) {
                listener.onError("No sessions reference");
            }
            return;
        }

        // Get current user ID
        String userId = FirebaseAuth.getInstance().getCurrentUser() != null ?
                FirebaseAuth.getInstance().getCurrentUser().getUid() : "unknown_user";

        Log.d(TAG, "═══════════════════════════════════════════════════════════");
        Log.d(TAG, "generateInferenceJSON called");
        Log.d(TAG, "Current session ID in memory: " + currentSessionId);
        Log.d(TAG, "═══════════════════════════════════════════════════════════");

        // ✅ Find the MOST RECENT session with inference arrays
        sessionsRef.orderByChild("timestamp").limitToLast(1)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        if (!snapshot.exists() || snapshot.getChildrenCount() == 0) {
                            Log.e(TAG, "No sessions found in Firebase");
                            if (listener != null) {
                                listener.onError("No sessions found");
                            }
                            return;
                        }

                        // Get the most recent session
                        DataSnapshot sessionSnapshot = null;
                        for (DataSnapshot child : snapshot.getChildren()) {
                            sessionSnapshot = child;  // Will be the last (most recent) one
                        }

                        if (sessionSnapshot == null) {
                            if (listener != null) {
                                listener.onError("No session data");
                            }
                            return;
                        }

                        String sessionId = sessionSnapshot.getKey();
                        Log.d(TAG, "Generating inference JSON from session: " + sessionId);

                        Log.d(TAG, "═══════════════════════════════════════════════════════════");
                        Log.d(TAG, "INFERENCE SESSION FROM FIREBASE: " + sessionId);
                        Log.d(TAG, "═══════════════════════════════════════════════════════════");

                        try {
                            // Get arrays from Firebase
                            List<SensorData> upperBackArray = new ArrayList<>();
                            List<SensorData> lowerBackArray = new ArrayList<>();

                            DataSnapshot upperArraySnapshot = sessionSnapshot.child("upperBackArray");
                            DataSnapshot lowerArraySnapshot = sessionSnapshot.child("lowerBackArray");

                            // Parse upper back array
                            for (DataSnapshot reading : upperArraySnapshot.getChildren()) {
                                SensorData data = reading.getValue(SensorData.class);
                                if (data != null) {
                                    upperBackArray.add(data);
                                }
                            }

                            // Parse lower back array
                            for (DataSnapshot reading : lowerArraySnapshot.getChildren()) {
                                SensorData data = reading.getValue(SensorData.class);
                                if (data != null) {
                                    lowerBackArray.add(data);
                                }
                            }

                            if (upperBackArray.isEmpty() || lowerBackArray.isEmpty()) {
                                Log.e(TAG, "No array data found for inference");
                                if (listener != null) {
                                    listener.onError("No sensor data arrays found");
                                }
                                return;
                            }

                            // Calculate metadata
                            int sampleCount = upperBackArray.size();

                            long firstTimestamp = upperBackArray.get(0).timestamp;
                            long lastTimestamp = upperBackArray.get(upperBackArray.size() - 1).timestamp;
                            long durationMs = lastTimestamp - firstTimestamp;

                            float sampleRateHz = durationMs > 0 ?
                                    (sampleCount * 1000f) / durationMs : 0f;
                            float durationSeconds = durationMs / 1000f;

                            // Build JSON
                            StringBuilder json = new StringBuilder();
                            json.append("{\n");
                            json.append("  \"user_id\": \"").append(userId).append("\",\n");
                            json.append("  \"sample_rate_hz\": ").append(String.format(Locale.US, "%.2f", sampleRateHz)).append(",\n");
                            json.append("  \"duration_seconds\": ").append(String.format(Locale.US, "%.2f", durationSeconds)).append(",\n");
                            json.append("  \"sample_count\": ").append(sampleCount).append(",\n");

                            // Upper back array (without timestamps)
                            json.append("  \"upper_back\": [\n");
                            for (int i = 0; i < upperBackArray.size(); i++) {
                                SensorData reading = upperBackArray.get(i);
                                json.append("    {");
                                json.append("\"ax\": ").append(String.format(Locale.US, "%.4f", reading.accelX)).append(", ");
                                json.append("\"ay\": ").append(String.format(Locale.US, "%.4f", reading.accelY)).append(", ");
                                json.append("\"az\": ").append(String.format(Locale.US, "%.4f", reading.accelZ)).append(", ");
                                json.append("\"gx\": ").append(String.format(Locale.US, "%.4f", reading.gyroX)).append(", ");
                                json.append("\"gy\": ").append(String.format(Locale.US, "%.4f", reading.gyroY)).append(", ");
                                json.append("\"gz\": ").append(String.format(Locale.US, "%.4f", reading.gyroZ));
                                json.append("}");
                                if (i < upperBackArray.size() - 1) {
                                    json.append(",");
                                }
                                json.append("\n");
                            }
                            json.append("  ],\n");

                            // Lower back array (without timestamps)
                            json.append("  \"lower_back\": [\n");
                            for (int i = 0; i < lowerBackArray.size(); i++) {
                                SensorData reading = lowerBackArray.get(i);
                                json.append("    {");
                                json.append("\"ax\": ").append(String.format(Locale.US, "%.4f", reading.accelX)).append(", ");
                                json.append("\"ay\": ").append(String.format(Locale.US, "%.4f", reading.accelY)).append(", ");
                                json.append("\"az\": ").append(String.format(Locale.US, "%.4f", reading.accelZ)).append(", ");
                                json.append("\"gx\": ").append(String.format(Locale.US, "%.4f", reading.gyroX)).append(", ");
                                json.append("\"gy\": ").append(String.format(Locale.US, "%.4f", reading.gyroY)).append(", ");
                                json.append("\"gz\": ").append(String.format(Locale.US, "%.4f", reading.gyroZ));
                                json.append("}");
                                if (i < lowerBackArray.size() - 1) {
                                    json.append(",");
                                }
                                json.append("\n");
                            }
                            json.append("  ]\n");
                            json.append("}");

                            Log.d(TAG, "✓ Inference JSON generated:");
                            Log.d(TAG, "  Session: " + sessionId);
                            Log.d(TAG, "  Sample count: " + sampleCount);
                            Log.d(TAG, "  Sample rate: " + String.format(Locale.US, "%.2f", sampleRateHz) + " Hz");
                            Log.d(TAG, "  Duration: " + String.format(Locale.US, "%.2f", durationSeconds) + " seconds");
                            Log.d(TAG, "  JSON size: " + json.length() + " characters");

                            if (listener != null) {
                                listener.onJSONGenerated(json.toString());
                            }

                        } catch (Exception e) {
                            Log.e(TAG, "Error generating inference JSON: " + e.getMessage(), e);
                            if (listener != null) {
                                listener.onError(e.getMessage());
                            }
                        }
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        Log.e(TAG, "Error reading session for inference: " + error.getMessage());
                        if (listener != null) {
                            listener.onError(error.getMessage());
                        }
                    }
                });
    }

    /**
     * Callback for inference upload
     */
    public interface OnInferenceUploadListener {
        void onUploadSuccess(String response);
        void onUploadFailure(String error);
    }

    /**
     * Upload inference JSON to Python backend
     * @param serverUrl The backend server URL (e.g., "https://abcd-1234.ngrok-free.app")
     * @param inferenceJSON The JSON string to upload
     * @param listener Callback for result
     */
    public void uploadInferenceData(String serverUrl, String inferenceJSON, OnInferenceUploadListener listener) {
        if (serverUrl == null || serverUrl.isEmpty()) {
            Log.e(TAG, "Server URL is empty");
            if (listener != null) {
                listener.onUploadFailure("Server URL not configured");
            }
            return;
        }

        // Add https:// prefix if missing
        if (!serverUrl.startsWith("http://") && !serverUrl.startsWith("https://")) {
            serverUrl = "https://" + serverUrl;
        }

        // Add /inference endpoint if not present
        if (!serverUrl.endsWith("/predict")) {
            if (serverUrl.endsWith("/")) {
                serverUrl = serverUrl + "predict";
            } else {
                serverUrl = serverUrl + "/predict";
            }
        }

        Log.d(TAG, "Uploading inference data to: " + serverUrl);

        try {
            okhttp3.MediaType JSON = okhttp3.MediaType.get("application/json; charset=utf-8");
            okhttp3.RequestBody body = okhttp3.RequestBody.create(inferenceJSON, JSON);

            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url(serverUrl)
                    .post(body)
                    .build();

            // Send request asynchronously
            httpClient.newCall(request).enqueue(new okhttp3.Callback() {
                @Override
                public void onFailure(@NonNull okhttp3.Call call, @NonNull IOException e) {
                    Log.e(TAG, "Inference upload failed: " + e.getMessage(), e);
                    if (listener != null) {
                        listener.onUploadFailure(e.getMessage());
                    }
                }

                @Override
                public void onResponse(@NonNull okhttp3.Call call, @NonNull okhttp3.Response response) throws IOException {
                    final String responseBody = response.body() != null ? response.body().string() : "";
                    Log.d(TAG, "Inference response code: " + response.code());
                    Log.d(TAG, "Inference response body: " + responseBody);

                    if (response.isSuccessful()) {
                        if (listener != null) {
                            listener.onUploadSuccess(responseBody);
                        }
                    } else {
                        if (listener != null) {
                            listener.onUploadFailure("Server error: " + response.code());
                        }
                    }
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "Error creating inference request: " + e.getMessage(), e);
            if (listener != null) {
                listener.onUploadFailure(e.getMessage());
            }
        }
    }

    /**
     * Send slouch indicator command to trigger red LED on device
     * @return true if command sent successfully, false otherwise
     */
    public boolean sendSlouchIndicator() {
        if (bluetoothGatt == null) {
            Log.w(TAG, "Cannot send slouch indicator - not connected");
            return false;
        }

        if (!isConnected.getValue()) {
            Log.w(TAG, "Cannot send slouch indicator - not connected (LiveData check)");
            return false;
        }

        try {
            BluetoothGattCharacteristic rxCharacteristic = bluetoothGatt.getService(UART_SERVICE_UUID)
                    .getCharacteristic(UART_RX_CHARACTERISTIC_UUID);

            if (rxCharacteristic == null) {
                Log.e(TAG, "RX characteristic not found!");
                return false;
            }

            // Simple command: "SLOUCH\n"
            String command = "SLOUCH\n";
            rxCharacteristic.setValue(command.getBytes());

            boolean success = bluetoothGatt.writeCharacteristic(rxCharacteristic);

            if (success) {
                Log.d(TAG, "✓ Slouch indicator command sent: " + command.trim());
            } else {
                Log.e(TAG, "✗ Failed to send slouch indicator command");
            }

            return success;

        } catch (Exception e) {
            Log.e(TAG, "Error sending slouch indicator: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * Set whether ML inference is currently enabled
     * This controls whether we save arrays to Firebase
     */
    public void setMLInferenceEnabled(boolean enabled) {
        this.isMLInferenceEnabled = enabled;
        Log.d(TAG, "ML inference mode: " + (enabled ? "ENABLED" : "DISABLED"));
    }

    public LiveData<String> getCompletedSessionId() {
        return completedSessionId;
    }

    /**
     * Generate inference JSON from a SPECIFIC session (not querying)
     */
    public void generateInferenceJSONForSession(String sessionId, OnInferenceJSONGeneratedListener listener) {
        if (sessionsRef == null || sessionId == null) {
            Log.w(TAG, "No sessions reference or sessionId");
            if (listener != null) {
                listener.onError("No sessions reference or sessionId");
            }
            return;
        }

        String userId = FirebaseAuth.getInstance().getCurrentUser() != null ?
                FirebaseAuth.getInstance().getCurrentUser().getUid() : "unknown_user";

        Log.d(TAG, "═══════════════════════════════════════════════════════════");
        Log.d(TAG, "generateInferenceJSONForSession: " + sessionId);
        Log.d(TAG, "═══════════════════════════════════════════════════════════");

        // Fetch the SPECIFIC session by ID
        sessionsRef.child(sessionId).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot sessionSnapshot) {
                if (!sessionSnapshot.exists()) {
                    Log.e(TAG, "Session not found: " + sessionId);
                    if (listener != null) {
                        listener.onError("Session not found");
                    }
                    return;
                }

                try {
                    // Get arrays from Firebase
                    List<SensorData> upperBackArray = new ArrayList<>();
                    List<SensorData> lowerBackArray = new ArrayList<>();

                    DataSnapshot upperArraySnapshot = sessionSnapshot.child("upperBackArray");
                    DataSnapshot lowerArraySnapshot = sessionSnapshot.child("lowerBackArray");

                    Log.d(TAG, "upperBackArray count: " + upperArraySnapshot.getChildrenCount());
                    Log.d(TAG, "lowerBackArray count: " + lowerArraySnapshot.getChildrenCount());

                    // Parse upper back array
                    for (DataSnapshot reading : upperArraySnapshot.getChildren()) {
                        SensorData data = reading.getValue(SensorData.class);
                        if (data != null) {
                            upperBackArray.add(data);
                        }
                    }

                    // Parse lower back array
                    for (DataSnapshot reading : lowerArraySnapshot.getChildren()) {
                        SensorData data = reading.getValue(SensorData.class);
                        if (data != null) {
                            lowerBackArray.add(data);
                        }
                    }

                    if (upperBackArray.isEmpty() || lowerBackArray.isEmpty()) {
                        Log.e(TAG, "No array data found for session: " + sessionId);
                        Log.e(TAG, "  upperBackArray size: " + upperBackArray.size());
                        Log.e(TAG, "  lowerBackArray size: " + lowerBackArray.size());
                        if (listener != null) {
                            listener.onError("No sensor data arrays found for this session");
                        }
                        return;
                    }

                    // Calculate metadata
                    int sampleCount = upperBackArray.size();

                    long firstTimestamp = upperBackArray.get(0).timestamp;
                    long lastTimestamp = upperBackArray.get(upperBackArray.size() - 1).timestamp;
                    long durationMs = lastTimestamp - firstTimestamp;

                    float sampleRateHz = durationMs > 0 ?
                            (sampleCount * 1000f) / durationMs : 0f;
                    float durationSeconds = durationMs / 1000f;

                    // Build JSON (same as before)
                    StringBuilder json = new StringBuilder();
                    json.append("{\n");
                    json.append("  \"user_id\": \"").append(userId).append("\",\n");
                    json.append("  \"sample_rate_hz\": ").append(String.format(Locale.US, "%.2f", sampleRateHz)).append(",\n");
                    json.append("  \"duration_seconds\": ").append(String.format(Locale.US, "%.2f", durationSeconds)).append(",\n");
                    json.append("  \"sample_count\": ").append(sampleCount).append(",\n");

                    // Upper back array
                    json.append("  \"upper_back\": [\n");
                    for (int i = 0; i < upperBackArray.size(); i++) {
                        SensorData reading = upperBackArray.get(i);
                        json.append("    {");
                        json.append("\"ax\": ").append(String.format(Locale.US, "%.4f", reading.accelX)).append(", ");
                        json.append("\"ay\": ").append(String.format(Locale.US, "%.4f", reading.accelY)).append(", ");
                        json.append("\"az\": ").append(String.format(Locale.US, "%.4f", reading.accelZ)).append(", ");
                        json.append("\"gx\": ").append(String.format(Locale.US, "%.4f", reading.gyroX)).append(", ");
                        json.append("\"gy\": ").append(String.format(Locale.US, "%.4f", reading.gyroY)).append(", ");
                        json.append("\"gz\": ").append(String.format(Locale.US, "%.4f", reading.gyroZ));
                        json.append("}");
                        if (i < upperBackArray.size() - 1) json.append(",");
                        json.append("\n");
                    }
                    json.append("  ],\n");

                    // Lower back array
                    json.append("  \"lower_back\": [\n");
                    for (int i = 0; i < lowerBackArray.size(); i++) {
                        SensorData reading = lowerBackArray.get(i);
                        json.append("    {");
                        json.append("\"ax\": ").append(String.format(Locale.US, "%.4f", reading.accelX)).append(", ");
                        json.append("\"ay\": ").append(String.format(Locale.US, "%.4f", reading.accelY)).append(", ");
                        json.append("\"az\": ").append(String.format(Locale.US, "%.4f", reading.accelZ)).append(", ");
                        json.append("\"gx\": ").append(String.format(Locale.US, "%.4f", reading.gyroX)).append(", ");
                        json.append("\"gy\": ").append(String.format(Locale.US, "%.4f", reading.gyroY)).append(", ");
                        json.append("\"gz\": ").append(String.format(Locale.US, "%.4f", reading.gyroZ));
                        json.append("}");
                        if (i < lowerBackArray.size() - 1) json.append(",");
                        json.append("\n");
                    }
                    json.append("  ]\n");
                    json.append("}");

                    Log.d(TAG, "✓ Inference JSON generated for session: " + sessionId);
                    Log.d(TAG, "  Sample count: " + sampleCount);

                    if (listener != null) {
                        listener.onJSONGenerated(json.toString());
                    }

                } catch (Exception e) {
                    Log.e(TAG, "Error generating inference JSON: " + e.getMessage(), e);
                    if (listener != null) {
                        listener.onError(e.getMessage());
                    }
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Error reading session: " + error.getMessage());
                if (listener != null) {
                    listener.onError(error.getMessage());
                }
            }
        });
    }


}