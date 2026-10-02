package com.vocabtrainer.service;

import com.vocabtrainer.domain.Achievement;
import com.vocabtrainer.domain.DailyGoalProgress;
import com.vocabtrainer.repository.AchievementRepository;

import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Unlocks badges and their XP. Review-count badges count a deck's reviews in its review logs
 * (practice not included); the daily-goal and overdue badges belong to the deck too. Streak badges
 * belong to no deck ({@link AchievementRepository#NO_DECK}), since the streak counts every deck; one
 * is unlocked once, and not again if an older version unlocked it in some deck. Their XP goes to the
 * deck whose review unlocked them.
 */
public class AchievementService {
    /** The badges of the streak, shown in every deck. */
    static final List<String> STREAK_CODES = List.of("streak_3", "streak_7", "streak_30");
    /** The highest review count a badge asks for; counting stops there. */
    private static final int MOST_REVIEWS_COUNTED = 100;

    private final AchievementRepository achievementRepository;
    private final GoalService goalService;
    private final Clock clock;

    public AchievementService(AchievementRepository achievementRepository, GoalService goalService) {
        this(achievementRepository, goalService, Clock.systemDefaultZone());
    }

    public AchievementService(AchievementRepository achievementRepository, GoalService goalService, Clock clock) {
        this.achievementRepository = achievementRepository;
        this.goalService = goalService;
        this.clock = clock;
    }

    /** The deck's badges and the streak badges, oldest first. */
    public List<Achievement> getUnlockedAchievements(long deckId) {
        try {
            return achievementRepository.findShown(deckId, STREAK_CODES);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read achievements", e);
        }
    }

    /**
     * Unlocks what the review just saved in {@code deckId} earned, adds the badges' XP to the deck and
     * returns the badges unlocked now.
     */
    public List<Achievement> evaluate(long deckId, DailyGoalProgress progress, boolean overdueRescued,
                                      boolean dailyGoalCompletedNow) {
        try {
            List<Achievement> unlocked = new ArrayList<>();
            int reviews = goalService.reviewCount(deckId, MOST_REVIEWS_COUNTED);
            unlockIf(deckId, reviews >= 1, unlocked, definition(
                "first_review", "First Review", "Completed the first review.", 10));
            unlockIf(deckId, reviews >= 10, unlocked, definition(
                "review_10", "10 Reviews", "Completed 10 total reviews.", 15));
            unlockIf(deckId, reviews >= 50, unlocked, definition(
                "review_50", "50 Reviews", "Completed 50 total reviews.", 30));
            unlockIf(deckId, reviews >= MOST_REVIEWS_COUNTED, unlocked, definition(
                "review_100", "100 Reviews", "Completed 100 total reviews.", 50));
            unlockIf(deckId, progress.currentStreak() >= 3, unlocked, definition(
                "streak_3", "3-Day Streak", "Reviewed on 3 consecutive days.", 20));
            unlockIf(deckId, progress.currentStreak() >= 7, unlocked, definition(
                "streak_7", "7-Day Streak", "Reviewed on 7 consecutive days.", 50));
            unlockIf(deckId, progress.currentStreak() >= 30, unlocked, definition(
                "streak_30", "30-Day Streak", "Reviewed on 30 consecutive days.", 150));
            unlockIf(deckId, dailyGoalCompletedNow, unlocked, definition(
                "daily_goal", "Daily Goal", "Completed today's review and new-word goals.", 20));
            unlockIf(deckId, overdueRescued, unlocked, definition(
                "overdue_rescue", "Overdue Rescue", "Reviewed an overdue word.", 15));
            return unlocked;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot evaluate achievements", e);
        }
    }

    /**
     * Unlocks {@code achievement} unless it is unlocked already, and adds its XP to {@code deckId}. A
     * streak badge is unlocked for no deck, and counts as unlocked if it was in any deck.
     */
    private void unlockIf(long deckId, boolean condition, List<Achievement> unlocked, Achievement achievement)
        throws SQLException {
        if (!condition) {
            return;
        }
        boolean streak = STREAK_CODES.contains(achievement.code());
        if (streak && achievementRepository.existsInAnyDeck(achievement.code())) {
            return;
        }
        if (achievementRepository.insertIfAbsent(streak ? AchievementRepository.NO_DECK : deckId, achievement)) {
            goalService.awardXp(deckId, achievement.xpReward());
            unlocked.add(achievement);
        }
    }

    private Achievement definition(String code, String name, String description, int xpReward) {
        return new Achievement(code, name, description, LocalDateTime.now(clock), xpReward);
    }
}
