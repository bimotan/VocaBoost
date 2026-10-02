package com.vocabtrainer.service;

import com.vocabtrainer.domain.Achievement;
import com.vocabtrainer.domain.DailyGoalProgress;
import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.repository.AchievementRepository;
import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.DeckRepository;
import com.vocabtrainer.repository.GoalRepository;
import com.vocabtrainer.repository.ReviewLogRepository;
import com.vocabtrainer.repository.TestDatabases;
import com.vocabtrainer.repository.WordRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Streak badges belong to no deck, yet a deck's JSON backup keeps them: restoring it on a new
 * computer shows them again and does not unlock them a second time with their XP.
 */
class StreakBadgeBackupTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-28T10:00:00Z"), ZoneId.of("UTC"));
    private static final Achievement STREAK_3 = new Achievement("streak_3", "3-Day Streak",
        "Reviewed on 3 consecutive days.", LocalDateTime.of(2026, 5, 20, 9, 0), 20);
    private static final Achievement FIRST_REVIEW = new Achievement("first_review", "First Review",
        "Completed the first review.", LocalDateTime.of(2026, 5, 18, 9, 0), 10);

    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    @Test
    void aBackupKeepsTheStreakBadgesAndRestoringThemAwardsNothing() throws Exception {
        Db source = new Db(tempDir.resolve("source.db"));
        Deck deck = source.decks.ensureDefaultDeck();
        source.achievements.insertIfAbsent(deck.getId(), FIRST_REVIEW);
        source.achievements.insertIfAbsent(AchievementRepository.NO_DECK, STREAK_3);
        Path json = source.backup.exportJsonBackup(deck.getId(), tempDir.resolve("backup.json"));

        Db fresh = new Db(tempDir.resolve("fresh.db"));
        Deck freshDeck = fresh.decks.ensureDefaultDeck();
        Deck other = fresh.decks.create("TOEFL");
        assertEquals(2, fresh.backup.importJsonBackup(json, freshDeck.getId()).achievementsRestored());

        assertEquals(List.of("first_review", "streak_3"), codes(fresh.achievementService.getUnlockedAchievements(
            freshDeck.getId())));
        assertEquals(List.of("streak_3"), codes(fresh.achievementService.getUnlockedAchievements(other.getId())),
            "the streak badge shows in every deck");
        List<Achievement> unlocked = fresh.achievementService.evaluate(other.getId(), threeDayStreak(), false, false);
        assertTrue(unlocked.stream().noneMatch(badge -> badge.code().equals("streak_3")), unlocked.toString());
        assertEquals(0, fresh.goals.totalXp(other.getId()));

        // Restoring it into another deck adds that deck's First Review, but no second streak badge.
        assertEquals(1, fresh.backup.importJsonBackup(json, other.getId()).achievementsRestored());
        assertEquals(1, fresh.achievements.findAll().stream().filter(badge -> badge.code().equals("streak_3")).count());
    }

    @Test
    void aStreakBadgeAnOlderVersionUnlockedInADeckIsNotRestoredTwice() throws Exception {
        Db source = new Db(tempDir.resolve("source.db"));
        Deck deck = source.decks.ensureDefaultDeck();
        // Older versions unlocked streak badges per deck, and their backups carry them that way.
        source.achievements.insertIfAbsent(deck.getId(), STREAK_3);
        Path json = source.backup.exportJsonBackup(deck.getId(), tempDir.resolve("backup.json"));

        Db target = new Db(tempDir.resolve("target.db"));
        Deck targetDeck = target.decks.ensureDefaultDeck();
        Deck legacyDeck = target.decks.create("Legacy");
        target.achievements.insertIfAbsent(legacyDeck.getId(), STREAK_3);

        assertEquals(0, target.backup.importJsonBackup(json, targetDeck.getId()).achievementsRestored());
        assertEquals(1, target.achievements.findAll().size());
        assertEquals(List.of("streak_3"), codes(target.achievementService.getUnlockedAchievements(targetDeck.getId())));
    }

    private static DailyGoalProgress threeDayStreak() {
        return new DailyGoalProgress(LocalDate.of(2026, 5, 28), 20, 5, 20, 1, 1, 0, 0, false, 3, 0);
    }

    private static List<String> codes(List<Achievement> achievements) {
        return achievements.stream().map(Achievement::code).toList();
    }

    private final class Db {
        final DeckRepository decks;
        final AchievementRepository achievements;
        final GoalService goals;
        final AchievementService achievementService;
        final BackupService backup;

        Db(Path file) throws SQLException {
            DatabaseManager databaseManager = databases.open(file);
            decks = new DeckRepository(databaseManager);
            WordRepository words = new WordRepository(databaseManager);
            ReviewLogRepository logs = new ReviewLogRepository(databaseManager);
            GoalRepository goalRepository = new GoalRepository(databaseManager);
            achievements = new AchievementRepository(databaseManager);
            goals = new GoalService(goalRepository, logs, CLOCK);
            achievementService = new AchievementService(achievements, goals, CLOCK);
            backup = new BackupService(decks, words, logs, goalRepository, achievements, databaseManager,
                new WordValidationService(), CLOCK);
        }
    }
}
