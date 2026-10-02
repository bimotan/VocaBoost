package com.vocabtrainer.domain;

import java.util.List;

/**
 * A saved rating.
 *
 * @param becameLeech whether this rating's lapse made the word a leech (see {@link WordCard#isLeech()})
 * @param kind        what was saved: a practice of a word that was not due left its schedule as it was
 */
public record ReviewOutcome(
    WordCard word,
    DailyGoalProgress progress,
    int xpEarned,
    List<Achievement> unlockedAchievements,
    ReviewSessionSummary sessionSummary,
    boolean becameLeech,
    ReviewKind kind
) {
    public boolean isPractice() {
        return kind == ReviewKind.PRACTICE;
    }
}
