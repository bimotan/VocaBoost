package com.vocabtrainer.service;

import com.vocabtrainer.domain.CardState;
import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.ReviewKind;
import com.vocabtrainer.domain.ReviewLog;
import com.vocabtrainer.domain.ReviewMode;
import com.vocabtrainer.domain.ReviewRating;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.AchievementRepository;
import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.DeckRepository;
import com.vocabtrainer.repository.GoalRepository;
import com.vocabtrainer.repository.ReviewLogRepository;
import com.vocabtrainer.repository.TestDatabases;
import com.vocabtrainer.repository.WordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JSON backups keep what each review was, which way it was asked, what it counted as and whether
 * the user overrode the answer check.
 */
class ReviewLogBackupTest {
    private static final LocalDateTime FIRST = LocalDateTime.of(2026, 3, 2, 9, 0);

    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    private DeckRepository decks;
    private WordRepository words;
    private ReviewLogRepository logs;
    private BackupService backups;

    @BeforeEach
    void setUp() throws SQLException {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("backup.db"));
        decks = new DeckRepository(databaseManager);
        words = new WordRepository(databaseManager);
        logs = new ReviewLogRepository(databaseManager);
        backups = new BackupService(decks, words, logs, new GoalRepository(databaseManager),
            new AchievementRepository(databaseManager), databaseManager, new WordValidationService());
    }

    @Test
    void aRestoredBackupKeepsTheKindAndDirectionOfEveryReview() throws SQLException {
        Deck source = decks.create("Source");
        WordCard word = WordCard.createNew(source.getId(), "lucid", "清晰的");
        word.setState(CardState.REVIEW);
        word.setStability(3);
        word.setDifficulty(5);
        word.setLastReviewedAt(FIRST.plusDays(1));
        word.setNextReviewAt(FIRST.plusDays(4));
        words.insert(word);
        logs.insert(log(word, FIRST, ReviewKind.LEARN, ReviewMode.EN_TO_ZH));
        logs.insert(log(word, FIRST.plusDays(1), ReviewKind.REVIEW, ReviewMode.ZH_TO_EN));
        logs.insert(log(word, FIRST.plusDays(2), ReviewKind.PRACTICE, null));
        Path file = backups.exportJsonBackup(source.getId(), tempDir.resolve("source.json"));
        Deck target = decks.create("Target");

        BackupRestoreResult result = backups.importJsonBackup(file, target.getId());

        assertTrue(result.invalidRows().isEmpty(), result.invalidRows().toString());
        WordCard restored = words.findByEnglish(target.getId(), "lucid").orElseThrow();
        List<ReviewLog> history = logs.findByWord(restored.getId());
        assertEquals(List.of(ReviewKind.LEARN, ReviewKind.REVIEW, ReviewKind.PRACTICE),
            history.stream().map(ReviewLog::getKind).toList());
        assertEquals(ReviewMode.EN_TO_ZH, history.get(0).getDirection());
        assertEquals(ReviewMode.ZH_TO_EN, history.get(1).getDirection());
        assertNull(history.get(2).getDirection());
    }

    @Test
    void logsOfAnOlderBackupRestoreAsReviewsOfAnUnknownDirectionAndUnknownKindsAreReported() throws Exception {
        Deck target = decks.create("Target");
        Path file = tempDir.resolve("older.json");
        Files.writeString(file, """
            {"format": "vocaboost-backup", "version": 2, "deck": {"name": "Older"},
             "words": [{"english": "lucid", "chinese": "清晰的", "addedAt": "2026-03-01T08:00:00",
                        "nextReviewAt": "2026-03-01T08:00:00"}],
             "reviewLogs": [
               {"english": "lucid", "reviewedAt": "2026-03-02T09:00:00", "userAnswer": "清晰的",
                "correctAnswer": "清晰的", "similarity": 1.0, "rating": "GOOD", "elapsedMillis": 900},
               {"english": "lucid", "reviewedAt": "2026-03-03T09:00:00", "userAnswer": "清晰的",
                "correctAnswer": "清晰的", "similarity": 1.0, "rating": "GOOD", "elapsedMillis": 900,
                "kind": "CRAM", "direction": "EN_TO_ZH"},
               {"english": "lucid", "reviewedAt": "2026-03-04T09:00:00", "userAnswer": "lucid",
                "correctAnswer": "lucid", "similarity": 1.0, "rating": "GOOD", "elapsedMillis": 900,
                "kind": "review", "direction": "SIDEWAYS"}
             ]}
            """, StandardCharsets.UTF_8);

        BackupRestoreResult result = backups.importJsonBackup(file, target.getId());

        assertEquals(1, result.logsInserted());
        assertEquals(2, result.invalidRows().size(), result.invalidRows().toString());
        assertTrue(result.invalidRows().get(0).contains("unknown kind \"CRAM\""), result.invalidRows().toString());
        assertTrue(result.invalidRows().get(1).contains("unknown direction \"SIDEWAYS\""), result.invalidRows().toString());
        ReviewLog restored = logs.findByDeck(target.getId()).get(0);
        assertEquals(ReviewKind.REVIEW, restored.getKind());
        assertNull(restored.getDirection());
        assertNull(restored.getRecordedEffectiveRating());
        assertFalse(restored.isOverridden());
    }

    @Test
    void aRestoredBackupKeepsWhatEachReviewCountedAsAndWhetherItWasOverridden() throws SQLException {
        Deck source = decks.create("Source");
        WordCard word = words.insert(WordCard.createNew(source.getId(), "lucid", "清晰的"));
        logs.insert(new ReviewLog(0, word.getId(), FIRST, "清楚", "清晰的", 0.25, ReviewRating.GOOD, 1500,
            ReviewKind.LEARN, ReviewMode.EN_TO_ZH, ReviewRating.GOOD, true));
        logs.insert(new ReviewLog(0, word.getId(), FIRST.plusDays(1), "清楚", "清晰的", 0.25, ReviewRating.EASY, 1500,
            ReviewKind.REVIEW, ReviewMode.EN_TO_ZH, ReviewRating.AGAIN, false));
        logs.insert(new ReviewLog(0, word.getId(), FIRST.plusDays(2), "清晰的", "清晰的", 1.0, ReviewRating.HARD, 1500));
        Path file = backups.exportJsonBackup(source.getId(), tempDir.resolve("source.json"));
        Deck target = decks.create("Target");

        BackupRestoreResult result = backups.importJsonBackup(file, target.getId());

        assertTrue(result.invalidRows().isEmpty(), result.invalidRows().toString());
        List<ReviewLog> history = logs.findByDeck(target.getId());
        assertEquals(List.of(ReviewRating.GOOD, ReviewRating.EASY, ReviewRating.HARD),
            history.stream().map(ReviewLog::getRating).toList());
        assertEquals(ReviewRating.GOOD, history.get(0).getRecordedEffectiveRating());
        assertTrue(history.get(0).isOverridden());
        assertEquals(ReviewRating.AGAIN, history.get(1).getRecordedEffectiveRating());
        assertFalse(history.get(1).isOverridden());
        assertNull(history.get(2).getRecordedEffectiveRating(), "a log without one stays without one");
    }

    private static ReviewLog log(WordCard word, LocalDateTime at, ReviewKind kind, ReviewMode direction) {
        return new ReviewLog(0, word.getId(), at, "清晰的", "清晰的", 1.0, ReviewRating.GOOD, 1500, kind, direction);
    }
}
