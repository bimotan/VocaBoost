package com.vocabtrainer.service;

import com.vocabtrainer.domain.Achievement;
import com.vocabtrainer.domain.GoalUpdate;
import com.vocabtrainer.domain.ReviewRating;
import com.vocabtrainer.repository.AchievementRepository;
import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.GoalRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AchievementServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void unlocksFirstReviewAndReviewCountBadges() throws Exception {
        DatabaseManager databaseManager = new DatabaseManager(tempDir.resolve("achievements.db"));
        databaseManager.initialize();
        Clock clock = Clock.fixed(Instant.parse("2026-05-28T09:00:00Z"), ZoneId.of("UTC"));
        GoalService goalService = new GoalService(new GoalRepository(databaseManager), clock);
        AchievementService achievementService = new AchievementService(
            new AchievementRepository(databaseManager),
            goalService,
            clock
        );

        List<Achievement> unlocked = List.of();
        for (int i = 0; i < 10; i++) {
            GoalUpdate update = goalService.recordReview(ReviewRating.GOOD, 0.9);
            unlocked = achievementService.evaluate(update.progress(), false, update.dailyGoalCompleted());
        }

        List<String> allCodes = achievementService.getUnlockedAchievements().stream()
            .map(Achievement::code)
            .toList();
        assertTrue(allCodes.contains("first_review"));
        assertTrue(allCodes.contains("review_10"));
        assertTrue(goalService.getTodayProgress().totalXp() > 0);
        assertTrue(unlocked.stream().anyMatch(achievement -> "review_10".equals(achievement.code())));
    }

    @Test
    void unlocksStreakBadge() throws Exception {
        DatabaseManager databaseManager = new DatabaseManager(tempDir.resolve("streak-achievements.db"));
        databaseManager.initialize();
        GoalRepository goalRepository = new GoalRepository(databaseManager);
        LocalDate today = LocalDate.of(2026, 5, 28);
        for (int i = 0; i < 3; i++) {
            LocalDate date = today.minusDays(i);
            goalRepository.ensure(date, 20, 5, 10);
            goalRepository.addProgress(date, 1, 1, 0, 5);
        }
        Clock clock = Clock.fixed(Instant.parse("2026-05-28T09:00:00Z"), ZoneId.of("UTC"));
        GoalService goalService = new GoalService(goalRepository, clock);
        AchievementService achievementService = new AchievementService(
            new AchievementRepository(databaseManager),
            goalService,
            clock
        );

        List<Achievement> unlocked = achievementService.evaluate(goalService.getTodayProgress(), false, false);

        assertTrue(unlocked.stream().anyMatch(achievement -> "streak_3".equals(achievement.code())));
    }
}
