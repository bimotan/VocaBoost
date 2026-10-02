package com.vocabtrainer.domain;

import java.util.List;

/**
 * @param reviewedCount ratings saved in the session, a card shown again counted each time
 * @param correctCount  ratings that were not Again and had an answer at least 50% similar
 * @param cardsReviewed different cards rated in the session; the session goal counts these
 */
public record ReviewSessionSummary(
    int reviewedCount,
    int correctCount,
    int xpEarned,
    int sessionGoal,
    List<Achievement> unlockedAchievements,
    int cardsReviewed
) {
    public double accuracy() {
        return reviewedCount == 0 ? 0.0 : correctCount / (double) reviewedCount;
    }
}
