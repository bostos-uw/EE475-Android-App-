package com.example.ee475project;

public class PostureSession {
    public String sessionId;
    public String userId;
    public long timestamp;
    public SensorData upperBack;
    public SensorData lowerBack;
    public Boolean analyzed;  // Has this session been analyzed yet?
    public Boolean slouching; // Result after analysis

    // Required empty constructor for Firebase
    public PostureSession() {}

    public PostureSession(String sessionId, String userId, long timestamp) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.timestamp = timestamp;
        this.analyzed = false;
        this.slouching = null;
    }
}

class SensorData {
    public float accelX, accelY, accelZ;
    public float gyroX, gyroY, gyroZ;
    public long timestamp;

    public SensorData() {}

    public SensorData(float ax, float ay, float az, float gx, float gy, float gz, long timestamp) {
        this.accelX = ax;
        this.accelY = ay;
        this.accelZ = az;
        this.gyroX = gx;
        this.gyroY = gy;
        this.gyroZ = gz;
        this.timestamp = timestamp;
    }
}