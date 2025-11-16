package com.example.ee475project;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

public class HomeFragment extends Fragment {

    private TextView dailyGoalMinutes;
    private SharedPreferences sharedPreferences;
    private Button connectButton;
    private Button connectCycleButton;
    private TextView connectionStatusText;
    private BluetoothViewModel bluetoothViewModel;
    private SharedViewModel sharedViewModel;

    private final ActivityResultLauncher<String[]> requestPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(),
            permissions -> {
                boolean allPermissionsGranted = true;
                for (Boolean granted : permissions.values()) {
                    allPermissionsGranted &= granted;
                }
                if (allPermissionsGranted) {
                    enableBluetooth();
                } else {
                    // Handle permission denial
                }
            });

    private final ActivityResultLauncher<Intent> enableBluetoothLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    bluetoothViewModel.startScan();
                } else {
                    // Handle Bluetooth not enabled
                }
            });

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        dailyGoalMinutes = view.findViewById(R.id.daily_goal_minutes);
        connectButton = view.findViewById(R.id.connect_button);
        connectCycleButton = view.findViewById(R.id.connect_cycle_button);
        connectionStatusText = view.findViewById(R.id.connection_status_text);

        sharedPreferences = requireActivity().getSharedPreferences("user_goals", Context.MODE_PRIVATE);

        // Use SharedViewModel to observe goal changes
        sharedViewModel = new ViewModelProvider(requireActivity()).get(SharedViewModel.class);
        sharedViewModel.getDailyGoal().observe(getViewLifecycleOwner(), goal -> {
            if (goal != null) {
                updateDailyGoal(goal);
            }
        });

        // Load the initial goal from SharedPreferences
        updateDailyGoal(sharedPreferences.getInt("daily_goal", 60));

        bluetoothViewModel = new ViewModelProvider(requireActivity()).get(BluetoothViewModel.class);

        // Set initial button state
        updateButtonState(bluetoothViewModel.getConnectionStatus().getValue());

        bluetoothViewModel.getConnectionStatus().observe(getViewLifecycleOwner(), this::updateButtonState);

        connectCycleButton.setOnClickListener(v -> {
            bluetoothViewModel.startCycle();
        });
    }

    private void updateButtonState(String status) {
        connectionStatusText.setText(status);
        if (status == null) {
            status = "Disconnected";
        }

        if (status.startsWith("Scanning for") || status.equals("Scanning...")) {
            connectButton.setText("Cancel");
            connectButton.setOnClickListener(v -> bluetoothViewModel.cancelScan());
            connectCycleButton.setText("Stop Cycling");
            connectCycleButton.setOnClickListener(v -> bluetoothViewModel.disconnect());
        } else if (status.startsWith("Connecting to")) {
            connectButton.setText("Cancel");
            connectButton.setOnClickListener(v -> bluetoothViewModel.disconnect());
            connectCycleButton.setText("Stop Cycling");
            connectCycleButton.setOnClickListener(v -> bluetoothViewModel.disconnect());
        } else if (status.startsWith("Connected to")) {
            connectButton.setText("Disconnect");
            connectButton.setOnClickListener(v -> bluetoothViewModel.disconnect());
            connectCycleButton.setText("Stop Cycling");
            connectCycleButton.setOnClickListener(v -> bluetoothViewModel.disconnect());
        } else { // Primarily "Disconnected"
            connectButton.setText("Connect");
            connectButton.setOnClickListener(v -> requestBluetoothPermissions());
            connectCycleButton.setText("Connect (Cycle)");
            connectCycleButton.setOnClickListener(v -> {
                bluetoothViewModel.startCycle();
            });
        }
    }

    private void updateDailyGoal(int dailyGoal) {
        dailyGoalMinutes.setText("of " + dailyGoal + " minutes");
    }

    private void requestBluetoothPermissions() {
        String[] permissions;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions = new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
            };
        } else {
            permissions = new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION
            };
        }

        if (!hasPermissions(permissions)) {
            requestPermissionLauncher.launch(permissions);
        } else {
            enableBluetooth();
        }
    }

    private boolean hasPermissions(String[] permissions) {
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(requireContext(), permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void enableBluetooth() {
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            enableBluetoothLauncher.launch(enableBtIntent);
        } else {
            bluetoothViewModel.startScan();
        }
    }
}
