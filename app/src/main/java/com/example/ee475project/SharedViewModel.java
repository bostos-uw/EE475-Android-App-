package com.example.ee475project;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

public class SharedViewModel extends ViewModel {
    private final MutableLiveData<Integer> dailyGoal = new MutableLiveData<>();

    public void setDailyGoal(int goal) {
        dailyGoal.setValue(goal);
    }

    public LiveData<Integer> getDailyGoal() {
        return dailyGoal;
    }
}
