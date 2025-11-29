package com.example.ee475project;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private FirebaseAuth mAuth;
    private SharedViewModel sharedViewModel;

    private DatabaseReference userGoalRef;
    private ValueEventListener userGoalListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            FirebaseDatabase.getInstance().setPersistenceEnabled(true);
        } catch (Exception e) {
            Log.w(TAG, "Firebase persistence failed to enable", e);
        }

        setContentView(R.layout.activity_main);

        mAuth = FirebaseAuth.getInstance();
        sharedViewModel = new ViewModelProvider(this).get(SharedViewModel.class);

        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser == null) {
            Intent intent = new Intent(MainActivity.this, LoginActivity.class);
            startActivity(intent);
            finish();
            return;
        }

        fetchUserData(currentUser.getUid());

        BottomNavigationView bottomNav = findViewById(R.id.bottom_navigation);
        bottomNav.setOnItemSelectedListener(navListener);

        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction().replace(R.id.fragment_container, new HomeFragment()).commit();
        }
    }

    private void fetchUserData(String userId) {
        userGoalRef = FirebaseDatabase.getInstance().getReference("users").child(userId).child("dailyGoal");
        userGoalListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                Integer dailyGoal = dataSnapshot.getValue(Integer.class);
                if (dailyGoal != null) {
                    sharedViewModel.setDailyGoal(dailyGoal);
                } else {
                    sharedViewModel.setDailyGoal(60); // Default value
                }
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
                Log.e(TAG, "Failed to read daily goal", databaseError.toException());
                sharedViewModel.setDailyGoal(60); // Default value on error
            }
        };
        userGoalRef.addValueEventListener(userGoalListener);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Remove the listener to prevent memory leaks and permission errors on sign-out
        if (userGoalRef != null && userGoalListener != null) {
            userGoalRef.removeEventListener(userGoalListener);
        }
    }

    private final BottomNavigationView.OnItemSelectedListener navListener = item -> {
        Fragment selectedFragment = null;
        int itemId = item.getItemId();

        if (itemId == R.id.home) {
            selectedFragment = new HomeFragment();
        } else if (itemId == R.id.analytics) {
            selectedFragment = new AnalyticsFragment();
        } else if (itemId == R.id.training) {
            selectedFragment = new TrainingFragment();  // NEW
        } else if (itemId == R.id.settings) {
            selectedFragment = new ProfileFragment();
        }

        if (selectedFragment != null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.fragment_container, selectedFragment)
                    .commit();
        }

        return true;
    };
}
