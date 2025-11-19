package com.example.ee475project;

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
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.UUID;

public class BluetoothViewModel extends AndroidViewModel {

    private final BluetoothAdapter bluetoothAdapter;
    private final BluetoothLeScanner bluetoothLeScanner;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final MutableLiveData<String> connectionStatus = new MutableLiveData<>("Disconnected");
    private final MutableLiveData<Boolean> isConnected = new MutableLiveData<>(false);
    private final MutableLiveData<ImuData> upperBackData = new MutableLiveData<>();
    private final MutableLiveData<ImuData> lowerBackData = new MutableLiveData<>();
    private final MutableLiveData<Float> totalConnectionTime = new MutableLiveData<>(0f);
    private final MutableLiveData<Float> activeTime = new MutableLiveData<>(0f);
    private long connectionStartTime;
    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private final SharedPreferences sharedPreferences;


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

    public BluetoothViewModel(@NonNull Application application) {
        super(application);
        sharedPreferences = application.getSharedPreferences("posture_prefs", Context.MODE_PRIVATE);
        BluetoothManager bluetoothManager = (BluetoothManager) application.getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();
        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        loadPersistentData();

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
        totalConnectionTime.setValue(sharedPreferences.getFloat("total_connection_time", 0f));

        long lastReset = sharedPreferences.getLong("last_active_time_reset", 0);
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long today = cal.getTimeInMillis();

        if (lastReset < today) {
            activeTime.setValue(0f);
            sharedPreferences.edit().putLong("last_active_time_reset", today).apply();
        } else {
            activeTime.setValue(sharedPreferences.getFloat("active_time", 0f));
        }
    }

    public LiveData<String> getConnectionStatus() {
        return connectionStatus;
    }

    public LiveData<Boolean> getIsConnected() {
        return isConnected;
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
            long millis = System.currentTimeMillis() - connectionStartTime;
            float hours = millis / (1000f * 60 * 60);
            float minutes = millis / (1000f * 60);

            Float currentTotal = totalConnectionTime.getValue();
            if (currentTotal == null) currentTotal = 0f;
            totalConnectionTime.postValue(currentTotal + hours);

            Float currentActive = activeTime.getValue();
            if (currentActive == null) currentActive = 0f;
            activeTime.postValue(currentActive + minutes);

            sharedPreferences.edit().putFloat("total_connection_time", totalConnectionTime.getValue()).apply();
            sharedPreferences.edit().putFloat("active_time", activeTime.getValue()).apply();

            connectionStartTime = System.currentTimeMillis(); // Reset start time for next interval
            timerHandler.postDelayed(this, 1000); // Update every second
        }
    };


    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            String deviceName = gatt.getDevice().getName();
            if (newState == BluetoothGatt.STATE_CONNECTED) {
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
                connectionStatus.postValue("Disconnected from " + deviceName);
                isConnected.postValue(false);
                gatt.close();
                if (bluetoothGatt == gatt) {
                    bluetoothGatt = null;
                }

                if (isCycling) {
                    currentDeviceIndex = (currentDeviceIndex + 1) % deviceNames.length;
                    handler.postDelayed(() -> scanForNextDevice(), 1000); // 1-second delay before scanning for the next device
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
            String dataString = new String(data);

            try {
                String[] parts = dataString.split("\\|");
                if (parts.length == 3) {
                    String identifier = parts[0];
                    String[] accelParts = parts[1].substring(2).split(",");
                    String[] gyroParts = parts[2].substring(2).split(",");

                    float accelX = Float.parseFloat(accelParts[0]);
                    float accelY = Float.parseFloat(accelParts[1]);
                    float accelZ = Float.parseFloat(accelParts[2]);

                    float gyroX = Float.parseFloat(gyroParts[0]);
                    float gyroY = Float.parseFloat(gyroParts[1]);
                    float gyroZ = Float.parseFloat(gyroParts[2]);

                    ImuData imuData = new ImuData(accelX, accelY, accelZ, gyroX, gyroY, gyroZ);

                    if (identifier.equals("UB")) {
                        upperBackData.postValue(imuData);
                    } else if (identifier.equals("LB")) {
                        lowerBackData.postValue(imuData);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error parsing IMU data: " + dataString, e);
            }
        }
    };
}
