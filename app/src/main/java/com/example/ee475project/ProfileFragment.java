package com.example.ee475project;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

public class ProfileFragment extends Fragment {

    private static final String TAG = "ProfileFragment";

    private ActivityResultLauncher<String> requestPermissionLauncher;
    private EditText goalInput;
    private TextView goalTextPreview;
    private Button saveGoalButton;
    private Button signOutButton;
    private SharedViewModel sharedViewModel;
    private BluetoothViewModel bluetoothViewModel;
    private TextView upperBackStatusText, lowerBackStatusText;
    private View upperBackStatusIndicator, lowerBackStatusIndicator;

    private FirebaseAuth mAuth;
    private DatabaseReference mDatabase;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestPermissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
            // We can handle the permission result here if needed
        });
        sharedViewModel = new ViewModelProvider(requireActivity()).get(SharedViewModel.class);
        bluetoothViewModel = new ViewModelProvider(requireActivity()).get(BluetoothViewModel.class);

        mAuth = FirebaseAuth.getInstance();
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser != null) {
            mDatabase = FirebaseDatabase.getInstance().getReference("users").child(currentUser.getUid());
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_profile, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        goalInput = view.findViewById(R.id.goal_input);
        goalTextPreview = view.findViewById(R.id.goal_text_preview);
        saveGoalButton = view.findViewById(R.id.save_goal_button);
        signOutButton = view.findViewById(R.id.sign_out_button);
        upperBackStatusText = view.findViewById(R.id.upper_back_status_text);
        upperBackStatusIndicator = view.findViewById(R.id.upper_back_status_indicator);
        lowerBackStatusText = view.findViewById(R.id.lower_back_status_text);
        lowerBackStatusIndicator = view.findViewById(R.id.lower_back_status_indicator);

        sharedViewModel.getDailyGoal().observe(getViewLifecycleOwner(), goal -> {
            if (goal != null) {
                goalInput.setText(String.valueOf(goal));
                updateGoalPreview(goal);
            }
        });

        goalInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!s.toString().isEmpty()) {
                    try {
                        updateGoalPreview(Integer.parseInt(s.toString()));
                    } catch (NumberFormatException e) {
                        // ignore
                    }
                }
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        saveGoalButton.setOnClickListener(v -> {
            if (mDatabase != null) {
                try {
                    int newGoal = Integer.parseInt(goalInput.getText().toString());
                    mDatabase.child("dailyGoal").setValue(newGoal)
                            .addOnSuccessListener(aVoid -> {
                                if (isAdded()) {
                                    Toast.makeText(getContext(), "Goal saved!", Toast.LENGTH_SHORT).show();
                                }
                            })
                            .addOnFailureListener(e -> {
                                Log.e(TAG, "Failed to save goal.", e);
                                if (isAdded()) {
                                    Toast.makeText(getContext(), "Failed to save goal: " + e.getMessage(), Toast.LENGTH_LONG).show();
                                }
                            });
                } catch (NumberFormatException e) {
                    Log.e(TAG, "Invalid goal input", e);
                    if (isAdded()) {
                        Toast.makeText(getContext(), "Invalid goal format.", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        });

        signOutButton.setOnClickListener(v -> {
            FirebaseAuth.getInstance().signOut();
            Intent intent = new Intent(getActivity(), LoginActivity.class);
            startActivity(intent);
            if (getActivity() != null) {
                getActivity().finish();
            }
        });

        SwitchCompat notificationSwitch = view.findViewById(R.id.notification_switch);
        notificationSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                if (!isNotificationPermissionGranted()) {
                    requestNotificationPermission();
                }
            }
        });

        bluetoothViewModel.getConnectionStatus().observe(getViewLifecycleOwner(), status -> {
            boolean isUpperConnected = status != null && status.equals("Connected to XIAO_Upper_Back");
            boolean isLowerConnected = status != null && status.equals("Connected to XIAO_Lower_Back");

            updateDeviceStatus(isUpperConnected, upperBackStatusText, upperBackStatusIndicator);
            updateDeviceStatus(isLowerConnected, lowerBackStatusText, lowerBackStatusIndicator);
        });
    }

    private void updateDeviceStatus(boolean isConnected, TextView statusText, View statusIndicator) {
        if (isConnected) {
            statusText.setText("Connected");
            statusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.ios_green));
            statusIndicator.setBackgroundResource(R.drawable.connected_dot);
        } else {
            statusText.setText("Not Connected");
            statusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.ios_red));
            statusIndicator.setBackgroundResource(R.drawable.disconnected_dot);
        }
    }

    private void updateGoalPreview(int minutes) {
        int hours = minutes / 60;
        int mins = minutes % 60;
        goalTextPreview.setText(hours + "h " + mins + "m per day");
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    private boolean isNotificationPermissionGranted() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        }
        return true; // Permissions are not needed for pre-Tiramisu versions
    }
}
