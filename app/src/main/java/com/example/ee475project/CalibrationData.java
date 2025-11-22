package com.example.ee475project;

public class CalibrationData {
    // Upper back sensor calibration
    public SensorAngles upperBackUpright;
    public SensorAngles upperBackSlouch;

    // Lower back sensor calibration
    public SensorAngles lowerBackUpright;
    public SensorAngles lowerBackSlouch;

    // Calculated thresholds
    public float upperBackThreshold;
    public float lowerBackThreshold;

    public long calibrationTimestamp;
    public boolean isCalibrated;

    // Required empty constructor for Firebase
    public CalibrationData() {
        this.isCalibrated = false;
    }
}

class SensorAngles {
    public float roll;
    public float pitch;
    public float yaw;  // Can calculate from accel/gyro if needed

    public SensorAngles() {}

    public SensorAngles(float roll, float pitch, float yaw) {
        this.roll = roll;
        this.pitch = pitch;
        this.yaw = yaw;
    }

    // Calculate angles from IMU data
    public static SensorAngles fromSensorData(SensorData data) {
        // Using the math from your Arduino code:
        float roll = (float) Math.toDegrees(
                Math.atan2(data.accelY, data.accelZ)
        );

        float pitch = (float) Math.toDegrees(
                Math.atan2(-data.accelX,
                        Math.sqrt(data.accelY * data.accelY + data.accelZ * data.accelZ))
        );

        // Yaw can't be accurately calculated from accel alone
        // Could use gyro integration if needed
        float yaw = 0.0f;

        return new SensorAngles(roll, pitch, yaw);
    }
}