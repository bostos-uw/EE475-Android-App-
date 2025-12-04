package com.example.ee475project;

import java.util.List;

public class PostureSession {
    public String sessionId;
    public String userId;
    public long timestamp;

    public List<SensorData> upperBackArray;
    public List<SensorData> lowerBackArray;

    public SensorData upperBack;
    public SensorData lowerBack;
    public Boolean analyzed;  // Has this session been analyzed yet?
    public Boolean slouching; // Result after analysis

    // ===== Analysis result fields (set by PostureAnalyzer) =====
    public int overallSlouchScore;
    public float upperBackDeviation;
    public float lowerBackDeviation;
    public int upperBackScore;
    public int lowerBackScore;
    public long calibrationTimestamp;

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