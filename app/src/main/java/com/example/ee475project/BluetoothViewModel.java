package com.example.ee475project;

import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.ArrayList;
import java.util.List;

public class BluetoothViewModel extends AndroidViewModel {

    private final BluetoothAdapter bluetoothAdapter;
    private final BluetoothLeScanner bluetoothLeScanner;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final MutableLiveData<String> connectionStatus = new MutableLiveData<>("Disconnected");
    private final MutableLiveData<Boolean> isConnected = new MutableLiveData<>(false);
    private BluetoothGatt bluetoothGatt;

    private static final String DEVICE_NAME_UPPER = "XIAO_Upper_Back";
    private static final String DEVICE_NAME_LOWER = "XIAO_Lower_Back";
    private static final long SCAN_PERIOD = 10000; // Stops scanning after 10 seconds.
    private static final long CONNECTION_TIME = 10000; // 10 seconds

    private final ScanCallback leScanCallback;
    private final Runnable stopScanRunnable;
    private boolean isCycling = false;
    private int currentDeviceIndex = 0;
    private final String[] deviceNames = {DEVICE_NAME_UPPER, DEVICE_NAME_LOWER};

    public BluetoothViewModel(@NonNull Application application) {
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
                if (connectionStatus.getValue() != null && connectionStatus.getValue().equals("Scanning...")) {
                    connectionStatus.setValue("Disconnected");
                }
            }
        };
    }

    public LiveData<String> getConnectionStatus() {
        return connectionStatus;
    }

    public LiveData<Boolean> getIsConnected() {
        return isConnected;
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

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            String deviceName = gatt.getDevice().getName();
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                connectionStatus.postValue("Connected to " + deviceName);
                isConnected.postValue(true);
                bluetoothGatt = gatt;
                gatt.discoverServices();

                if (isCycling) {
                    handler.postDelayed(() -> gatt.disconnect(), CONNECTION_TIME);
                }

            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
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
    };
}
