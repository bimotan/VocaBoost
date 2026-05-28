package com.vocabtrainer.domain;

public record GoalUpdate(
    DailyGoalProgress progress,
    int xpEarned,
    boolean dailyGoalCompleted
) {
}
