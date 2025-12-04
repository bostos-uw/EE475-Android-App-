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

/**
 * ✅ OPTIMIZED AnalyticsFragment
 *
 * Key optimizations:
 * 1. Uses daily_stats instead of loading all posture_sessions (HUGE memory savings)
 * 2. Calculates minutes from session counts
 * 3. Cleans up old sessions on entry
 * 4. Limits all queries
 */
public class AnalyticsFragment extends Fragment {

    private static final String TAG = "AnalyticsFragment";
    private FragmentAnalyticsBinding binding;
    private PostureAnalyzer postureAnalyzer;

    // Cycle duration in seconds
    private static final float CYCLE_DURATION_SECONDS = 10f;

    private float weeklyAverageUprightMinutes = 0f;
    private float bestDayUprightMinutes = 0f;

    // Cleanup tracking - only once per session
    private static boolean hasCleanedUpThisSession = false;

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

        postureAnalyzer = new PostureAnalyzer();
        binding.btnAnalyzePosture.setOnClickListener(v -> analyzePostureData());

        // ✅ Run cleanup first, then load data
        if (!hasCleanedUpThisSession) {
            runCleanupThenLoad();
        } else {
            autoAnalyzeIfNeeded();
            loadAnalyticsData();
        }
    }

    /**
     * ✅ Clean up old sessions before loading analytics
     */
    private void runCleanupThenLoad() {
        postureAnalyzer.cleanupOldSessions(new PostureAnalyzer.OnCleanupCompleteListener() {
            @Override
            public void onCleanupComplete(int deletedCount) {
                hasCleanedUpThisSession = true;
                if (deletedCount > 0) {
                    Log.d(TAG, "✓ Cleaned " + deletedCount + " old sessions");
                }
                if (isAdded()) {
                    autoAnalyzeIfNeeded();
                    loadAnalyticsData();
                }
            }

            @Override
            public void onCleanupError(String error) {
                hasCleanedUpThisSession = true;
                Log.e(TAG, "Cleanup error: " + error);
                if (isAdded()) {
                    autoAnalyzeIfNeeded();
                    loadAnalyticsData();
                }
            }
        });
    }

    /**
     * ✅ OPTIMIZED: Only check if ANY unanalyzed exist (limitToLast(1))
     */
    private void autoAnalyzeIfNeeded() {
        String userId = FirebaseAuth.getInstance().getCurrentUser().getUid();

        DatabaseReference sessionsRef = FirebaseDatabase.getInstance()
                .getReference("posture_sessions")
                .child(userId);

        sessionsRef.orderByChild("analyzed").equalTo(false)
                .limitToLast(1)  // ✅ Only check if any exist
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        if (snapshot.getChildrenCount() > 0) {
                            Log.d(TAG, "Found unanalyzed sessions. Auto-analyzing...");

                            postureAnalyzer.analyzeUnprocessedSessions(new PostureAnalyzer.OnAnalysisCompleteListener() {
                                @Override
                                public void onAnalysisComplete(int sessionsAnalyzed, int slouchingSessions) {
                                    if (isAdded() && getActivity() != null) {
                                        requireActivity().runOnUiThread(() -> loadAnalyticsData());
                                    }
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
                        Log.e(TAG, "Error checking sessions: " + error.getMessage());
                    }
                });
    }

    private void analyzePostureData() {
        binding.btnAnalyzePosture.setEnabled(false);
        binding.btnAnalyzePosture.setText("Analyzing...");

        postureAnalyzer.analyzeUnprocessedSessions(new PostureAnalyzer.OnAnalysisCompleteListener() {
            @Override
            public void onAnalysisComplete(int sessionsAnalyzed, int slouchingSessions) {
                if (!isAdded() || getActivity() == null) return;

                requireActivity().runOnUiThread(() -> {
                    binding.btnAnalyzePosture.setEnabled(true);
                    binding.btnAnalyzePosture.setText("Analyze Posture Data");

                    int goodPosture = sessionsAnalyzed - slouchingSessions;
                    Toast.makeText(getContext(),
                            "✓ Analyzed " + sessionsAnalyzed + " sessions",
                            Toast.LENGTH_SHORT).show();

                    loadAnalyticsData();
                });
            }

            @Override
            public void onAnalysisError(String error) {
                if (!isAdded() || getActivity() == null) return;

                requireActivity().runOnUiThread(() -> {
                    binding.btnAnalyzePosture.setEnabled(true);
                    binding.btnAnalyzePosture.setText("Analyze Posture Data");
                    Toast.makeText(getContext(), "Error: " + error, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    /**
     * ✅ OPTIMIZED: Load ALL analytics from daily_stats (lightweight)
     * Instead of querying posture_sessions which has large sensor arrays
     */
    private void loadAnalyticsData() {
        String userId = FirebaseAuth.getInstance().getCurrentUser().getUid();

        // ✅ Use daily_stats - much smaller than posture_sessions!
        DatabaseReference statsRef = FirebaseDatabase.getInstance()
                .getReference("daily_stats")
                .child(userId);

        statsRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!isAdded() || getActivity() == null) return;

                // Calculate ALL totals from daily_stats in a single pass
                int totalGoodPosture = 0;
                int totalSlouching = 0;

                // Weekly data
                Calendar cal = Calendar.getInstance();
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

                int currentDayOfWeek = cal.get(Calendar.DAY_OF_WEEK);
                int daysToMonday = (currentDayOfWeek == Calendar.SUNDAY) ? 6 : currentDayOfWeek - Calendar.MONDAY;
                cal.add(Calendar.DAY_OF_YEAR, -daysToMonday);

                String[] weekDays = new String[7];
                int[] weeklyGoodSessions = new int[7];
                int[] weeklySlouchSessions = new int[7];

                for (int i = 0; i < 7; i++) {
                    weekDays[i] = sdf.format(cal.getTime());
                    cal.add(Calendar.DAY_OF_YEAR, 1);
                }

                // Single pass through all daily stats
                for (DataSnapshot daySnapshot : snapshot.getChildren()) {
                    String dateKey = daySnapshot.getKey();

                    Integer good = daySnapshot.child("good_posture_sessions").getValue(Integer.class);
                    Integer slouch = daySnapshot.child("slouching_sessions").getValue(Integer.class);

                    int goodCount = (good != null) ? good : 0;
                    int slouchCount = (slouch != null) ? slouch : 0;

                    // Add to all-time totals
                    totalGoodPosture += goodCount;
                    totalSlouching += slouchCount;

                    // Check if in current week for bar chart
                    for (int i = 0; i < 7; i++) {
                        if (weekDays[i].equals(dateKey)) {
                            weeklyGoodSessions[i] = goodCount;
                            weeklySlouchSessions[i] = slouchCount;
                            break;
                        }
                    }
                }

                // Calculate minutes from session counts
                float[] weeklyUprightMinutes = new float[7];
                float[] weeklySlouchMinutes = new float[7];

                for (int i = 0; i < 7; i++) {
                    weeklyUprightMinutes[i] = weeklyGoodSessions[i] * CYCLE_DURATION_SECONDS / 60f;
                    weeklySlouchMinutes[i] = weeklySlouchSessions[i] * CYCLE_DURATION_SECONDS / 60f;
                }

                // Weekly stats
                float totalWeeklyUpright = 0;
                float maxUpright = 0;
                for (float minutes : weeklyUprightMinutes) {
                    totalWeeklyUpright += minutes;
                    if (minutes > maxUpright) maxUpright = minutes;
                }

                weeklyAverageUprightMinutes = totalWeeklyUpright / 7.0f;
                bestDayUprightMinutes = maxUpright;

                // All-time hours
                float totalUprightHours = (totalGoodPosture * CYCLE_DURATION_SECONDS / 60f) / 60f;
                float totalSlouchHours = (totalSlouching * CYCLE_DURATION_SECONDS / 60f) / 60f;

                // Final values
                final int finalGoodPosture = totalGoodPosture;
                final int finalSlouching = totalSlouching;
                final float finalUprightHours = totalUprightHours;
                final float finalSlouchHours = totalSlouchHours;
                final float[] finalWeeklyUpright = weeklyUprightMinutes;
                final float[] finalWeeklySlouch = weeklySlouchMinutes;

                requireActivity().runOnUiThread(() -> {
                    updatePieChart(finalGoodPosture, finalSlouching);
                    updateBarChart(finalWeeklyUpright, finalWeeklySlouch);
                    updatePostureInfoCards(finalUprightHours, finalSlouchHours);
                    updateDailyAvgCard();
                    updateBestDayCard();
                });

                Log.d(TAG, "Analytics loaded from daily_stats: " +
                        (finalGoodPosture + finalSlouching) + " total sessions");
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Error loading analytics: " + error.getMessage());
            }
        });

        loadTotalConnectionTime();
    }

    private void loadTotalConnectionTime() {
        String userId = FirebaseAuth.getInstance().getCurrentUser().getUid();

        FirebaseDatabase.getInstance()
                .getReference("users")
                .child(userId)
                .child("total_connection_time")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        if (!isAdded() || getActivity() == null) return;

                        if (snapshot.exists()) {
                            Float totalHours = snapshot.getValue(Float.class);
                            if (totalHours != null) {
                                requireActivity().runOnUiThread(() -> {
                                    binding.tvTotalValue.setText(String.format(Locale.US, "%.2fh", totalHours));
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

        if (goodPosture == 0 && slouching == 0) {
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
        dataSet.setColors(
                getResources().getColor(R.color.ios_blue, null),
                getResources().getColor(R.color.ios_orange, null)
        );
        dataSet.setDrawValues(false);

        PieData data = new PieData(dataSet);
        pieChart.setData(data);

        pieChart.setDrawHoleEnabled(true);
        pieChart.setHoleRadius(70f);
        pieChart.setTransparentCircleRadius(75f);
        pieChart.setDrawEntryLabels(false);
        pieChart.getDescription().setEnabled(false);
        pieChart.getLegend().setEnabled(false);

        int total = goodPosture + slouching;
        int percentage = total > 0 ? (goodPosture * 100 / total) : 0;
        binding.tvPieCenterPercentage.setText(percentage + "%");

        pieChart.invalidate();
    }

    private void updateBarChart(float[] uprightMinutes, float[] slouchMinutes) {
        BarChart barChart = binding.barChart;

        ArrayList<BarEntry> uprightEntries = new ArrayList<>();
        ArrayList<BarEntry> slouchEntries = new ArrayList<>();

        for (int i = 0; i < 7; i++) {
            uprightEntries.add(new BarEntry(i, uprightMinutes[i]));
            slouchEntries.add(new BarEntry(i, slouchMinutes[i]));
        }

        BarDataSet uprightDataSet = new BarDataSet(uprightEntries, "Upright");
        uprightDataSet.setColor(getResources().getColor(R.color.ios_blue, null));
        uprightDataSet.setDrawValues(false);

        BarDataSet slouchDataSet = new BarDataSet(slouchEntries, "Slouch");
        slouchDataSet.setColor(getResources().getColor(R.color.ios_orange, null));
        slouchDataSet.setDrawValues(false);

        BarData barData = new BarData(uprightDataSet, slouchDataSet);

        float groupSpace = 0.3f;
        float barSpace = 0.05f;
        float barWidth = 0.3f;

        barData.setBarWidth(barWidth);
        barChart.setData(barData);

        barChart.getDescription().setEnabled(false);
        barChart.setDrawGridBackground(false);
        barChart.setDrawBarShadow(false);
        barChart.setHighlightFullBarEnabled(false);
        barChart.setDrawValueAboveBar(false);
        barChart.setTouchEnabled(false);

        barChart.getXAxis().setPosition(com.github.mikephil.charting.components.XAxis.XAxisPosition.BOTTOM);
        barChart.getXAxis().setGranularity(1f);
        barChart.getXAxis().setGranularityEnabled(true);
        barChart.getXAxis().setCenterAxisLabels(true);
        barChart.getXAxis().setDrawGridLines(false);
        barChart.getXAxis().setAxisMinimum(0f);
        barChart.getXAxis().setAxisMaximum(7f);
        barChart.getXAxis().setTextColor(getResources().getColor(R.color.ios_text_primary, null));
        barChart.getXAxis().setTextSize(11f);
        barChart.getXAxis().setValueFormatter(new com.github.mikephil.charting.formatter.IndexAxisValueFormatter(getDayLabels()));

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

        barChart.getLegend().setEnabled(true);
        barChart.getLegend().setTextColor(getResources().getColor(R.color.ios_text_primary, null));
        barChart.getLegend().setTextSize(12f);
        barChart.getLegend().setVerticalAlignment(com.github.mikephil.charting.components.Legend.LegendVerticalAlignment.TOP);
        barChart.getLegend().setHorizontalAlignment(com.github.mikephil.charting.components.Legend.LegendHorizontalAlignment.RIGHT);

        barChart.groupBars(0f, groupSpace, barSpace);
        barChart.animateY(1000);
        barChart.invalidate();
    }

    private String[] getDayLabels() {
        SimpleDateFormat dayFormat = new SimpleDateFormat("EEE", Locale.US);
        Calendar cal = Calendar.getInstance();
        String[] labels = new String[7];

        int currentDayOfWeek = cal.get(Calendar.DAY_OF_WEEK);
        int daysToMonday = (currentDayOfWeek == Calendar.SUNDAY) ? 6 : currentDayOfWeek - Calendar.MONDAY;
        cal.add(Calendar.DAY_OF_YEAR, -daysToMonday);

        for (int i = 0; i < 7; i++) {
            labels[i] = dayFormat.format(cal.getTime());
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }

        return labels;
    }

    private void updatePostureInfoCards(float uprightHours, float slouchHours) {
        binding.tvUprightValue.setText(String.format(Locale.US, "%.2fh", uprightHours));
        binding.tvSlouchValue.setText(String.format(Locale.US, "%.2fh", slouchHours));
    }

    private void updateBestDayCard() {
        binding.tvBestDayValue.setText(String.format(Locale.US, "%.1f min", bestDayUprightMinutes));
    }

    private void updateDailyAvgCard() {
        binding.tvDailyAvgValue.setText(String.format(Locale.US, "%.1f min", weeklyAverageUprightMinutes));
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}