package com.example.ee475project;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.lifecycle.Observer;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.util.ArrayList;
import java.util.List;

public class CalibrationHelper {

    private static final String TAG = "CalibrationHelper";
    private Context context;
    private DatabaseReference calibrationRef;
    private OnCalibrationCompleteListener listener;
    private BluetoothViewModel bluetoothViewModel;

    private static final long CONNECTION_TIMEOUT = 15000; // 15 seconds timeout for finding devices
    private Runnable connectionTimeoutRunnable;
    private boolean isDeviceConnected = false;

    // Calibration state
    private enum CalibrationStep {
        IDLE,
        WAITING_FOR_UPRIGHT_POSITION,
        COLLECTING_UPRIGHT_DATA,
        WAITING_FOR_SLOUCH_POSITION,
        COLLECTING_SLOUCH_DATA,
        COMPLETE
    }

    private CalibrationStep currentStep = CalibrationStep.IDLE;
    private CalibrationData calibrationData;
    private Handler handler = new Handler(Looper.getMainLooper());

    // Store ALL collected sensor data during the 10-second windows
    private List<ImuData> upperBackDataList = new ArrayList<>();
    private List<ImuData> lowerBackDataList = new ArrayList<>();

    // Observers for sensor data
    private Observer<ImuData> upperBackObserver;
    private Observer<ImuData> lowerBackObserver;
    private Observer<Boolean> cycleCompleteObserver;

    public interface OnCalibrationCompleteListener {
        void onCalibrationComplete(CalibrationData data);
        void onCalibrationCancelled();
    }

    public CalibrationHelper(Context context, BluetoothViewModel bluetoothViewModel, OnCalibrationCompleteListener listener) {
        this.context = context;
        this.bluetoothViewModel = bluetoothViewModel;
        this.listener = listener;

        String userId = FirebaseAuth.getInstance().getCurrentUser().getUid();
        calibrationRef = FirebaseDatabase.getInstance()
                .getReference("calibration_data")
                .child(userId);

        calibrationData = new CalibrationData();
    }

    public void startCalibration() {
        // Reset ALL state to ensure clean start
        currentStep = CalibrationStep.WAITING_FOR_UPRIGHT_POSITION;
        upperBackDataList.clear();
        lowerBackDataList.clear();
        isDeviceConnected = false;

        // Clear any existing observers from previous calibration attempts
        cleanupAllObservers();

        // Reset calibration data
        calibrationData = new CalibrationData();

        // Show the first dialog
        showUprightDialog();
    }

    private void showUprightDialog() {
        new AlertDialog.Builder(context)
                .setTitle("Calibration Step 1 of 2")
                .setMessage("📏 UPRIGHT POSTURE\n\n" +
                        "Please sit in your BEST UPRIGHT POSTURE:\n\n" +
                        "• Keep your back straight\n" +
                        "• Shoulders back\n" +
                        "• Chin level\n\n" +
                        "When ready, press OK to start recording.\n" +
                        "Recording will take ~20 seconds.")
                .setPositiveButton("OK", (dialog, which) -> {
                    collectUprightData();
                })
                .setNegativeButton("Cancel", (dialog, which) -> {
                    cancelCalibration();
                })
                .setCancelable(false)
                .show();
    }

    private void collectUprightData() {
        currentStep = CalibrationStep.COLLECTING_UPRIGHT_DATA;

        // Clear previous data
        upperBackDataList.clear();
        lowerBackDataList.clear();
        isDeviceConnected = false;

        Toast.makeText(context, "Recording upright posture...\n~20 seconds", Toast.LENGTH_SHORT).show();

        // Set up observers to collect ALL data during the cycle
        setupDataCollectionObservers();

        // Set up cycle completion observer
        setupCycleCompleteObserver(true); // true = upright data

        // Add connection monitoring
        setupConnectionMonitoring();

        // Add a small delay before starting the cycle to ensure clean state
        handler.postDelayed(() -> {
            // Start the BLE cycle
            bluetoothViewModel.startCycle();

            // Set up timeout for connection
            setupConnectionTimeout("upright");
        }, 500);  // 500ms delay to ensure everything is reset
    }

    private void showSlouchDialog() {
        new AlertDialog.Builder(context)
                .setTitle("Calibration Step 2 of 2")
                .setMessage("📉 SLOUCHED POSTURE\n\n" +
                        "Now SLOUCH FORWARD as you normally would:\n\n" +
                        "• Lean forward\n" +
                        "• Round your shoulders\n" +
                        "• Relax your posture\n\n" +
                        "When ready, press OK to start recording.\n" +
                        "Recording will take ~20 seconds.")
                .setPositiveButton("OK", (dialog, which) -> {
                    collectSlouchData();
                })
                .setNegativeButton("Cancel", (dialog, which) -> {
                    cancelCalibration();
                })
                .setCancelable(false)
                .show();
    }

    private void collectSlouchData() {
        currentStep = CalibrationStep.COLLECTING_SLOUCH_DATA;

        // Clear previous data
        upperBackDataList.clear();
        lowerBackDataList.clear();
        isDeviceConnected = false;

        Toast.makeText(context, "Recording slouched posture...\n~20 seconds", Toast.LENGTH_SHORT).show();

        // Ensure any previous BLE operations are stopped
        bluetoothViewModel.cancelScan();
        bluetoothViewModel.disconnect();

        // Wait a bit longer before setting up observers and starting cycle
        handler.postDelayed(() -> {
            // Set up observers to collect ALL data during the cycle
            setupDataCollectionObservers();

            // Set up cycle completion observer
            setupCycleCompleteObserver(false); // false = slouch data

            // Add connection monitoring
            setupConnectionMonitoring();

            // Start the BLE cycle
            bluetoothViewModel.startCycle();

            // Set up timeout for connection
            setupConnectionTimeout("slouch");
        }, 1000);  // 1 second delay to ensure everything is properly disconnected
    }

    private void setupDataCollectionObservers() {
        // Upper back observer - collect ALL data points during the 10-second window
        upperBackObserver = imuData -> {
            if (imuData != null &&
                    (currentStep == CalibrationStep.COLLECTING_UPRIGHT_DATA ||
                            currentStep == CalibrationStep.COLLECTING_SLOUCH_DATA)) {
                upperBackDataList.add(imuData);
                Log.d(TAG, "Upper back data point collected. Total: " + upperBackDataList.size());
            }
        };

        // Lower back observer - collect ALL data points during the 10-second window
        lowerBackObserver = imuData -> {
            if (imuData != null &&
                    (currentStep == CalibrationStep.COLLECTING_UPRIGHT_DATA ||
                            currentStep == CalibrationStep.COLLECTING_SLOUCH_DATA)) {
                lowerBackDataList.add(imuData);
                Log.d(TAG, "Lower back data point collected. Total: " + lowerBackDataList.size());
            }
        };

        bluetoothViewModel.getUpperBackData().observeForever(upperBackObserver);
        bluetoothViewModel.getLowerBackData().observeForever(lowerBackObserver);
    }

    private void setupCycleCompleteObserver(final boolean isUprightData) {
        // Remove any existing cycle observer first
        if (cycleCompleteObserver != null) {
            bluetoothViewModel.getIsCycleComplete().removeObserver(cycleCompleteObserver);
        }

        cycleCompleteObserver = isComplete -> {
            if (isComplete != null && isComplete) {
                Log.d(TAG, "Cycle completed! Processing collected data...");

                // IMMEDIATELY remove observer to prevent double-triggering
                bluetoothViewModel.getIsCycleComplete().removeObserver(cycleCompleteObserver);
                cycleCompleteObserver = null;

                // Clean up data collection observers
                cleanupDataCollectionObservers();

                // Cancel any pending timeouts
                if (connectionTimeoutRunnable != null) {
                    handler.removeCallbacks(connectionTimeoutRunnable);
                    connectionTimeoutRunnable = null;
                }

                // Process the collected data
                if (isUprightData) {
                    processAndSaveUprightData();
                } else {
                    processAndSaveSlouchData();
                }
            }
        };

        bluetoothViewModel.getIsCycleComplete().observeForever(cycleCompleteObserver);
    }


    private void setupConnectionMonitoring() {
        // Monitor connection status to detect when devices connect
        bluetoothViewModel.getConnectionStatus().observeForever(connectionStatusObserver);
    }

    private Observer<String> connectionStatusObserver = status -> {
        if (status != null && status.startsWith("Connected to")) {
            isDeviceConnected = true;
            // Cancel the timeout since we've connected
            if (connectionTimeoutRunnable != null) {
                handler.removeCallbacks(connectionTimeoutRunnable);
                Log.d(TAG, "Device connected, cancelling timeout");
            }
        }
    };

    private void setupConnectionTimeout(String phase) {
        connectionTimeoutRunnable = () -> {
            if (!isDeviceConnected) {
                Log.e(TAG, "Timeout: No devices found after 15 seconds");

                // Show error dialog
                new AlertDialog.Builder(context)
                        .setTitle("Connection Failed")
                        .setMessage("⚠️ Could not connect to sensors.\n\n" +
                                "Please ensure:\n" +
                                "• Both sensors are powered on\n" +
                                "• Bluetooth is enabled\n" +
                                "• Sensors are within range\n\n" +
                                "Would you like to retry?")
                        .setPositiveButton("Retry", (dialog, which) -> {
                            // Retry the current step
                            if (phase.equals("upright")) {
                                collectUprightData();
                            } else {
                                collectSlouchData();
                            }
                        })
                        .setNegativeButton("Cancel", (dialog, which) -> {
                            cancelCalibration();
                        })
                        .setCancelable(false)
                        .show();

                // Stop the current scan
                bluetoothViewModel.cancelScan();
                cleanupAllObservers();
            }
        };

        handler.postDelayed(connectionTimeoutRunnable, CONNECTION_TIMEOUT);
        Log.d(TAG, "Connection timeout set for 15 seconds");
    }

    private void cleanupAllObservers() {
        cleanupDataCollectionObservers();

        if (cycleCompleteObserver != null) {
            bluetoothViewModel.getIsCycleComplete().removeObserver(cycleCompleteObserver);
            cycleCompleteObserver = null;
        }

        if (connectionStatusObserver != null) {
            bluetoothViewModel.getConnectionStatus().removeObserver(connectionStatusObserver);
        }

        if (connectionTimeoutRunnable != null) {
            handler.removeCallbacks(connectionTimeoutRunnable);
            connectionTimeoutRunnable = null;
        }
    }

    private void processAndSaveUprightData() {
        // Cancel any pending timeout
        if (connectionTimeoutRunnable != null) {
            handler.removeCallbacks(connectionTimeoutRunnable);
        }

        // Remove connection observer
        if (connectionStatusObserver != null) {
            bluetoothViewModel.getConnectionStatus().removeObserver(connectionStatusObserver);
        }

        // Stop any ongoing BLE operations
        bluetoothViewModel.cancelScan();
        bluetoothViewModel.disconnect();

        if (upperBackDataList.isEmpty() || lowerBackDataList.isEmpty()) {
            // Show specific error message
            new AlertDialog.Builder(context)
                    .setTitle("Incomplete Data")
                    .setMessage("⚠️ Not enough sensor data collected.\n\n" +
                            "Upper back samples: " + upperBackDataList.size() + "\n" +
                            "Lower back samples: " + lowerBackDataList.size() + "\n\n" +
                            "Would you like to retry?")
                    .setPositiveButton("Retry", (dialog, which) -> {
                        upperBackDataList.clear();
                        lowerBackDataList.clear();
                        collectUprightData();
                    })
                    .setNegativeButton("Cancel", (dialog, which) -> {
                        cancelCalibration();
                    })
                    .setCancelable(false)
                    .show();
            return;
        }

        Log.d(TAG, "Processing upright data:");
        Log.d(TAG, "  Upper back samples: " + upperBackDataList.size());
        Log.d(TAG, "  Lower back samples: " + lowerBackDataList.size());

        // Average all the collected data points
        ImuData avgUpperBack = averageImuData(upperBackDataList);
        ImuData avgLowerBack = averageImuData(lowerBackDataList);

        // Convert averaged IMU data to SensorData format
        SensorData upperBack = new SensorData(
                avgUpperBack.accelX, avgUpperBack.accelY, avgUpperBack.accelZ,
                avgUpperBack.gyroX, avgUpperBack.gyroY, avgUpperBack.gyroZ,
                System.currentTimeMillis()
        );

        SensorData lowerBack = new SensorData(
                avgLowerBack.accelX, avgLowerBack.accelY, avgLowerBack.accelZ,
                avgLowerBack.gyroX, avgLowerBack.gyroY, avgLowerBack.gyroZ,
                System.currentTimeMillis()
        );

        // Convert to angles
        SensorAngles upperAngles = SensorAngles.fromSensorData(upperBack);
        SensorAngles lowerAngles = SensorAngles.fromSensorData(lowerBack);

        // NEW APPROACH: Store the angles themselves (we'll use them to calculate difference)
        calibrationData.upperBackUpright = upperAngles;
        calibrationData.lowerBackUpright = lowerAngles;

        // Calculate spine curvature (difference between upper and lower)
        float uprightPitchDiff = upperAngles.pitch - lowerAngles.pitch;
        float uprightRollDiff = upperAngles.roll - lowerAngles.roll;

        Log.d(TAG, "✓ Upright data saved (averaged from " +
                upperBackDataList.size() + " upper, " +
                lowerBackDataList.size() + " lower samples):");
        Log.d(TAG, "  Upper - Roll: " + upperAngles.roll + "° Pitch: " + upperAngles.pitch + "°");
        Log.d(TAG, "  Lower - Roll: " + lowerAngles.roll + "° Pitch: " + lowerAngles.pitch + "°");
        Log.d(TAG, "  Spine Curvature - Pitch diff: " + uprightPitchDiff + "° Roll diff: " + uprightRollDiff + "°");

        Toast.makeText(context, "✓ Upright posture recorded!\n" +
                        "Spine curvature baseline established",
                Toast.LENGTH_LONG).show();

        // Reset before next step
        upperBackDataList.clear();
        lowerBackDataList.clear();
        isDeviceConnected = false;

        // Move to slouch step
        currentStep = CalibrationStep.WAITING_FOR_SLOUCH_POSITION;

        // Show slouch dialog
        handler.postDelayed(this::showSlouchDialog, 3000);
    }



    public boolean isCalibrating() {
        return currentStep != CalibrationStep.IDLE && currentStep != CalibrationStep.COMPLETE;
    }

    private void processAndSaveSlouchData() {
        // Cancel any pending timeout
        if (connectionTimeoutRunnable != null) {
            handler.removeCallbacks(connectionTimeoutRunnable);
        }

        // Remove connection observer
        if (connectionStatusObserver != null) {
            bluetoothViewModel.getConnectionStatus().removeObserver(connectionStatusObserver);
        }

        Log.d(TAG, "Processing slouch data:");
        Log.d(TAG, "  Upper back samples: " + upperBackDataList.size());
        Log.d(TAG, "  Lower back samples: " + lowerBackDataList.size());

        if (upperBackDataList.isEmpty() || lowerBackDataList.isEmpty()) {
            Toast.makeText(context, "Error: No sensor data collected", Toast.LENGTH_LONG).show();
            cancelCalibration();
            return;
        }

        Log.d(TAG, "Processing slouch data:");
        Log.d(TAG, "  Upper back samples: " + upperBackDataList.size());
        Log.d(TAG, "  Lower back samples: " + lowerBackDataList.size());

        // Average all the collected data points
        ImuData avgUpperBack = averageImuData(upperBackDataList);
        ImuData avgLowerBack = averageImuData(lowerBackDataList);

        Log.d(TAG, "Averaged slouch IMU data:");
        Log.d(TAG, "  Upper: Accel(" + avgUpperBack.accelX + "," + avgUpperBack.accelY + "," + avgUpperBack.accelZ + ")");
        Log.d(TAG, "  Lower: Accel(" + avgLowerBack.accelX + "," + avgLowerBack.accelY + "," + avgLowerBack.accelZ + ")");

        // Convert averaged IMU data to SensorData format
        SensorData upperBack = new SensorData(
                avgUpperBack.accelX, avgUpperBack.accelY, avgUpperBack.accelZ,
                avgUpperBack.gyroX, avgUpperBack.gyroY, avgUpperBack.gyroZ,
                System.currentTimeMillis()
        );

        SensorData lowerBack = new SensorData(
                avgLowerBack.accelX, avgLowerBack.accelY, avgLowerBack.accelZ,
                avgLowerBack.gyroX, avgLowerBack.gyroY, avgLowerBack.gyroZ,
                System.currentTimeMillis()
        );

        // Convert to angles
        SensorAngles upperAngles = SensorAngles.fromSensorData(upperBack);
        SensorAngles lowerAngles = SensorAngles.fromSensorData(lowerBack);

        // Store angles
        calibrationData.upperBackSlouch = upperAngles;
        calibrationData.lowerBackSlouch = lowerAngles;

        if (calibrationData.lowerBackSlouch == null) {
            Log.e(TAG, "✗ ERROR: lowerBackSlouch is NULL after assignment!");
        } else {
            Log.d(TAG, "✓ lowerBackSlouch saved: Roll=" + calibrationData.lowerBackSlouch.roll +
                    "° Pitch=" + calibrationData.lowerBackSlouch.pitch + "°");
        }

        // Calculate spine curvature when slouching
        float slouchPitchDiff = upperAngles.pitch - lowerAngles.pitch;
        float slouchRollDiff = upperAngles.roll - lowerAngles.roll;

        // Calculate upright spine curvature (for reference)
        float uprightPitchDiff = calibrationData.upperBackUpright.pitch - calibrationData.lowerBackUpright.pitch;
        float uprightRollDiff = calibrationData.upperBackUpright.roll - calibrationData.lowerBackUpright.roll;

        // NEW THRESHOLDS: Based on change in spine curvature
        // This measures how much the spine CURVES when slouching vs upright
        calibrationData.upperBackThreshold = Math.abs(slouchPitchDiff - uprightPitchDiff);
        calibrationData.lowerBackThreshold = Math.abs(slouchRollDiff - uprightRollDiff);

        calibrationData.calibrationTimestamp = System.currentTimeMillis();
        calibrationData.isCalibrated = true;

        Log.d(TAG, "✓ Slouch data saved (averaged from " +
                upperBackDataList.size() + " upper, " +
                lowerBackDataList.size() + " lower samples):");
        Log.d(TAG, "  Upper - Roll: " + upperAngles.roll + "° Pitch: " + upperAngles.pitch + "°");
        Log.d(TAG, "  Lower - Roll: " + lowerAngles.roll + "° Pitch: " + lowerAngles.pitch + "°");
        Log.d(TAG, "  Spine Curvature - Pitch diff: " + slouchPitchDiff + "° Roll diff: " + slouchRollDiff + "°");
        Log.d(TAG, "");
        Log.d(TAG, "Calculated thresholds (change in spine curvature):");
        Log.d(TAG, "  Pitch threshold: " + calibrationData.upperBackThreshold + "° (curvature change)");
        Log.d(TAG, "  Roll threshold: " + calibrationData.lowerBackThreshold + "° (twist change)");

        Toast.makeText(context, "✓ Slouched posture recorded!\n" +
                        "Pitch threshold: " + String.format("%.1f°", calibrationData.upperBackThreshold) + "\n" +
                        "Roll threshold: " + String.format("%.1f°", calibrationData.lowerBackThreshold),
                Toast.LENGTH_LONG).show();

        // Save to Firebase
        handler.postDelayed(this::saveCalibrationToFirebase, 1000);
    }

    private ImuData averageImuData(List<ImuData> dataList) {
        if (dataList.isEmpty()) {
            return new ImuData(0, 0, 0, 0, 0, 0);
        }

        float sumAccelX = 0, sumAccelY = 0, sumAccelZ = 0;
        float sumGyroX = 0, sumGyroY = 0, sumGyroZ = 0;

        for (ImuData data : dataList) {
            sumAccelX += data.accelX;
            sumAccelY += data.accelY;
            sumAccelZ += data.accelZ;
            sumGyroX += data.gyroX;
            sumGyroY += data.gyroY;
            sumGyroZ += data.gyroZ;
        }

        int count = dataList.size();
        return new ImuData(
                sumAccelX / count,
                sumAccelY / count,
                sumAccelZ / count,
                sumGyroX / count,
                sumGyroY / count,
                sumGyroZ / count
        );
    }

    private void saveCalibrationToFirebase() {
        calibrationRef.setValue(calibrationData)
                .addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "✓ Calibration saved to Firebase successfully!");

                    currentStep = CalibrationStep.COMPLETE;

                    // STOP THE CYCLING since calibration is complete
                    bluetoothViewModel.cancelScan();
                    bluetoothViewModel.disconnect();

                    // Show success dialog
                    new AlertDialog.Builder(context)
                            .setTitle("Calibration Complete!")
                            .setMessage("✓ Your personalized spine curvature thresholds have been saved.\n\n" +
                                    "Pitch curvature threshold: " + String.format("%.1f°", calibrationData.upperBackThreshold) + "\n" +
                                    "Roll twist threshold: " + String.format("%.1f°", calibrationData.lowerBackThreshold) + "\n\n" +
                                    "The system now detects slouching by measuring how your spine curves, not just tilt!")
                            .setPositiveButton("OK", (dialog, which) -> {
                                if (listener != null) {
                                    listener.onCalibrationComplete(calibrationData);
                                }
                            })
                            .setCancelable(false)
                            .show();
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "✗ Failed to save calibration to Firebase: " + e.getMessage());
                    Toast.makeText(context, "Error saving calibration: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                    cancelCalibration();
                });
    }



    private void cleanupDataCollectionObservers() {
        if (bluetoothViewModel != null) {
            if (upperBackObserver != null) {
                bluetoothViewModel.getUpperBackData().removeObserver(upperBackObserver);
            }
            if (lowerBackObserver != null) {
                bluetoothViewModel.getLowerBackData().removeObserver(lowerBackObserver);
            }
        }
    }

    private void cancelCalibration() {
        currentStep = CalibrationStep.IDLE;
        cleanupAllObservers();

        // Stop cycling if still running
        bluetoothViewModel.cancelScan();

        if (listener != null) {
            listener.onCalibrationCancelled();
        }
    }
}