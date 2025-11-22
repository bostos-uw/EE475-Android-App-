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

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.UUID;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

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

    private final DatabaseReference userDbRef;
    private final StringBuilder dataBuffer = new StringBuilder();

    private BluetoothGatt bluetoothGatt;

    private static final String TAG = "BluetoothViewModel";
    private static final String DEVICE_NAME_UPPER = "XIAO_Upper_Back";
    private static final String DEVICE_NAME_LOWER = "XIAO_Lower_Back";
    private static final long SCAN_PERIOD = 10000; // Stops scanning after 10 seconds.
    private static final long CONNECTION_TIME = 10000; // 10 seconds

    // Nordic UART Service UUIDs
    private static final UUID UART_SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID UART_TX_CHARACTERISTIC_UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E");
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

    public BluetoothViewModel(@NonNull Application application) {
        super(application);
        BluetoothManager bluetoothManager = (BluetoothManager) application.getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();
        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user != null) {
            userDbRef = FirebaseDatabase.getInstance().getReference("users").child(user.getUid());
            loadPersistentData();
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
                BluetoothDevice device = result.getDevice();
                if (device != null && device.getName() != null) {
                    if (isCycling && device.getName().equals(deviceNames[currentDeviceIndex])) {
                        handler.removeCallbacks(stopScanRunnable);
                        bluetoothLeScanner.stopScan(leScanCallback);
                        connectToDevice(device);
                    } else if (!isCycling && (device.getName().startsWith(DEVICE_NAME_UPPER) || device.getName().startsWith(DEVICE_NAME_LOWER))) {
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

    private void loadPersistentData() {
        if (userDbRef == null) return;

        userDbRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    Float totalTime = snapshot.child("total_connection_time").getValue(Float.class);
                    totalConnectionTime.setValue(totalTime != null ? totalTime : 0f);

                    Long lastReset = snapshot.child("last_active_time_reset").getValue(Long.class);
                    if (lastReset == null) lastReset = 0L;

                    Calendar cal = Calendar.getInstance();
                    cal.set(Calendar.HOUR_OF_DAY, 0);
                    cal.set(Calendar.MINUTE, 0);
                    cal.set(Calendar.SECOND, 0);
                    cal.set(Calendar.MILLISECOND, 0);
                    long today = cal.getTimeInMillis();

                    if (lastReset < today) {
                        activeTime.setValue(0f);
                        userDbRef.child("last_active_time_reset").setValue(today);
                    } else {
                        Float actTime = snapshot.child("active_time").getValue(Float.class);
                        activeTime.setValue(actTime != null ? actTime : 0f);
                    }
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
        isCycling = true;
        currentDeviceIndex = 0;
        isCycleComplete.setValue(false);
        scanForNextDevice();
    }

    private void scanForNextDevice() {
        if (!isCycling) return;

        String deviceName = deviceNames[currentDeviceIndex];
        List<ScanFilter> filters = new ArrayList<>();
        filters.add(new ScanFilter.Builder().setDeviceName(deviceName).build());

        ScanSettings scanSettings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        bluetoothLeScanner.startScan(filters, scanSettings, leScanCallback);
        connectionStatus.setValue("Scanning for " + deviceName);

        // Stop scanning after a predefined scan period.
        handler.postDelayed(stopScanRunnable, SCAN_PERIOD);
    }

    public void cancelScan() {
        isCycling = false;
        isCycleComplete.setValue(false);
        if (bluetoothLeScanner != null) {
            handler.removeCallbacks(stopScanRunnable);
            stopScanRunnable.run();
        }
    }

    public void disconnect() {
        isCycling = false;
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
            float hours = millis / (1000f * 60 * 60);
            float minutes = millis / (1000f * 60);

            Float currentTotal = totalConnectionTime.getValue();
            if (currentTotal == null) currentTotal = 0f;
            totalConnectionTime.postValue(currentTotal + hours);

            Float currentActive = activeTime.getValue();
            if (currentActive == null) currentActive = 0f;
            activeTime.postValue(currentActive + minutes);

            userDbRef.child("total_connection_time").setValue(totalConnectionTime.getValue());
            userDbRef.child("active_time").setValue(activeTime.getValue());

            connectionStartTime = System.currentTimeMillis(); // Reset start time for next interval
            timerHandler.postDelayed(this, 1000); // Update every second
        }
    };


    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            String deviceName = gatt.getDevice().getName();

            if (newState == BluetoothGatt.STATE_CONNECTED) {
                // ALWAYS create a new session if one doesn't exist
                if (currentSessionId == null && sessionsRef != null) {
                    currentSessionId = generateSessionId();
                    sessionStartTime = System.currentTimeMillis();

                    // Create session in Firebase with ALL required fields
                    PostureSession session = new PostureSession(
                            currentSessionId,
                            FirebaseAuth.getInstance().getCurrentUser().getUid(),
                            sessionStartTime
                    );

                    // Write the entire session object to Firebase
                    sessionsRef.child(currentSessionId).setValue(session)
                            .addOnSuccessListener(aVoid ->
                                    Log.d(TAG, "✓ New session created: " + currentSessionId))
                            .addOnFailureListener(e ->
                                    Log.e(TAG, "✗ Failed to create session: " + e.getMessage()));
                }

                connectionStatus.postValue("Connected to " + deviceName);
                isConnected.postValue(true);
                connectionStartTime = System.currentTimeMillis();
                timerHandler.post(timerRunnable);

                bluetoothGatt = gatt;
                gatt.discoverServices();

                if (isCycling) {
                    handler.postDelayed(() -> gatt.disconnect(), CONNECTION_TIME);
                }

            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                timerHandler.removeCallbacks(timerRunnable);
                dataBuffer.setLength(0);
                connectionStatus.postValue("Disconnected from " + deviceName);
                isConnected.postValue(false);
                gatt.close();

                if (bluetoothGatt == gatt) {
                    bluetoothGatt = null;
                }

                if (isCycling) {
                    currentDeviceIndex = (currentDeviceIndex + 1) % deviceNames.length;

                    // After completing a full cycle (both devices), end the session
                    if (currentDeviceIndex == 0) {
                        Log.d(TAG, "✓ Session cycle complete: " + currentSessionId);
                        currentSessionId = null;  // Next connection will create new session

                        // Notify that cycle is complete
                        isCycleComplete.postValue(true);
                        // Reset the flag after a short delay
//                        handler.postDelayed(() -> isCycleComplete.postValue(false), 100);

                    }

                    handler.postDelayed(() -> scanForNextDevice(), 1000);
                }
            }
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
    };  // END of gattCallback

    private String generateSessionId() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
        return "session_" + sdf.format(new Date());
    }

    private void parseSplitMessage(String dataString) {
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

                Log.d(TAG, identifier + " - Accel received: " + x + "," + y + "," + z);
            }
            else if (dataType.equals("G")) {
                tempGyroX = x;
                tempGyroY = y;
                tempGyroZ = z;
                hasGyroData = true;

                Log.d(TAG, identifier + " - Gyro received: " + x + "," + y + "," + z);

                if (hasAccelData && hasGyroData && currentSensor.equals(identifier)) {
                    ImuData imuData = new ImuData(
                            tempAccelX, tempAccelY, tempAccelZ,
                            tempGyroX, tempGyroY, tempGyroZ
                    );

                    if (currentSessionId != null) {
                        SensorData sensorData = new SensorData(
                                tempAccelX, tempAccelY, tempAccelZ,
                                tempGyroX, tempGyroY, tempGyroZ,
                                System.currentTimeMillis()
                        );

                        if (identifier.equals("UB")) {
                            sessionsRef.child(currentSessionId)
                                    .child("upperBack")
                                    .setValue(sensorData);

                            upperBackData.postValue(imuData);  // ADD THIS - was missing
                            Log.d(TAG, "Upper back data saved to session: " + currentSessionId);

                        } else if (identifier.equals("LB")) {
                            sessionsRef.child(currentSessionId)
                                    .child("lowerBack")
                                    .setValue(sensorData);

                            lowerBackData.postValue(imuData);
                            Log.d(TAG, "Lower back data saved to session: " + currentSessionId);
                        }
                    }

                    // Reset flags for next pair
                    hasAccelData = false;
                    hasGyroData = false;
                    currentSensor = "";
                }
            } else {
                Log.w(TAG, "Unknown data type: " + dataType);
            }

        } catch (NumberFormatException e) {
            Log.e(TAG, "Error parsing float values from: " + dataString, e);
        } catch (Exception e) {
            Log.e(TAG, "Error parsing split message: " + dataString, e);
        }
    }
}  // END of BluetoothViewModel class