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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Schema version 6 logs what each review was and which way the question was asked. Logs from
 * before keep everything they had and read as reviews of an unknown direction.
 */
class ReviewLogKindMigrationTest {
    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    @Test
    void aVersionFiveDatabaseKeepsItsLogsAsReviewsOfAnUnknownDirection() throws Exception {
        Path file = tempDir.resolve("v5.db");
        LegacySchemas.FSRS_VERSION_5.create(file);
        LegacySchemas.execute(file,
            "INSERT INTO decks(id, name, created_at) VALUES(1, 'GRE', '2026-03-01T08:00:00')",
            "INSERT INTO words(id, deck_id, english, chinese, added_at, last_reviewed_at, next_review_at, easiness_factor,"
                + " interval_days, repetitions, consecutive_correct, lapses, card_state, stability, difficulty)"
                + " VALUES(1, 1, 'lucid', '清晰的', '2026-03-01T08:00:00', '2026-03-05T09:00:00', '2026-03-09T04:00:00',"
                + " 2.5, 4, 2, 2, 0, 'REVIEW', 4.2, 5.1)",
            "INSERT INTO review_logs(word_id, reviewed_at, user_answer, correct_answer, similarity, rating, elapsed_millis)"
                + " VALUES(1, '2026-03-02T09:00:00', '清晰的', '清晰的', 1.0, 'GOOD', 4200)",
            "INSERT INTO review_logs(word_id, reviewed_at, user_answer, correct_answer, similarity, rating, elapsed_millis)"
                + " VALUES(1, '2026-03-05T09:00:00', '清楚', '清晰的', 0.6, 'HARD', 5100)");

        DatabaseManager databaseManager = databases.open(file);

        assertEquals(SchemaMigrations.CURRENT_VERSION, SchemaMigrationTest.intQuery(file, "PRAGMA user_version"));
        List<ReviewLog> logs = new ReviewLogRepository(databaseManager).findByWord(1);
        assertEquals(2, logs.size());
        assertEquals(ReviewRating.HARD, logs.get(1).getRating());
        assertEquals(5100, logs.get(1).getElapsedMillis());
        assertTrue(logs.stream().allMatch(log -> log.getKind() == ReviewKind.REVIEW && log.getDirection() == null));
        assertEquals(4.2, new WordRepository(databaseManager).findById(1).orElseThrow().getStability());
        assertEquals("1", SchemaMigrationTest.stringColumn(file, "SELECT partial FROM pragma_index_list('words')"
            + " WHERE name = 'idx_words_queue'").get(0));
        SchemaMigrationTest.assertDatabaseIsConsistent(file);
    }

    @Test
    void anOlderVersionWritingToTheDatabaseLogsReviewsOfAnUnknownDirection() throws Exception {
        Path file = tempDir.resolve("shared.db");
        DatabaseManager databaseManager = databases.open(file);
        LegacySchemas.execute(file,
            "INSERT INTO decks(id, name, created_at) VALUES(1, 'GRE', '2026-03-01T08:00:00')",
            "INSERT INTO words(id, deck_id, english, chinese, added_at, next_review_at, easiness_factor, interval_days,"
                + " repetitions, consecutive_correct, lapses) VALUES(1, 1, 'lucid', '清晰的', '2026-03-01T08:00:00',"
                + " '2026-03-01T08:00:00', 2.5, 0, 0, 0, 0)",
            // What a version-5 app inserts: no kind, no direction.
            "INSERT INTO review_logs(word_id, reviewed_at, user_answer, correct_answer, similarity, rating, elapsed_millis)"
                + " VALUES(1, '2026-03-02T09:00:00', '清晰的', '清晰的', 1.0, 'GOOD', 4200)");
        ReviewLogRepository logs = new ReviewLogRepository(databaseManager);
        logs.insert(new ReviewLog(0, 1, LocalDateTime.of(2026, 3, 3, 9, 0), "lucid", "lucid", 1.0, ReviewRating.EASY,
            3000, ReviewKind.PRACTICE, ReviewMode.ZH_TO_EN));

        List<ReviewLog> history = logs.findByWord(1);

        assertEquals(ReviewKind.REVIEW, history.get(0).getKind());
        assertNull(history.get(0).getDirection());
        assertEquals(ReviewKind.PRACTICE, history.get(1).getKind());
        assertEquals(ReviewMode.ZH_TO_EN, history.get(1).getDirection());
        assertEquals(List.of("REVIEW|null", "PRACTICE|ZH_TO_EN"), SchemaMigrationTest.stringColumn(file,
            "SELECT kind || '|' || COALESCE(direction, 'null') FROM review_logs ORDER BY id"));
    }
}
