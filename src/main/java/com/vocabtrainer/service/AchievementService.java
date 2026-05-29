package com.vocabtrainer.service;

import com.vocabtrainer.domain.Achievement;
import com.vocabtrainer.domain.DailyGoalProgress;
import com.vocabtrainer.repository.AchievementRepository;

import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class AchievementService {
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

    public List<Achievement> getUnlockedAchievements() {
        return getUnlockedAchievements(0L);
    }

    public List<Achievement> getUnlockedAchievements(long deckId) {
        try {
            return achievementRepository.findAll(deckId);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read achievements", e);
        }
    }

    public List<Achievement> evaluate(DailyGoalProgress progress, boolean overdueRescued,
                                      boolean dailyGoalCompletedNow) {
        return evaluate(0L, progress, overdueRescued, dailyGoalCompletedNow);
    }

    public List<Achievement> evaluate(long deckId, DailyGoalProgress progress, boolean overdueRescued,
                                      boolean dailyGoalCompletedNow) {
        try {
            List<Achievement> unlocked = new ArrayList<>();
            int totalReviews = goalService.totalReviews(deckId);
            unlockIf(deckId, totalReviews >= 1, unlocked, definition(
                "first_review", "First Review", "Completed the first review.", 10));
            unlockIf(deckId, totalReviews >= 10, unlocked, definition(
                "review_10", "10 Reviews", "Completed 10 total reviews.", 15));
            unlockIf(deckId, totalReviews >= 50, unlocked, definition(
                "review_50", "50 Reviews", "Completed 50 total reviews.", 30));
            unlockIf(deckId, totalReviews >= 100, unlocked, definition(
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

    private void unlockIf(boolean condition, List<Achievement> unlocked, Achievement achievement)
        throws SQLException {
        unlockIf(0L, condition, unlocked, achievement);
    }

    private void unlockIf(long deckId, boolean condition, List<Achievement> unlocked, Achievement achievement)
        throws SQLException {
        if (!condition) {
            return;
        }
        if (achievementRepository.insertIfAbsent(deckId, achievement)) {
            goalService.awardXp(deckId, achievement.xpReward());
            unlocked.add(achievement);
        }
    }

    private Achievement definition(String code, String name, String description, int xpReward) {
        return new Achievement(code, name, description, LocalDateTime.now(clock), xpReward);
    }
}
