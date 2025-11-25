package com.example.ee475project;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.ee475project.databinding.FragmentAnalyticsBinding;
import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.charts.PieChart;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.data.PieData;
import com.github.mikephil.charting.data.PieDataSet;
import com.github.mikephil.charting.data.PieEntry;
import com.github.mikephil.charting.utils.ColorTemplate;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class AnalyticsFragment extends Fragment {

    private static final String TAG = "AnalyticsFragment";
    private FragmentAnalyticsBinding binding;
    private PostureAnalyzer postureAnalyzer;

    // Store total connection time for calculations
    private float totalConnectionTimeHours = 0f;
    private int goodPostureSessions = 0;
    private int slouchingSessions = 0;
    private float weeklyAverageUprightMinutes = 0f; // Store weekly average
    private float bestDayUprightMinutes = 0f; // Store best day upright time

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentAnalyticsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Initialize PostureAnalyzer
        postureAnalyzer = new PostureAnalyzer();

        // Set up Analyze button (keep for manual refresh)
        binding.btnAnalyzePosture.setOnClickListener(v -> analyzePostureData());

        // AUTO-ANALYZE: Check for unanalyzed sessions when fragment is viewed
        autoAnalyzeIfNeeded();

        // Load existing analytics data
        loadAnalyticsData();
    }

    private void autoAnalyzeIfNeeded() {
        String userId = FirebaseAuth.getInstance().getCurrentUser().getUid();

        DatabaseReference sessionsRef = FirebaseDatabase.getInstance()
                .getReference("posture_sessions")
                .child(userId);

        // Check if there are any unanalyzed sessions
        sessionsRef.orderByChild("analyzed").equalTo(false)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        if (snapshot.getChildrenCount() > 0) {
                            Log.d(TAG, "Found " + snapshot.getChildrenCount() +
                                    " unanalyzed sessions. Auto-analyzing...");

                            // Silently analyze without showing toast
                            postureAnalyzer.analyzeUnprocessedSessions(new PostureAnalyzer.OnAnalysisCompleteListener() {
                                @Override
                                public void onAnalysisComplete(int sessionsAnalyzed, int slouchingSessions) {
                                    Log.d(TAG, "Auto-analysis complete: " + sessionsAnalyzed + " sessions");
                                    requireActivity().runOnUiThread(() -> {
                                        // Refresh analytics display
                                        loadAnalyticsData();
                                    });
                                }

                                @Override
                                public void onAnalysisError(String error) {
                                    Log.e(TAG, "Auto-analysis error: " + error);
                                }
                            });
                        }
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        Log.e(TAG, "Error checking for unanalyzed sessions: " + error.getMessage());
                    }
                });
    }

    private void analyzePostureData() {
        // Show loading state
        binding.btnAnalyzePosture.setEnabled(false);
        binding.btnAnalyzePosture.setText("Analyzing...");
        Toast.makeText(getContext(), "Analyzing posture data...", Toast.LENGTH_SHORT).show();

        postureAnalyzer.analyzeUnprocessedSessions(new PostureAnalyzer.OnAnalysisCompleteListener() {
            @Override
            public void onAnalysisComplete(int sessionsAnalyzed, int slouchingSessions) {
                requireActivity().runOnUiThread(() -> {
                    // Reset button
                    binding.btnAnalyzePosture.setEnabled(true);
                    binding.btnAnalyzePosture.setText("Analyze Posture Data");

                    // Show results
                    int goodPosture = sessionsAnalyzed - slouchingSessions;
                    String message = String.format(
                            "✓ Analysis Complete!\n\n" +
                                    "Sessions analyzed: %d\n" +
                                    "Good posture: %d\n" +
                                    "Slouching: %d",
                            sessionsAnalyzed, goodPosture, slouchingSessions
                    );

                    Toast.makeText(getContext(), message, Toast.LENGTH_LONG).show();

                    // Refresh analytics display
                    loadAnalyticsData();
                });
            }

            @Override
            public void onAnalysisError(String error) {
                requireActivity().runOnUiThread(() -> {
                    binding.btnAnalyzePosture.setEnabled(true);
                    binding.btnAnalyzePosture.setText("Analyze Posture Data");
                    Toast.makeText(getContext(), "Analysis error: " + error,
                            Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void loadAnalyticsData() {
        String userId = FirebaseAuth.getInstance().getCurrentUser().getUid();

        DatabaseReference sessionsRef = FirebaseDatabase.getInstance()
                .getReference("posture_sessions")
                .child(userId);

        // Query ALL analyzed sessions for overall percentage
        sessionsRef.orderByChild("analyzed").equalTo(true)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        int totalAnalyzedSessions = 0;
                        int goodPostureSessions = 0;
                        int slouchingSessions = 0;

                        Log.d(TAG, "Loading analytics from " + snapshot.getChildrenCount() + " analyzed sessions");

                        for (DataSnapshot sessionSnapshot : snapshot.getChildren()) {
                            PostureSession session = sessionSnapshot.getValue(PostureSession.class);

                            if (session != null && session.analyzed != null && session.analyzed) {
                                totalAnalyzedSessions++;

                                // Check if slouching
                                if (session.slouching != null && session.slouching) {
                                    slouchingSessions++;
                                } else {
                                    goodPostureSessions++;
                                }
                            }
                        }

                        // Update UI on main thread
                        int finalGoodPosture = goodPostureSessions;
                        int finalSlouchingSessions = slouchingSessions;
                        int finalTotal = totalAnalyzedSessions;

                        requireActivity().runOnUiThread(() -> {
                            // Update insight cards
                            updateInsightCards(finalTotal, finalSlouchingSessions, finalGoodPosture);

                            // Update pie chart with overall percentage
                            updatePieChart(finalGoodPosture, finalSlouchingSessions);

                            // Load TODAY'S time breakdown (not all-time)
                            loadTodayTimeBreakdown();

                            // Update bar chart with weekly data
                            loadWeeklyData();

                            Log.d(TAG, "Analytics loaded: " + finalTotal + " sessions total, " +
                                    finalGoodPosture + " good posture, " +
                                    finalSlouchingSessions + " slouching");
                        });
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        Log.e(TAG, "Error loading analytics: " + error.getMessage());
                        requireActivity().runOnUiThread(() -> {
                            Toast.makeText(getContext(),
                                    "Failed to load analytics: " + error.getMessage(),
                                    Toast.LENGTH_SHORT).show();
                        });
                    }
                });

        // Load total connection time for the Total card
        loadTotalConnectionTime();
    }

    private void loadTodayTimeBreakdown() {
        String userId = FirebaseAuth.getInstance().getCurrentUser().getUid();
        String todayKey = getTodayDateKey();

        // Load today's active time
        DatabaseReference userRef = FirebaseDatabase.getInstance()
                .getReference("users")
                .child(userId);

        userRef.child("active_time").addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                float todayActiveMinutes = 0f;

                if (snapshot.exists()) {
                    Float activeTime = snapshot.getValue(Float.class);
                    todayActiveMinutes = (activeTime != null) ? activeTime : 0f;
                }

                // Now get today's slouch percentage
                float finalActiveTime = todayActiveMinutes;
                loadTodaySlouchPercentageForCards(finalActiveTime);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Error loading today's active time: " + error.getMessage());
            }
        });
    }

    /**
     * Load today's slouch percentage and calculate time breakdown
     */
    private void loadTodaySlouchPercentageForCards(float todayActiveMinutes) {
        String userId = FirebaseAuth.getInstance().getCurrentUser().getUid();
        String todayKey = getTodayDateKey();

        DatabaseReference statsRef = FirebaseDatabase.getInstance()
                .getReference("daily_stats")
                .child(userId)
                .child(todayKey);

        statsRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                double slouchPercentage = 0.0;

                if (snapshot.exists()) {
                    Double slouch = snapshot.child("slouch_percentage").getValue(Double.class);
                    slouchPercentage = (slouch != null) ? slouch : 0.0;
                }

                // Calculate time breakdown
                float slouchMinutes = todayActiveMinutes * (float)(slouchPercentage / 100.0);
                float uprightMinutes = todayActiveMinutes - slouchMinutes;

                // Convert to hours
                float uprightHours = uprightMinutes / 60.0f;
                float slouchHours = slouchMinutes / 60.0f;

                requireActivity().runOnUiThread(() -> {
                    updatePostureInfoCards(uprightHours, slouchHours);
                });

                Log.d(TAG, "Today's time breakdown: Active=" + todayActiveMinutes +
                        "min, Upright=" + uprightMinutes + "min, Slouch=" + slouchMinutes + "min");
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Error loading slouch percentage: " + error.getMessage());
            }
        });
    }



    private void updateInsightCards(int total, int slouching, int goodPosture) {
        // Update the "Total" card
        binding.tvTotalValue.setText(String.valueOf(total));

        // You can add more card updates here as needed
        // For example, calculate daily average, best day, etc.
        loadTotalConnectionTime();
    }

    private void loadTotalConnectionTime() {
        String userId = FirebaseAuth.getInstance().getCurrentUser().getUid();
        DatabaseReference userRef = FirebaseDatabase.getInstance()
                .getReference("users")
                .child(userId);

        userRef.child("total_connection_time").addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    Float totalHours = snapshot.getValue(Float.class);
                    if (totalHours != null) {
                        totalConnectionTimeHours = totalHours;

                        requireActivity().runOnUiThread(() -> {
                            // Update Total card with hours
                            binding.tvTotalValue.setText(String.format("%.2fh", totalHours));

                            // REMOVED: updatePostureInfoCards()
                            // The posture info cards are now updated by loadTodayTimeBreakdown()
                        });
                    }
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Error loading total time: " + error.getMessage());
            }
        });
    }

    private void updatePieChart(int goodPosture, int slouching) {
        PieChart pieChart = binding.pieChart;

        // Check if we have any data
        if (goodPosture == 0 && slouching == 0) {
            // Show empty state
            pieChart.clear();
            pieChart.setNoDataText("No analyzed sessions yet");
            pieChart.setNoDataTextColor(getResources().getColor(R.color.ios_text_secondary, null));
            binding.tvPieCenterPercentage.setText("0%");
            return;
        }

        List<PieEntry> entries = new ArrayList<>();
        entries.add(new PieEntry(goodPosture, "Upright"));
        entries.add(new PieEntry(slouching, "Slouch"));

        PieDataSet dataSet = new PieDataSet(entries, "Posture Distribution");

        // Colors: Blue for upright, Orange for slouch
        dataSet.setColors(
                getResources().getColor(R.color.ios_blue, null),
                getResources().getColor(R.color.ios_orange, null)
        );

        dataSet.setValueTextSize(14f);
        dataSet.setValueTextColor(getResources().getColor(R.color.white, null));

        PieData data = new PieData(dataSet);
        pieChart.setData(data);

        // Configure pie chart appearance
        pieChart.setDrawHoleEnabled(true);
        pieChart.setHoleRadius(70f);
        pieChart.setTransparentCircleRadius(75f);
        pieChart.setDrawEntryLabels(false);
        pieChart.getDescription().setEnabled(false);
        pieChart.getLegend().setEnabled(false);

        // Update center text
        int total = goodPosture + slouching;
        int percentage = total > 0 ? (goodPosture * 100 / total) : 0;
        binding.tvPieCenterPercentage.setText(percentage + "%");

        pieChart.invalidate(); // Refresh chart

        Log.d(TAG, "Pie chart updated: " + goodPosture + " upright, " + slouching + " slouch");
    }

    private void loadWeeklyData() {
        String userId = FirebaseAuth.getInstance().getCurrentUser().getUid();

        DatabaseReference statsRef = FirebaseDatabase.getInstance()
                .getReference("daily_stats")
                .child(userId);

        // Get last 7 days
        Calendar cal = Calendar.getInstance();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

        // Store data for each day
        String[] last7Days = new String[7];
        for (int i = 6; i >= 0; i--) {
            last7Days[6 - i] = sdf.format(cal.getTime());
            cal.add(Calendar.DAY_OF_YEAR, -1);
        }

        // Query each day's stats
        statsRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                float[] uprightMinutes = new float[7];
                float[] slouchMinutes = new float[7];

                // Initialize with zeros
                for (int i = 0; i < 7; i++) {
                    uprightMinutes[i] = 0;
                    slouchMinutes[i] = 0;
                }

                // Fill in actual data from stored time values
                for (int i = 0; i < 7; i++) {
                    String dateKey = last7Days[i];
                    DataSnapshot daySnapshot = snapshot.child(dateKey);

                    if (daySnapshot.exists()) {
                        // NEW: Use actual stored time values instead of session estimates
                        Float upright = daySnapshot.child("upright_minutes").getValue(Float.class);
                        Float slouch = daySnapshot.child("slouch_minutes").getValue(Float.class);

                        if (upright != null) {
                            uprightMinutes[i] = upright;
                        }
                        if (slouch != null) {
                            slouchMinutes[i] = slouch;
                        }
                    }
                }

                // Calculate weekly average upright time
                float totalUprightMinutes = 0;
                float maxUprightMinutes = 0;

                for (float minutes : uprightMinutes) {
                    totalUprightMinutes += minutes;
                    // Find the maximum (best day)
                    if (minutes > maxUprightMinutes) {
                        maxUprightMinutes = minutes;
                    }
                }

                weeklyAverageUprightMinutes = totalUprightMinutes / 7.0f;
                bestDayUprightMinutes = maxUprightMinutes;

                requireActivity().runOnUiThread(() -> {
                    updateBarChart(uprightMinutes, slouchMinutes);
                    updateDailyAvgCard();
                    updateBestDayCard();
                });

                Log.d(TAG, "Weekly data loaded with ACTUAL time values (not estimates)");
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Error loading weekly data: " + error.getMessage());
            }
        });
    }

    private void updateBarChart(float[] uprightMinutes, float[] slouchMinutes) {
        BarChart barChart = binding.barChart;

        // Create entries for upright (blue) and slouch (orange)
        ArrayList<BarEntry> uprightEntries = new ArrayList<>();
        ArrayList<BarEntry> slouchEntries = new ArrayList<>();

        for (int i = 0; i < 7; i++) {
            uprightEntries.add(new BarEntry(i, uprightMinutes[i]));
            slouchEntries.add(new BarEntry(i, slouchMinutes[i]));
        }

        // Create datasets
        BarDataSet uprightDataSet = new BarDataSet(uprightEntries, "Upright");
        uprightDataSet.setColor(getResources().getColor(R.color.ios_blue, null));
        uprightDataSet.setDrawValues(false); // Remove text labels above bars

        BarDataSet slouchDataSet = new BarDataSet(slouchEntries, "Slouch");
        slouchDataSet.setColor(getResources().getColor(R.color.ios_orange, null));
        slouchDataSet.setDrawValues(false); // Remove text labels above bars

        // Combine datasets
        BarData barData = new BarData(uprightDataSet, slouchDataSet);

        // Group bars
        float groupSpace = 0.3f;
        float barSpace = 0.05f;
        float barWidth = 0.3f;

        barData.setBarWidth(barWidth);
        barChart.setData(barData);

        // Configure chart
        barChart.getDescription().setEnabled(false);
        barChart.setDrawGridBackground(false);
        barChart.setDrawBarShadow(false);
        barChart.setHighlightFullBarEnabled(false);
        barChart.setDrawValueAboveBar(false); // Ensure no values above bars
        barChart.setTouchEnabled(false); // Disable touch interactions for cleaner look

        // X-axis configuration
        barChart.getXAxis().setPosition(com.github.mikephil.charting.components.XAxis.XAxisPosition.BOTTOM);
        barChart.getXAxis().setGranularity(1f);
        barChart.getXAxis().setGranularityEnabled(true);
        barChart.getXAxis().setCenterAxisLabels(true);
        barChart.getXAxis().setDrawGridLines(false);
        barChart.getXAxis().setAxisMinimum(0f);
        barChart.getXAxis().setAxisMaximum(7f);
        barChart.getXAxis().setTextColor(getResources().getColor(R.color.ios_text_primary, null));
        barChart.getXAxis().setTextSize(11f);

        // Set day labels (Mon, Tue, Wed, etc.)
        String[] dayLabels = getDayLabels();
        barChart.getXAxis().setValueFormatter(new com.github.mikephil.charting.formatter.IndexAxisValueFormatter(dayLabels));

        // Y-axis configuration (showing minutes)
        barChart.getAxisLeft().setAxisMinimum(0f);
        barChart.getAxisLeft().setDrawGridLines(true);
        barChart.getAxisLeft().setGridColor(getResources().getColor(R.color.ios_text_secondary, null));
        barChart.getAxisLeft().setGridLineWidth(0.5f);
        barChart.getAxisLeft().setTextColor(getResources().getColor(R.color.ios_text_primary, null));
        barChart.getAxisLeft().setTextSize(10f);
        barChart.getAxisLeft().setValueFormatter(new com.github.mikephil.charting.formatter.ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return String.format(Locale.US, "%.0f min", value);
            }
        });
        barChart.getAxisRight().setEnabled(false);

        // Legend
        barChart.getLegend().setEnabled(true);
        barChart.getLegend().setTextColor(getResources().getColor(R.color.ios_text_primary, null));
        barChart.getLegend().setTextSize(12f);
        barChart.getLegend().setVerticalAlignment(com.github.mikephil.charting.components.Legend.LegendVerticalAlignment.TOP);
        barChart.getLegend().setHorizontalAlignment(com.github.mikephil.charting.components.Legend.LegendHorizontalAlignment.RIGHT);

        // Group bars together
        barChart.groupBars(0f, groupSpace, barSpace);

        // Animation
        barChart.animateY(1000);

        barChart.invalidate();

        Log.d(TAG, "Bar chart updated with weekly time data (minutes)");
    }

    private String[] getDayLabels() {
        // Get last 7 days in order (oldest to newest)
        SimpleDateFormat dayFormat = new SimpleDateFormat("EEE", Locale.US); // Short day name
        Calendar cal = Calendar.getInstance();
        String[] labels = new String[7];

        // Go back 6 days to start
        cal.add(Calendar.DAY_OF_YEAR, -6);

        for (int i = 0; i < 7; i++) {
            labels[i] = dayFormat.format(cal.getTime());
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }

        return labels;
    }

    private void updatePostureInfoCards(float uprightHours, float slouchHours) {
        // Update upright sessions count
        binding.tvUprightValue.setText(String.format(Locale.US, "%.2fh", uprightHours));

        // Update slouching sessions count
        binding.tvSlouchValue.setText(String.format(Locale.US, "%.2fh", slouchHours));

        Log.d(TAG, "Posture info cards updated - Upright: " + uprightHours +
                "h, Slouch: " + slouchHours + "h");
    }

    private String getTodayDateKey() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        return sdf.format(new Date());
    }

    private void updateBestDayCard() {
        // The card shows the best day's upright time in minutes with 1 decimal place
        binding.tvBestDayValue.setText(String.format(Locale.US, "%.1f min", bestDayUprightMinutes));

        Log.d(TAG, "Best day upright time: " + bestDayUprightMinutes + " min");
    }

    private void updateDailyAvgCard() {
        // The card shows weekly average in minutes with 1 decimal place
        binding.tvDailyAvgValue.setText(String.format(Locale.US, "%.1f min", weeklyAverageUprightMinutes));

        Log.d(TAG, "Daily (weekly) average upright time: " + weeklyAverageUprightMinutes + " min");
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}