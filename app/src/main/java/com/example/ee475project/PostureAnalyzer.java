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
import java.util.ArrayList;
import java.util.List;

public class PostureAnalyzer {

    private static final String TAG = "PostureAnalyzer";
    private static final float THRESHOLD_MULTIPLIER = 0.65f;

    // ✅ REDUCED: Only process 3 sessions at a time to avoid memory issues
    private static final int MAX_SESSIONS_PER_ANALYSIS = 3;
    private static final int MAX_CLEANUP_BATCH_SIZE = 10;

    // Session retention - 7 days
    private static final long SESSION_RETENTION_MS = 7 * 24 * 60 * 60 * 1000L;

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
     * ✅ NEW: Clean up sessions older than 7 days
     * Safe because daily_stats already has aggregated data
     */
    public void cleanupOldSessions(OnCleanupCompleteListener listener) {
        long cutoffTime = System.currentTimeMillis() - SESSION_RETENTION_MS;

        sessionsRef.orderByChild("timestamp")
                .endAt(cutoffTime)
                .limitToFirst(20)  // Small batch
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        int deletedCount = 0;

                        for (DataSnapshot sessionSnapshot : snapshot.getChildren()) {
                            sessionSnapshot.getRef().removeValue();
                            deletedCount++;
                        }

                        if (deletedCount > 0) {
                            Log.d(TAG, "✓ Cleaned up " + deletedCount + " old sessions");
                        }

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

    /**
     * Clean up incomplete/stale sessions from Firebase
     * ✅ OPTIMIZED: Smaller batch size
     */
    public void cleanupIncompleteSessions(OnCleanupCompleteListener listener) {
        sessionsRef.orderByChild("analyzed").equalTo(false)
                .limitToLast(MAX_CLEANUP_BATCH_SIZE)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        int deletedCount = 0;
                        long currentTime = System.currentTimeMillis();

                        for (DataSnapshot sessionSnapshot : snapshot.getChildren()) {
                            // ✅ Don't deserialize entire session - just check fields directly
                            Long timestamp = sessionSnapshot.child("timestamp").getValue(Long.class);
                            boolean hasUpperBack = sessionSnapshot.hasChild("upperBack");
                            boolean hasLowerBack = sessionSnapshot.hasChild("lowerBack");

                            boolean shouldDelete = false;

                            // Missing sensor data
                            if (!hasUpperBack || !hasLowerBack) {
                                shouldDelete = true;
                            }

                            // Stale session (older than 24 hours)
                            if (timestamp != null && currentTime - timestamp > STALE_SESSION_THRESHOLD_MS) {
                                shouldDelete = true;
                            }

                            if (shouldDelete) {
                                sessionSnapshot.getRef().removeValue();
                                deletedCount++;
                                Log.d(TAG, "Deleted incomplete session: " + sessionSnapshot.getKey());
                            }
                        }

                        if (deletedCount > 0) {
                            Log.d(TAG, "Cleanup: " + deletedCount + " incomplete sessions deleted");
                        }

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
        Log.d(TAG, "═══════════════════════════════════════════════════════════");
        Log.d(TAG, "Starting analysis for user: " + userId);

        calibrationRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    calibrationData = snapshot.getValue(CalibrationData.class);

                    if (calibrationData != null && calibrationData.isCalibrated) {
                        Log.d(TAG, "✓ Calibration loaded successfully");
                        processUnprocessedSessions(listener);
                    } else {
                        Log.w(TAG, "✗ Calibration not complete");
                        if (listener != null) {
                            listener.onAnalysisError("Please complete calibration first.");
                        }
                    }
                } else {
                    Log.w(TAG, "✗ No calibration data found");
                    if (listener != null) {
                        listener.onAnalysisError("Please complete calibration first.");
                    }
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Calibration load error: " + error.getMessage());
                if (listener != null) {
                    listener.onAnalysisError("Failed to load calibration: " + error.getMessage());
                }
            }
        });
    }

    /**
     * ✅ HEAVILY OPTIMIZED with comprehensive logging
     */
    private void processUnprocessedSessions(OnAnalysisCompleteListener listener) {
        Log.d(TAG, "Querying unanalyzed sessions (limit=" + MAX_SESSIONS_PER_ANALYSIS + ")...");

        sessionsRef.orderByChild("analyzed").equalTo(false)
                .limitToLast(MAX_SESSIONS_PER_ANALYSIS)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        Log.d(TAG, "───────────────────────────────────────────────────────────");
                        Log.d(TAG, "Query returned " + snapshot.getChildrenCount() + " unanalyzed sessions");

                        if (snapshot.getChildrenCount() == 0) {
                            Log.d(TAG, "No unanalyzed sessions found!");
                            Log.d(TAG, "Possible reasons:");
                            Log.d(TAG, "  1. All sessions already analyzed=true");
                            Log.d(TAG, "  2. Session not saved to Firebase yet");
                            Log.d(TAG, "  3. No sessions exist for this user");

                            if (listener != null) {
                                listener.onAnalysisComplete(0, 0);
                            }
                            return;
                        }

                        int analyzedCount = 0;
                        int slouchingSessions = 0;
                        int skippedCount = 0;

                        for (DataSnapshot sessionSnapshot : snapshot.getChildren()) {
                            String sessionId = sessionSnapshot.getKey();
                            Log.d(TAG, "───────────────────────────────────────────────────────────");
                            Log.d(TAG, "Processing: " + sessionId);

                            // ✅ Check if session has required data BEFORE deserializing
                            boolean hasUpperBack = sessionSnapshot.hasChild("upperBack");
                            boolean hasLowerBack = sessionSnapshot.hasChild("lowerBack");
                            Boolean analyzed = sessionSnapshot.child("analyzed").getValue(Boolean.class);

                            Log.d(TAG, "  analyzed=" + analyzed);
                            Log.d(TAG, "  hasUpperBack=" + hasUpperBack);
                            Log.d(TAG, "  hasLowerBack=" + hasLowerBack);

                            if (!hasUpperBack) {
                                Log.d(TAG, "  → SKIPPED: Missing upperBack data");
                                skippedCount++;
                                continue;
                            }

                            if (!hasLowerBack) {
                                Log.d(TAG, "  → SKIPPED: Missing lowerBack data");
                                skippedCount++;
                                continue;
                            }

                            // ✅ Now safe to deserialize
                            try {
                                PostureSession session = sessionSnapshot.getValue(PostureSession.class);

                                if (session == null) {
                                    Log.d(TAG, "  → SKIPPED: Deserialization returned null");
                                    skippedCount++;
                                    continue;
                                }

                                if (session.upperBack == null) {
                                    Log.d(TAG, "  → SKIPPED: session.upperBack is null");
                                    skippedCount++;
                                    continue;
                                }

                                if (session.lowerBack == null) {
                                    Log.d(TAG, "  → SKIPPED: session.lowerBack is null");
                                    skippedCount++;
                                    continue;
                                }

                                SensorAngles upperAngles = SensorAngles.fromSensorData(session.upperBack);
                                SensorAngles lowerAngles = SensorAngles.fromSensorData(session.lowerBack);

                                Log.d(TAG, "  upperBack pitch=" + upperAngles.pitch + ", roll=" + upperAngles.roll);
                                Log.d(TAG, "  lowerBack pitch=" + lowerAngles.pitch + ", roll=" + lowerAngles.roll);

                                AnalysisResult result = detectSlouchWithCalibration(
                                        session.upperBack,
                                        session.lowerBack
                                );

                                Log.d(TAG, "  → ANALYZED: slouching=" + result.isSlouchingDetected +
                                        ", score=" + result.overallSlouchScore);

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
                                Log.d(TAG, "  → SAVED to Firebase");

                                analyzedCount++;

                                String sessionDateKey = getDateKeyFromTimestamp(session.timestamp);
                                saveDailyStatsOptimized(sessionDateKey, result.isSlouchingDetected);

                            } catch (Exception e) {
                                Log.e(TAG, "  → ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                                skippedCount++;
                            }
                        }

                        Log.d(TAG, "═══════════════════════════════════════════════════════════");
                        Log.d(TAG, "ANALYSIS COMPLETE");
                        Log.d(TAG, "  Analyzed: " + analyzedCount);
                        Log.d(TAG, "  Slouching: " + slouchingSessions);
                        Log.d(TAG, "  Skipped: " + skippedCount);
                        Log.d(TAG, "═══════════════════════════════════════════════════════════");

                        // Notify completion
                        if (listener != null) {
                            listener.onAnalysisComplete(analyzedCount, slouchingSessions);
                        }
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        Log.e(TAG, "Query error: " + error.getMessage());
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

    /**
     * Analyze a specific session by ID - much faster than querying!
     */
    public void analyzeSpecificSession(String sessionId, OnAnalysisCompleteListener listener) {
        Log.d(TAG, "═══════════════════════════════════════════════════════════");
        Log.d(TAG, "Analyzing specific session: " + sessionId);

        // First load calibration
        calibrationRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!snapshot.exists()) {
                    Log.w(TAG, "No calibration data");
                    if (listener != null) {
                        listener.onAnalysisError("No calibration data");
                    }
                    return;
                }

                calibrationData = snapshot.getValue(CalibrationData.class);
                if (calibrationData == null || !calibrationData.isCalibrated) {
                    Log.w(TAG, "Calibration not complete");
                    if (listener != null) {
                        listener.onAnalysisError("Calibration not complete");
                    }
                    return;
                }

                Log.d(TAG, "✓ Calibration loaded");

                // Now fetch and analyze the specific session
                sessionsRef.child(sessionId).addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot sessionSnapshot) {
                        if (!sessionSnapshot.exists()) {
                            Log.w(TAG, "Session not found: " + sessionId);
                            if (listener != null) {
                                listener.onAnalysisComplete(0, 0);
                            }
                            return;
                        }

                        // Check if already analyzed
                        Boolean analyzed = sessionSnapshot.child("analyzed").getValue(Boolean.class);
                        if (analyzed != null && analyzed) {
                            Log.d(TAG, "Session already analyzed");
                            if (listener != null) {
                                listener.onAnalysisComplete(0, 0);
                            }
                            return;
                        }

                        try {
                            PostureSession session = sessionSnapshot.getValue(PostureSession.class);

                            if (session == null || session.upperBack == null || session.lowerBack == null) {
                                Log.d(TAG, "Session incomplete - missing sensor data");
                                if (listener != null) {
                                    listener.onAnalysisComplete(0, 0);
                                }
                                return;
                            }

                            // Analyze!
                            AnalysisResult result = detectSlouchWithCalibration(
                                    session.upperBack,
                                    session.lowerBack
                            );

                            Log.d(TAG, "  → slouching=" + result.isSlouchingDetected +
                                    ", score=" + result.overallSlouchScore);

                            // Save results
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

                            // Update daily stats
                            String sessionDateKey = getDateKeyFromTimestamp(session.timestamp);
                            saveDailyStatsOptimized(sessionDateKey, result.isSlouchingDetected);

                            Log.d(TAG, "✓ Session analyzed and saved");
                            Log.d(TAG, "═══════════════════════════════════════════════════════════");

                            if (listener != null) {
                                listener.onAnalysisComplete(1, result.isSlouchingDetected ? 1 : 0);
                            }

                        } catch (Exception e) {
                            Log.e(TAG, "Error analyzing session: " + e.getMessage());
                            if (listener != null) {
                                listener.onAnalysisError(e.getMessage());
                            }
                        }
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        Log.e(TAG, "Error fetching session: " + error.getMessage());
                        if (listener != null) {
                            listener.onAnalysisError(error.getMessage());
                        }
                    }
                });
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Error loading calibration: " + error.getMessage());
                if (listener != null) {
                    listener.onAnalysisError(error.getMessage());
                }
            }
        });
    }

}