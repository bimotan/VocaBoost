package com.vocabtrainer.repository;

import com.vocabtrainer.domain.ReviewKind;
import com.vocabtrainer.domain.ReviewLog;
import com.vocabtrainer.domain.ReviewMode;
import com.vocabtrainer.domain.ReviewRating;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Schema version 7 logs the rating the schedule used and whether the user overrode the answer
 * check. Logs from before keep everything they had; their effective rating reads as their rating
 * capped by their similarity, as those versions scheduled them.
 */
class ReviewLogEffectiveRatingMigrationTest {
    private static final String WORD = "INSERT INTO words(id, deck_id, english, chinese, added_at, next_review_at,"
        + " easiness_factor, interval_days, repetitions, consecutive_correct, lapses)"
        + " VALUES(1, 1, 'lucid', '清晰的', '2026-03-01T08:00:00', '2026-03-01T08:00:00', 2.5, 0, 0, 0, 0)";

    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    @Test
    void aVersionSixDatabaseKeepsItsLogsAndDerivesWhatTheyCountedAs() throws Exception {
        Path file = tempDir.resolve("v6.db");
        LegacySchemas.REVIEW_QUEUE_VERSION_6.create(file);
        LegacySchemas.execute(file,
            "INSERT INTO decks(id, name, created_at) VALUES(1, 'GRE', '2026-03-01T08:00:00')",
            WORD,
            "INSERT INTO review_logs(word_id, reviewed_at, user_answer, correct_answer, similarity, rating, elapsed_millis,"
                + " kind, direction) VALUES(1, '2026-03-02T09:00:00', '清晰的', '清晰的', 1.0, 'GOOD', 4200, 'LEARN', 'EN_TO_ZH')",
            "INSERT INTO review_logs(word_id, reviewed_at, user_answer, correct_answer, similarity, rating, elapsed_millis,"
                + " kind, direction) VALUES(1, '2026-03-05T09:00:00', '清楚', '清晰的', 0.25, 'GOOD', 5100, 'REVIEW', 'EN_TO_ZH')");

        DatabaseManager databaseManager = databases.open(file);

        assertEquals(SchemaMigrations.CURRENT_VERSION, SchemaMigrationTest.intQuery(file, "PRAGMA user_version"));
        List<ReviewLog> logs = new ReviewLogRepository(databaseManager).findByWord(1);
        assertEquals(2, logs.size());
        assertTrue(logs.stream().allMatch(log -> log.getRecordedEffectiveRating() == null && !log.isOverridden()));
        assertEquals(ReviewRating.GOOD, logs.get(0).getEffectiveRating());
        assertEquals(ReviewRating.AGAIN, logs.get(1).getEffectiveRating(), "a Good at 25% similarity was scheduled as Again");
        assertEquals(ReviewRating.GOOD, logs.get(1).getRating());
        assertEquals(ReviewKind.REVIEW, logs.get(1).getKind());
        assertFalse(logs.get(1).isCorrect());
        SchemaMigrationTest.assertDatabaseIsConsistent(file);
    }

    @Test
    void newLogsStoreBothRatingsAndLogsOfAnOlderVersionStillRead() throws Exception {
        Path file = tempDir.resolve("shared.db");
        DatabaseManager databaseManager = databases.open(file);
        LegacySchemas.execute(file,
            "INSERT INTO decks(id, name, created_at) VALUES(1, 'GRE', '2026-03-01T08:00:00')",
            WORD,
            // What a version-6 app inserts: no effective rating, no override.
            "INSERT INTO review_logs(word_id, reviewed_at, user_answer, correct_answer, similarity, rating, elapsed_millis,"
                + " kind, direction) VALUES(1, '2026-03-02T09:00:00', '完全错误', '清晰的', 0.0, 'EASY', 4200, 'LEARN', 'EN_TO_ZH')");
        ReviewLogRepository logs = new ReviewLogRepository(databaseManager);
        logs.insert(new ReviewLog(0, 1, LocalDateTime.of(2026, 3, 3, 9, 0), "清楚", "清晰的", 0.25, ReviewRating.EASY,
            3000, ReviewKind.REVIEW, ReviewMode.EN_TO_ZH, ReviewRating.EASY, true));
        logs.insert(new ReviewLog(0, 1, LocalDateTime.of(2026, 3, 4, 9, 0), "清晰", "清晰的", 0.6, ReviewRating.GOOD,
            3000, ReviewKind.REVIEW, ReviewMode.EN_TO_ZH, ReviewRating.HARD, false));

        List<ReviewLog> history = logs.findByWord(1);

        assertNull(history.get(0).getRecordedEffectiveRating());
        assertEquals(ReviewRating.AGAIN, history.get(0).getEffectiveRating());
        assertEquals(ReviewRating.EASY, history.get(1).getEffectiveRating());
        assertTrue(history.get(1).isOverridden());
        assertEquals(ReviewRating.HARD, history.get(2).getEffectiveRating());
        assertFalse(history.get(2).isOverridden());
        assertEquals(List.of("EASY|null|0", "EASY|EASY|1", "GOOD|HARD|0"), SchemaMigrationTest.stringColumn(file,
            "SELECT rating || '|' || COALESCE(effective_rating, 'null') || '|' || overridden FROM review_logs ORDER BY id"));
    }
}
