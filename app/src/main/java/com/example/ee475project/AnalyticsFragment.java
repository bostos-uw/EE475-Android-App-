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

        // Set up Analyze button
        binding.btnAnalyzePosture.setOnClickListener(v -> analyzePostureData());

        // Load existing analytics data
        loadAnalyticsData();
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
        String todayKey = getTodayDateKey();

        DatabaseReference statsRef = FirebaseDatabase.getInstance()
                .getReference("daily_stats")
                .child(userId)
                .child(todayKey);

        statsRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    Integer totalSessions = snapshot.child("total_sessions").getValue(Integer.class);
                    Integer slouchingSessions = snapshot.child("slouching_sessions").getValue(Integer.class);
                    Integer goodPostureSessions = snapshot.child("good_posture_sessions").getValue(Integer.class);
                    Double slouchPercentage = snapshot.child("slouch_percentage").getValue(Double.class);

                    if (totalSessions == null) totalSessions = 0;
                    if (slouchingSessions == null) slouchingSessions = 0;
                    if (goodPostureSessions == null) goodPostureSessions = 0;
                    if (slouchPercentage == null) slouchPercentage = 0.0;

                    // Update insight cards
                    updateInsightCards(totalSessions, slouchingSessions, goodPostureSessions);

                    // Update charts
                    updatePieChart(goodPostureSessions, slouchingSessions);
                    updateBarChart();

                    Log.d(TAG, "Analytics loaded: " + totalSessions + " sessions, " +
                            slouchPercentage + "% slouching");
                } else {
                    Log.d(TAG, "No analytics data for today yet");
                    // Show empty state
                    updateInsightCards(0, 0, 0);
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Error loading analytics: " + error.getMessage());
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
                        requireActivity().runOnUiThread(() -> {
                            // Update Total card with hours
                            binding.tvTotalValue.setText(String.format("%.2fh", totalHours));
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
    }

    private void updateBarChart() {
        BarChart barChart = binding.barChart;

        // Sample data for weekly overview
        List<BarEntry> entries = new ArrayList<>();
        entries.add(new BarEntry(0, 5));  // Mon
        entries.add(new BarEntry(1, 8));  // Tue
        entries.add(new BarEntry(2, 6));  // Wed
        entries.add(new BarEntry(3, 10)); // Thu
        entries.add(new BarEntry(4, 7));  // Fri
        entries.add(new BarEntry(5, 4));  // Sat
        entries.add(new BarEntry(6, 3));  // Sun

        BarDataSet dataSet = new BarDataSet(entries, "Sessions");
        dataSet.setColor(getResources().getColor(R.color.ios_blue, null));

        BarData data = new BarData(dataSet);
        barChart.setData(data);
        barChart.getDescription().setEnabled(false);
        barChart.invalidate(); // Refresh chart
    }

    private String getTodayDateKey() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        return sdf.format(new Date());
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}