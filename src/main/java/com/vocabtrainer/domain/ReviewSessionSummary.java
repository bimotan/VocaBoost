package com.vocabtrainer.domain;

import java.util.List;

public record ReviewSessionSummary(
    int reviewedCount,
    int correctCount,
    int xpEarned,
    int sessionGoal,
    List<Achievement> unlockedAchievements
) {
    public double accuracy() {
        return reviewedCount == 0 ? 0.0 : correctCount / (double) reviewedCount;
    }
}
