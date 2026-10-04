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

    /** The same progress with {@code xpEarned} XP earned on its day and {@code totalXp} in the deck. */
    public DailyGoalProgress withXp(int xpEarned, int totalXp) {
        return new DailyGoalProgress(date, reviewGoal, newWordGoal, sessionGoal, reviewedCount, correctCount,
            newWordsCount, xpEarned, completed, currentStreak, totalXp);
    }
}
