package com.vocabtrainer.domain;

import java.util.List;

public record ReviewOutcome(
    WordCard word,
    DailyGoalProgress progress,
    int xpEarned,
    List<Achievement> unlockedAchievements,
    ReviewSessionSummary sessionSummary
) {
}
