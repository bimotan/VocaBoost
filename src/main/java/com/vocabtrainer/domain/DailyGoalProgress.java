package com.vocabtrainer.domain;

import java.time.LocalDate;

public record DailyGoalProgress(
    LocalDate date,
    int reviewGoal,
    int newWordGoal,
    int sessionGoal,
    int reviewedCount,
    int correctCount,
    int newWordsCount,
    int xpEarned,
    boolean completed,
    int currentStreak,
    int totalXp
) {
    public double reviewProgress() {
        return reviewGoal <= 0 ? 1.0 : Math.min(1.0, reviewedCount / (double) reviewGoal);
    }

    public double newWordProgress() {
        return newWordGoal <= 0 ? 1.0 : Math.min(1.0, newWordsCount / (double) newWordGoal);
    }

    public double accuracy() {
        return reviewedCount == 0 ? 0.0 : correctCount / (double) reviewedCount;
    }
}
