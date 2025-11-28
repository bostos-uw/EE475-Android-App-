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

        if (bluetoothLeScanner != null) {
            handler.removeCallbacks(stopScanRunnable);
            bluetoothLeScanner.stopScan(leScanCallback);
        }

        if (bluetoothGatt != null) {
            bluetoothGatt.disconnect();
        }

        stopProgressUpdates();
        currentPhase.setValue("Cancelled");
        collectionProgress.setValue(0);
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

        // Update phase
        if (currentDeviceIndex == 0) {
            currentPhase.setValue("Upper Back");
        } else {
            currentPhase.setValue("Lower Back");
        }

        handler.postDelayed(stopScanRunnable, SCAN_PERIOD);
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

                // Auto-disconnect after 2 minutes
                if (isCycling) {
                    handler.postDelayed(() -> gatt.disconnect(), CONNECTION_TIME);
                }

            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                synchronized (dataBuffer) {
                    dataBuffer.setLength(0);
                }

                connectionStatus.postValue("Disconnected from " + deviceName);
                isConnected.postValue(false);
                gatt.close();

                stopProgressUpdates();

                if (bluetoothGatt == gatt) {
                    bluetoothGatt = null;
                }

                if (isCycling) {
                    // Log buffer sizes
                    Log.d(TAG, deviceName + " disconnected. Buffer size: " +
                            (deviceName.equals(DEVICE_NAME_UPPER) ? upperBackBuffer.size() : lowerBackBuffer.size()));

                    currentDeviceIndex = (currentDeviceIndex + 1) % deviceNames.length;

                    if (currentDeviceIndex == 0) {
                        // Cycle complete
                        Log.d(TAG, "✓ Training collection complete!");
                        Log.d(TAG, "  Upper back readings: " + upperBackBuffer.size());
                        Log.d(TAG, "  Lower back readings: " + lowerBackBuffer.size());

                        isCycling = false;
                        currentPhase.postValue("Complete");
                        collectionProgress.postValue(100);

                        // Apply timestamp shifting (Option B)
                        applyTimestampShifting();
                    } else {
                        // Continue to next sensor
                        handler.postDelayed(() -> scanForNextDevice(), 1000);
                    }
                }
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
}