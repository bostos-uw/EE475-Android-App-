package com.example.ee475project;

import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.example.ee475project.databinding.FragmentAnalyticsBinding;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.data.PieData;
import com.github.mikephil.charting.data.PieDataSet;
import com.github.mikephil.charting.data.PieEntry;
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

public class AnalyticsFragment extends Fragment {

    private FragmentAnalyticsBinding binding;
    private BluetoothViewModel bluetoothViewModel;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        binding = FragmentAnalyticsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        bluetoothViewModel = new ViewModelProvider(requireActivity()).get(BluetoothViewModel.class);
        bluetoothViewModel.getTotalConnectionTime().observe(getViewLifecycleOwner(), totalTime -> {
            if (totalTime != null) {
                binding.tvTotalValue.setText(String.format(Locale.US, "%.2f h", totalTime));
            }
        });

        setupBarChart();
        setupPieChart();
    }

    private void setupBarChart() {
        List<BarEntry> uprightEntries = new ArrayList<>();
        List<BarEntry> slouchEntries = new ArrayList<>();
        Random random = new Random();

        for (int i = 0; i < 7; i++) {
            uprightEntries.add(new BarEntry(i, random.nextFloat() * 4f));
            slouchEntries.add(new BarEntry(i, random.nextFloat() * 2f));
        }

        BarDataSet uprightDataSet = new BarDataSet(uprightEntries, "Upright");
        uprightDataSet.setColor(ContextCompat.getColor(requireContext(), R.color.chart_blue));

        BarDataSet slouchDataSet = new BarDataSet(slouchEntries, "Slouch");
        slouchDataSet.setColor(ContextCompat.getColor(requireContext(), R.color.chart_orange));

        float groupSpace = 0.3f;
        float barSpace = 0.05f;
        float barWidth = 0.3f;

        BarData barData = new BarData(uprightDataSet, slouchDataSet);
        barData.setBarWidth(barWidth);

        binding.barChart.setData(barData);
        binding.barChart.groupBars(0f, groupSpace, barSpace);
        binding.barChart.getDescription().setEnabled(false);
        binding.barChart.getLegend().setEnabled(false);
        binding.barChart.setDrawGridBackground(false);
        binding.barChart.getXAxis().setDrawGridLines(false);
        binding.barChart.getAxisLeft().setDrawGridLines(false);
        binding.barChart.getAxisRight().setDrawGridLines(false);

        XAxis xAxis = binding.barChart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setGranularity(1f);
        xAxis.setValueFormatter(new IndexAxisValueFormatter(Arrays.asList("Oct 16", "Oct 17", "Oct 18", "Oct 19", "Oct 20", "Oct 21", "Oct 22")));

        binding.barChart.invalidate();
    }

    private void setupPieChart() {
        List<PieEntry> pieEntries = new ArrayList<>();
        Random random = new Random();
        float uprightPercentage = random.nextFloat() * 100;
        float slouchPercentage = 100f - uprightPercentage;

        pieEntries.add(new PieEntry(uprightPercentage, ""));
        pieEntries.add(new PieEntry(slouchPercentage, ""));

        PieDataSet pieDataSet = new PieDataSet(pieEntries, "");
        pieDataSet.setColors(ContextCompat.getColor(requireContext(), R.color.chart_blue), ContextCompat.getColor(requireContext(), R.color.chart_orange));
        pieDataSet.setDrawValues(false);

        PieData pieData = new PieData(pieDataSet);

        binding.pieChart.setData(pieData);
        binding.pieChart.getDescription().setEnabled(false);
        binding.pieChart.getLegend().setEnabled(false);
        binding.pieChart.setDrawHoleEnabled(true);
        binding.pieChart.setHoleColor(Color.TRANSPARENT);
        binding.pieChart.setTransparentCircleRadius(0f);
        binding.pieChart.setHoleRadius(80f);
        binding.tvPieCenterPercentage.setText(String.format("%.0f%%", uprightPercentage));

        binding.pieChart.invalidate();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}