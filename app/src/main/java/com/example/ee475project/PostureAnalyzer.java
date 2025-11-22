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
    private DatabaseReference sessionsRef;
    private DatabaseReference statsRef;
    private String userId;

    public PostureAnalyzer() {
        userId = FirebaseAuth.getInstance().getCurrentUser().getUid();
        sessionsRef = FirebaseDatabase.getInstance()
                .getReference("posture_sessions")
                .child(userId);
        statsRef = FirebaseDatabase.getInstance()
                .getReference("daily_stats")
                .child(userId);
    }

    /**
     * Main analysis function - processes all unanalyzed sessions
     */
    public void analyzeUnprocessedSessions(OnAnalysisCompleteListener listener) {
        Log.d(TAG, "Starting analysis of unprocessed sessions...");

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

                                    // ===== CALL THE ALGORITHM HERE =====
                                    boolean isSlouching = detectSlouchAdvanced(
                                            session.upperBack,
                                            session.lowerBack
                                    );

                                    if (isSlouching) {
                                        slouchingSessions++;
                                    }

                                    // Update this session in Firebase
                                    Map<String, Object> updates = new HashMap<>();
                                    updates.put("analyzed", true);
                                    updates.put("slouching", isSlouching);

                                    sessionSnapshot.getRef().updateChildren(updates)
                                            .addOnSuccessListener(aVoid -> {
                                                Log.d(TAG, "✓ Analyzed: " + session.sessionId +
                                                        " → Slouching: " + isSlouching);
                                            })
                                            .addOnFailureListener(e -> {
                                                Log.e(TAG, "✗ Failed to update: " + e.getMessage());
                                            });

                                    analyzedCount++;

                                } else {
                                    Log.w(TAG, "⚠ Session incomplete (missing sensor data): " +
                                            session.sessionId);
                                }
                            }
                        }

                        // Save daily statistics
                        if (analyzedCount > 0) {
                            saveDailyStats(analyzedCount, slouchingSessions);
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

    /**
     * PLACEHOLDER ALGORITHM - Replace this with your teammate's algorithm
     *
     * This is where the actual slouch detection logic goes.
     * For now, it uses simple thresholds as a mock.
     *
     * @param upperBack Upper back sensor data
     * @param lowerBack Lower back sensor data
     * @return true if slouching detected, false otherwise
     */
    private boolean detectSlouchAdvanced(SensorData upperBack, SensorData lowerBack) {
        // ===== TEMPORARY MOCK ALGORITHM =====
        // Replace this entire method when real algorithm is ready

        Log.d(TAG, "Running algorithm on paired data:");
        Log.d(TAG, "  Upper: accelZ=" + upperBack.accelZ + ", accelX=" + upperBack.accelX);
        Log.d(TAG, "  Lower: accelZ=" + lowerBack.accelZ + ", accelX=" + lowerBack.accelX);

        // Simple threshold-based detection (TEMPORARY)
        boolean upperLean = Math.abs(upperBack.accelZ) < 0.7f;  // Forward lean
        boolean lowerTilt = Math.abs(lowerBack.accelX) > 0.3f;  // Pelvic tilt

        boolean slouching = upperLean || lowerTilt;

        Log.d(TAG, "  Result: " + (slouching ? "SLOUCHING" : "GOOD POSTURE"));

        return slouching;

        // ===== YOUR TEAMMATE'S ALGORITHM WILL GO HERE =====
        // Example of what it might look like:
        // return AdvancedPostureClassifier.classify(upperBack, lowerBack);
    }

    /**
     * Save daily statistics to Firebase
     */
    private void saveDailyStats(int totalSessions, int slouchingSessions) {
        String todayKey = getTodayDateKey();

        Map<String, Object> stats = new HashMap<>();
        stats.put("date", todayKey);
        stats.put("total_sessions", totalSessions);
        stats.put("slouching_sessions", slouchingSessions);
        stats.put("good_posture_sessions", totalSessions - slouchingSessions);
        stats.put("slouch_percentage", (totalSessions > 0) ?
                (slouchingSessions * 100.0 / totalSessions) : 0);
        stats.put("last_updated", System.currentTimeMillis());

        statsRef.child(todayKey).updateChildren(stats)
                .addOnSuccessListener(aVoid ->
                        Log.d(TAG, "✓ Daily stats saved"))
                .addOnFailureListener(e ->
                        Log.e(TAG, "✗ Failed to save stats: " + e.getMessage()));
    }

    /**
     * Get today's date in format "yyyy-MM-dd"
     */
    private String getTodayDateKey() {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
        return sdf.format(new java.util.Date());
    }

    /**
     * Callback interface for analysis completion
     */
    public interface OnAnalysisCompleteListener {
        void onAnalysisComplete(int sessionsAnalyzed, int slouchingSessions);
        void onAnalysisError(String error);
    }
}