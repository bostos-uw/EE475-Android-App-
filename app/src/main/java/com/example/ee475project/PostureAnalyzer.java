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
    private static final float THRESHOLD_MULTIPLIER = 0.65f;

    private DatabaseReference sessionsRef;
    private DatabaseReference statsRef;
    private DatabaseReference calibrationRef;
    private String userId;
    private CalibrationData calibrationData;

    private static final float FILTER_ALPHA = 0.5f;
    private Float previousFilteredPitchDiff = null;

    // Cleanup constants
    private static final long STALE_SESSION_THRESHOLD_MS = 24 * 60 * 60 * 1000; // 24 hours

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

    public interface OnCleanupCompleteListener {
        void onCleanupComplete(int deletedCount);
        void onCleanupError(String error);
    }

    /**
     * Clean up incomplete/stale sessions from Firebase
     */
    public void cleanupIncompleteSessions(OnCleanupCompleteListener listener) {
        sessionsRef.orderByChild("analyzed").equalTo(false)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        int deletedCount = 0;
                        long currentTime = System.currentTimeMillis();

                        for (DataSnapshot sessionSnapshot : snapshot.getChildren()) {
                            PostureSession session = sessionSnapshot.getValue(PostureSession.class);

                            if (session == null) {
                                sessionSnapshot.getRef().removeValue();
                                deletedCount++;
                                continue;
                            }

                            boolean shouldDelete = false;

                            // Missing sensor data
                            if (session.upperBack == null || session.lowerBack == null) {
                                shouldDelete = true;
                            }

                            // Stale session (older than 24 hours)
                            if (currentTime - session.timestamp > STALE_SESSION_THRESHOLD_MS) {
                                shouldDelete = true;
                            }

                            if (shouldDelete) {
                                sessionSnapshot.getRef().removeValue();
                                deletedCount++;
                            }
                        }

                        Log.d(TAG, "Cleanup: " + deletedCount + " incomplete sessions deleted");
                        if (listener != null) {
                            listener.onCleanupComplete(deletedCount);
                        }
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        if (listener != null) {
                            listener.onCleanupError(error.getMessage());
                        }
                    }
                });
    }

    public void analyzeUnprocessedSessions(OnAnalysisCompleteListener listener) {
        calibrationRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    calibrationData = snapshot.getValue(CalibrationData.class);

                    if (calibrationData != null && calibrationData.isCalibrated) {
                        processUnprocessedSessions(listener);
                    } else {
                        if (listener != null) {
                            listener.onAnalysisError("Please complete calibration first.");
                        }
                    }
                } else {
                    if (listener != null) {
                        listener.onAnalysisError("Please complete calibration first.");
                    }
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                if (listener != null) {
                    listener.onAnalysisError("Failed to load calibration: " + error.getMessage());
                }
            }
        });
    }

    private void processUnprocessedSessions(OnAnalysisCompleteListener listener) {
        // Query ONLY unanalyzed sessions (removed the debug query that listed ALL sessions)
        sessionsRef.orderByChild("analyzed").equalTo(false)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        int analyzedCount = 0;
                        int slouchingSessions = 0;
                        int skippedCount = 0;

                        Log.d(TAG, "Found " + snapshot.getChildrenCount() + " unprocessed sessions");

                        for (DataSnapshot sessionSnapshot : snapshot.getChildren()) {
                            PostureSession session = sessionSnapshot.getValue(PostureSession.class);

                            if (session != null && session.upperBack != null && session.lowerBack != null) {
                                // ✅ Session has complete data - analyze it
                                AnalysisResult result = detectSlouchWithCalibration(
                                        session.upperBack,
                                        session.lowerBack
                                );

                                if (result.isSlouchingDetected) {
                                    slouchingSessions++;
                                }

                                // Batched update - single write operation
                                Map<String, Object> updates = new HashMap<>();
                                updates.put("analyzed", true);
                                updates.put("slouching", result.isSlouchingDetected);
                                updates.put("overallSlouchScore", result.overallSlouchScore);
                                updates.put("upperBackDeviation", result.upperBackDeviation);
                                updates.put("lowerBackDeviation", result.lowerBackDeviation);
                                updates.put("upperBackScore", result.upperBackScore);
                                updates.put("lowerBackScore", result.lowerBackScore);
                                updates.put("calibrationTimestamp", calibrationData.calibrationTimestamp);

                                sessionSnapshot.getRef().updateChildren(updates);

                                Log.d(TAG, "✓ Analyzed: " + session.sessionId + " → " +
                                        (result.isSlouchingDetected ? "SLOUCHING" : "GOOD"));

                                analyzedCount++;

                                String sessionDateKey = getDateKeyFromTimestamp(session.timestamp);
                                saveDailyStatsOptimized(sessionDateKey, result.isSlouchingDetected);

                            } else {
                                // ⚠ Session incomplete - skip it (cleanup will handle later)
                                skippedCount++;
                                Log.w(TAG, "⚠ Skipped incomplete session: " +
                                        (session != null ? session.sessionId : "null") +
                                        " (upper=" + (session != null && session.upperBack != null) +
                                        ", lower=" + (session != null && session.lowerBack != null) + ")");
                            }
                        }

                        // Notify completion immediately
                        if (listener != null) {
                            listener.onAnalysisComplete(analyzedCount, slouchingSessions);
                        }

                        Log.d(TAG, "Analysis complete: " + analyzedCount + " analyzed, " +
                                slouchingSessions + " slouching, " + skippedCount + " skipped");
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
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
     * Optimized slouch detection - minimal logging
     */
    private AnalysisResult detectSlouchWithCalibration(SensorData upperBack, SensorData lowerBack) {
        AnalysisResult result = new AnalysisResult();

        SensorAngles currentUpper = SensorAngles.fromSensorData(upperBack);
        SensorAngles currentLower = SensorAngles.fromSensorData(lowerBack);

        float currentPitchDiff = currentUpper.pitch - currentLower.pitch;
        float currentRollDiff = currentUpper.roll - currentLower.roll;

        // Apply low-pass filter
        if (previousFilteredPitchDiff == null) {
            previousFilteredPitchDiff = currentPitchDiff;
        } else {
            currentPitchDiff = FILTER_ALPHA * currentPitchDiff + (1 - FILTER_ALPHA) * previousFilteredPitchDiff;
            previousFilteredPitchDiff = currentPitchDiff;
        }

        float uprightPitchDiff = calibrationData.upperBackUpright.pitch - calibrationData.lowerBackUpright.pitch;
        float uprightRollDiff = calibrationData.upperBackUpright.roll - calibrationData.lowerBackUpright.roll;

        float pitchDeviation = Math.abs(currentPitchDiff - uprightPitchDiff);
        float rollDeviation = Math.abs(currentRollDiff - uprightRollDiff);

        result.upperBackDeviation = pitchDeviation;
        result.lowerBackDeviation = rollDeviation;

        float pitchThreshold = calibrationData.upperBackThreshold * THRESHOLD_MULTIPLIER;

        result.isSlouchingDetected = pitchDeviation > pitchThreshold;

        result.upperBackScore = Math.min(100, (int)((pitchDeviation / calibrationData.upperBackThreshold) * 100));
        result.lowerBackScore = Math.min(100, (int)((rollDeviation / calibrationData.lowerBackThreshold) * 100));
        result.overallSlouchScore = Math.max(result.upperBackScore, result.lowerBackScore);

        return result;
    }

    /**
     * Optimized: Single read + single batched write
     */
    private void saveDailyStatsOptimized(String dateKey, boolean isSlouching) {
        DatabaseReference dayStatsRef = statsRef.child(dateKey);

        dayStatsRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
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

                int newTotal = existingTotal + 1;
                int newSlouching = existingSlouching + (isSlouching ? 1 : 0);
                int newGoodPosture = existingGoodPosture + (isSlouching ? 0 : 1);
                double newSlouchPercentage = (newSlouching * 100.0 / newTotal);

                // Single batched write
                Map<String, Object> updates = new HashMap<>();
                updates.put("total_sessions", newTotal);
                updates.put("slouching_sessions", newSlouching);
                updates.put("good_posture_sessions", newGoodPosture);
                updates.put("slouch_percentage", newSlouchPercentage);
                updates.put("last_updated", System.currentTimeMillis());

                dayStatsRef.updateChildren(updates);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Stats error: " + error.getMessage());
            }
        });
    }

    private static class AnalysisResult {
        boolean isSlouchingDetected = false;
        float upperBackDeviation = 0;
        float lowerBackDeviation = 0;
        int upperBackScore = 0;
        int lowerBackScore = 0;
        int overallSlouchScore = 0;
    }

    public interface OnAnalysisCompleteListener {
        void onAnalysisComplete(int sessionsAnalyzed, int slouchingSessions);
        void onAnalysisError(String error);
    }
}