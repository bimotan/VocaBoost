package com.vocabtrainer.domain;

import java.util.List;

/**
 * A saved rating.
 *
 * @param becameLeech whether this rating's lapse made the word a leech (see {@link WordCard#isLeech()})
 */
public record ReviewOutcome(
    WordCard word,
    DailyGoalProgress progress,
    int xpEarned,
    List<Achievement> unlockedAchievements,
    ReviewSessionSummary sessionSummary,
    boolean becameLeech
) {
}
