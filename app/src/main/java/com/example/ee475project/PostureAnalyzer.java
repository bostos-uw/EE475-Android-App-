package com.example.ee475project;

import android.util.Log;

import androidx.annotation.NonNull;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.Map;

public class PostureAnalyzer {

    private static final String TAG = "PostureAnalyzer";
    private static final float THRESHOLD_MULTIPLIER = 0.65f; // 65% of calibrated difference

    private DatabaseReference sessionsRef;
    private DatabaseReference statsRef;
    private DatabaseReference calibrationRef;
    private String userId;
    private CalibrationData calibrationData;

    public PostureAnalyzer() {
        userId = FirebaseAuth.getInstance().getCurrentUser().getUid();
        sessionsRef = FirebaseDatabase.getInstance()
                .getReference("posture_sessions")
                .child(userId);
        statsRef = FirebaseDatabase.getInstance()
                .getReference("daily_stats")
                .child(userId);
        calibrationRef = FirebaseDatabase.getInstance()
                .getReference("calibration_data")
                .child(userId);
    }

    /**
     * Main analysis function - processes all unanalyzed sessions
     */
    public void analyzeUnprocessedSessions(OnAnalysisCompleteListener listener) {
        Log.d(TAG, "Starting analysis of unprocessed sessions...");

        // First, load the user's calibration data
        calibrationRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    calibrationData = snapshot.getValue(CalibrationData.class);

                    if (calibrationData != null && calibrationData.isCalibrated) {
                        Log.d(TAG, "✓ Calibration data loaded:");
                        Log.d(TAG, "  Upper threshold: " + calibrationData.upperBackThreshold + "°");
                        Log.d(TAG, "  Lower threshold: " + calibrationData.lowerBackThreshold + "°");

                        // Process sessions with personalized calibration
                        processUnprocessedSessions(listener);
                    } else {
                        Log.w(TAG, "⚠ No calibration data found. Please calibrate first.");
                        if (listener != null) {
                            listener.onAnalysisError("Please complete calibration before analyzing posture.");
                        }
                    }
                } else {
                    Log.w(TAG, "⚠ No calibration data found. Please calibrate first.");
                    if (listener != null) {
                        listener.onAnalysisError("Please complete calibration before analyzing posture.");
                    }
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Error loading calibration: " + error.getMessage());
                if (listener != null) {
                    listener.onAnalysisError("Failed to load calibration: " + error.getMessage());
                }
            }
        });
    }

    private void processUnprocessedSessions(OnAnalysisCompleteListener listener) {

        sessionsRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Log.d(TAG, "Total sessions in database: " + snapshot.getChildrenCount());

                // List all sessions
                for (DataSnapshot child : snapshot.getChildren()) {
                    PostureSession session = child.getValue(PostureSession.class);
                    if (session != null) {
                        Log.d(TAG, "Session: " + session.sessionId +
                                " analyzed=" + session.analyzed +
                                " upperBack=" + (session.upperBack != null) +
                                " lowerBack=" + (session.lowerBack != null));
                    }
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Error: " + error.getMessage());
            }
        });

        // Query for sessions where analyzed = false
        sessionsRef.orderByChild("analyzed").equalTo(false)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        int totalSessions = 0;
                        int analyzedCount = 0;
                        int slouchingSessions = 0;

                        Log.d(TAG, "Found " + snapshot.getChildrenCount() + " unprocessed sessions");

                        for (DataSnapshot sessionSnapshot : snapshot.getChildren()) {
                            PostureSession session = sessionSnapshot.getValue(PostureSession.class);

                            if (session != null) {
                                totalSessions++;

                                // Check if we have both upper and lower back data
                                if (session.upperBack != null && session.lowerBack != null) {

                                    // ===== CALL THE ALGORITHM =====
                                    AnalysisResult result = detectSlouchWithCalibration(
                                            session.upperBack,
                                            session.lowerBack
                                    );

                                    if (result.isSlouchingDetected) {
                                        slouchingSessions++;
                                    }

                                    // Update this session in Firebase with detailed results
                                    Map<String, Object> updates = new HashMap<>();
                                    updates.put("analyzed", true);
                                    updates.put("slouching", result.isSlouchingDetected);
                                    updates.put("overallSlouchScore", result.overallSlouchScore);
                                    updates.put("upperBackDeviation", result.upperBackDeviation);
                                    updates.put("lowerBackDeviation", result.lowerBackDeviation);
                                    updates.put("upperBackScore", result.upperBackScore);
                                    updates.put("lowerBackScore", result.lowerBackScore);
                                    updates.put("calibrationTimestamp", calibrationData.calibrationTimestamp);

                                    sessionSnapshot.getRef().updateChildren(updates)
                                            .addOnSuccessListener(aVoid -> {
                                                Log.d(TAG, "✓ Analyzed: " + session.sessionId +
                                                        " → Slouching: " + result.isSlouchingDetected +
                                                        " (Score: " + result.overallSlouchScore + "/100)");
                                            })
                                            .addOnFailureListener(e -> {
                                                Log.e(TAG, "✗ Failed to update: " + e.getMessage());
                                            });

                                    analyzedCount++;

                                    // NEW: Use session's timestamp to get the correct date
                                    String sessionDateKey = getDateKeyFromTimestamp(session.timestamp);
                                    saveDailyStats(userId, getTodayDateKey(), result.isSlouchingDetected);

                                } else {
                                    Log.w(TAG, "⚠ Session incomplete (missing sensor data): " +
                                            session.sessionId);
                                }
                            }
                        }

                        // Notify completion
                        if (listener != null) {
                            listener.onAnalysisComplete(analyzedCount, slouchingSessions);
                        }

                        Log.d(TAG, "Analysis complete: " + analyzedCount + " sessions analyzed, " +
                                slouchingSessions + " slouching detected");
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        Log.e(TAG, "Error querying sessions: " + error.getMessage());
                        if (listener != null) {
                            listener.onAnalysisError(error.getMessage());
                        }
                    }
                });
    }

    private String getDateKeyFromTimestamp(long timestamp) {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
        return sdf.format(new java.util.Date(timestamp));
    }

    /**
     * REAL SLOUCH DETECTION ALGORITHM
     * Uses personalized calibration data with 65% threshold
     */
    /**
     * ENHANCED SLOUCH DETECTION ALGORITHM - RELATIVE CURVATURE METHOD
     * Measures spine curvature (difference between upper and lower back angles)
     * This is more accurate than absolute tilt and immune to whole-body movements
     */
    private AnalysisResult detectSlouchWithCalibration(SensorData upperBack, SensorData lowerBack) {
        AnalysisResult result = new AnalysisResult();

        // ===== DEBUG: Log raw sensor data =====
        Log.d(TAG, "════════════════════════════════════════════════");
        Log.d(TAG, "RAW SENSOR DATA:");
        Log.d(TAG, "  Upper Back - Accel: (" + upperBack.accelX + ", " + upperBack.accelY + ", " + upperBack.accelZ + ")");
        Log.d(TAG, "  Upper Back - Gyro: (" + upperBack.gyroX + ", " + upperBack.gyroY + ", " + upperBack.gyroZ + ")");
        Log.d(TAG, "  Lower Back - Accel: (" + lowerBack.accelX + ", " + lowerBack.accelY + ", " + lowerBack.accelZ + ")");
        Log.d(TAG, "  Lower Back - Gyro: (" + lowerBack.gyroX + ", " + lowerBack.gyroY + ", " + lowerBack.gyroZ + ")");

        // Convert sensor data to angles
        SensorAngles currentUpper = SensorAngles.fromSensorData(upperBack);
        SensorAngles currentLower = SensorAngles.fromSensorData(lowerBack);

        // ===== DEBUG: Log calibration data =====
        Log.d(TAG, "CALIBRATION DATA:");
        Log.d(TAG, "  Upright Upper - Roll: " + calibrationData.upperBackUpright.roll + "° Pitch: " + calibrationData.upperBackUpright.pitch + "°");
        Log.d(TAG, "  Upright Lower - Roll: " + calibrationData.lowerBackUpright.roll + "° Pitch: " + calibrationData.lowerBackUpright.pitch + "°");
        Log.d(TAG, "  Slouch Upper - Roll: " + calibrationData.upperBackSlouch.roll + "° Pitch: " + calibrationData.upperBackSlouch.pitch + "°");
        Log.d(TAG, "  Slouch Lower - Roll: " + calibrationData.lowerBackSlouch.roll + "° Pitch: " + calibrationData.lowerBackSlouch.pitch + "°");
        Log.d(TAG, "  Pitch Threshold: " + calibrationData.upperBackThreshold + "°");
        Log.d(TAG, "  Roll Threshold: " + calibrationData.lowerBackThreshold + "°");

        // Calculate CURRENT spine curvature
        float currentPitchDiff = currentUpper.pitch - currentLower.pitch;
        float currentRollDiff = currentUpper.roll - currentLower.roll;

        // Calculate UPRIGHT spine curvature baseline
        float uprightPitchDiff = calibrationData.upperBackUpright.pitch - calibrationData.lowerBackUpright.pitch;
        float uprightRollDiff = calibrationData.upperBackUpright.roll - calibrationData.lowerBackUpright.roll;

        // Calculate deviation
        float pitchDeviation = Math.abs(currentPitchDiff - uprightPitchDiff);
        float rollDeviation = Math.abs(currentRollDiff - uprightRollDiff);

        result.upperBackDeviation = pitchDeviation;
        result.lowerBackDeviation = rollDeviation;

        // Apply 65% threshold
        float pitchThreshold = calibrationData.upperBackThreshold * THRESHOLD_MULTIPLIER;
        float rollThreshold = calibrationData.lowerBackThreshold * THRESHOLD_MULTIPLIER;

        Log.d(TAG, "CURRENT ANALYSIS:");
        Log.d(TAG, "  Current Upper - Roll: " + currentUpper.roll + "° Pitch: " + currentUpper.pitch + "°");
        Log.d(TAG, "  Current Lower - Roll: " + currentLower.roll + "° Pitch: " + currentLower.pitch + "°");
        Log.d(TAG, "  Current spine - Pitch diff: " + currentPitchDiff + "° Roll diff: " + currentRollDiff + "°");
        Log.d(TAG, "  Upright spine - Pitch diff: " + uprightPitchDiff + "° Roll diff: " + uprightRollDiff + "°");
        Log.d(TAG, "  Deviations - Pitch: " + pitchDeviation + "° Roll: " + rollDeviation + "°");
        Log.d(TAG, "  Thresholds - Pitch: " + pitchThreshold + "° Roll: " + rollThreshold + "°");

        // Check if slouching
        boolean pitchSlouchDetected = pitchDeviation > pitchThreshold;
        boolean rollSlouchDetected = rollDeviation > rollThreshold;

        result.isSlouchingDetected = pitchSlouchDetected || rollSlouchDetected;

        // Calculate scores
        result.upperBackScore = Math.min(100, (int)((pitchDeviation / calibrationData.upperBackThreshold) * 100));
        result.lowerBackScore = Math.min(100, (int)((rollDeviation / calibrationData.lowerBackThreshold) * 100));
        result.overallSlouchScore = Math.max(result.upperBackScore, result.lowerBackScore);

        Log.d(TAG, "RESULTS:");
        Log.d(TAG, "  Pitch slouch: " + pitchSlouchDetected + " (score: " + result.upperBackScore + "/100)");
        Log.d(TAG, "  Roll slouch: " + rollSlouchDetected + " (score: " + result.lowerBackScore + "/100)");
        Log.d(TAG, "  FINAL: " + (result.isSlouchingDetected ? "SLOUCHING" : "GOOD POSTURE") +
                " (score: " + result.overallSlouchScore + "/100)");
        Log.d(TAG, "════════════════════════════════════════════════");

        return result;
    }

    /**
     * Save daily statistics to Firebase
     */
    private void saveDailyStats(String userId, String dateKey, boolean isSlouching) {
        DatabaseReference statsRef = FirebaseDatabase.getInstance()
                .getReference("daily_stats")
                .child(userId)
                .child(dateKey);

        DatabaseReference userRef = FirebaseDatabase.getInstance()
                .getReference("users")
                .child(userId);

        // First, get the current active_time and slouch_percentage to calculate actual time
        userRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot userSnapshot) {
                // Get current active time for today
                Float activeTime = userSnapshot.child("active_time").getValue(Float.class);
                float todayActiveMinutes = (activeTime != null) ? activeTime : 0f;

                // Now read existing daily stats
                statsRef.addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        // Get existing counts (default to 0 if not exists)
                        int existingTotal = 0;
                        int existingSlouching = 0;
                        int existingGoodPosture = 0;

                        if (snapshot.exists()) {
                            Integer total = snapshot.child("total_sessions").getValue(Integer.class);
                            Integer slouch = snapshot.child("slouching_sessions").getValue(Integer.class);
                            Integer good = snapshot.child("good_posture_sessions").getValue(Integer.class);

                            existingTotal = (total != null) ? total : 0;
                            existingSlouching = (slouch != null) ? slouch : 0;
                            existingGoodPosture = (good != null) ? good : 0;
                        }

                        // Add the new session
                        int newTotal = existingTotal + 1;
                        int newSlouching = existingSlouching + (isSlouching ? 1 : 0);
                        int newGoodPosture = existingGoodPosture + (isSlouching ? 0 : 1);

                        // Calculate new slouch percentage
                        double newSlouchPercentage = newTotal > 0 ?
                                (newSlouching * 100.0 / newTotal) : 0.0;

                        // Calculate actual time breakdown
                        float slouchMinutes = todayActiveMinutes * (float)(newSlouchPercentage / 100.0);
                        float uprightMinutes = todayActiveMinutes - slouchMinutes;

                        // Update all stats INCLUDING actual time values
                        statsRef.child("total_sessions").setValue(newTotal);
                        statsRef.child("slouching_sessions").setValue(newSlouching);
                        statsRef.child("good_posture_sessions").setValue(newGoodPosture);
                        statsRef.child("slouch_percentage").setValue(newSlouchPercentage);

                        // NEW: Save actual time values
                        statsRef.child("upright_minutes").setValue(uprightMinutes);
                        statsRef.child("slouch_minutes").setValue(slouchMinutes);
                        statsRef.child("active_minutes").setValue(todayActiveMinutes);

                        statsRef.child("last_updated").setValue(System.currentTimeMillis());

                        Log.d(TAG, "Daily stats updated for " + dateKey +
                                ": total=" + newTotal +
                                ", slouching=" + newSlouching +
                                ", good=" + newGoodPosture +
                                ", slouch%=" + String.format("%.1f", newSlouchPercentage) +
                                ", upright=" + uprightMinutes + "min" +
                                ", slouch=" + slouchMinutes + "min");
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        Log.e(TAG, "Error reading daily stats: " + error.getMessage());
                    }
                });
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Error reading user active time: " + error.getMessage());
            }
        });
    }

    /**
     * Get today's date in format "yyyy-MM-dd"
     */
    private String getTodayDateKey() {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
        return sdf.format(new java.util.Date());
    }

    /**
     * Result class to hold analysis details
     */
    private static class AnalysisResult {
        boolean isSlouchingDetected = false;
        float upperBackDeviation = 0;
        float lowerBackDeviation = 0;
        int upperBackScore = 0;
        int lowerBackScore = 0;
        int overallSlouchScore = 0;
    }

    /**
     * Callback interface for analysis completion
     */
    public interface OnAnalysisCompleteListener {
        void onAnalysisComplete(int sessionsAnalyzed, int slouchingSessions);
        void onAnalysisError(String error);
    }
}