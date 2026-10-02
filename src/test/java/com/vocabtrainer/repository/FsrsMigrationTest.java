package com.vocabtrainer.repository;

import com.vocabtrainer.domain.CardState;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.service.CardStateBackfill;
import com.vocabtrainer.service.ReviewScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Schema version 5 and the startup backfill turn the SM-2 schedule of a version-4 database into
 * FSRS card state: replayed from the review logs where there are any, estimated from the SM-2
 * fields otherwise. The SM-2 columns and the logs stay.
 */
class FsrsMigrationTest {
    private static final double EPSILON = 1e-5;

    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    @Test
    void anSm2DatabaseGetsItsCardStateFromReviewLogsOrTheSm2Schedule() throws Exception {
        Path file = tempDir.resolve("sm2.db");
        LegacySchemas.SM2_VERSION_4.create(file);
        LegacySchemas.execute(file,
            "INSERT INTO decks(id, name, created_at) VALUES(1, 'GRE', '2026-03-01T08:00:00')",
            // Reviewed five times; the SM-2 fields say interval 3, but the logs tell the whole story.
            word(1, "lucid", "2026-04-14T20:00:00", "2026-04-17T20:00:00", 2.18, 3, 5, 2, 2),
            log(1, "2026-04-01T09:00:00", 1.0, "GOOD"),
            log(1, "2026-04-02T09:00:00", 1.0, "GOOD"),
            // Rated Good but answered wrongly: counts as Again, like the old scheduler counted it.
            log(1, "2026-04-10T09:00:00", 0.1, "GOOD"),
            log(1, "2026-04-10T09:15:00", 1.0, "GOOD"),
            log(1, "2026-04-14T20:00:00", 1.0, "HARD"),
            // SM-2 progress without logs, e.g. restored from a backup that had none.
            word(2, "abate", "2026-04-20T09:00:00", "2026-05-07T09:00:00", 1.3, 17, 4, 4, 2),
            // Never reviewed.
            word(3, "laud", null, "2026-03-01T08:00:00", 2.5, 0, 0, 0, 0));

        DatabaseManager databaseManager = databases.open(file);

        assertEquals(5, SchemaMigrationTest.intQuery(file, "PRAGMA user_version"));
        assertEquals(3, SchemaMigrationTest.intQuery(file, "SELECT COUNT(*) FROM words WHERE card_state IS NULL"));
        WordRepository words = new WordRepository(databaseManager);
        ReviewLogRepository logs = new ReviewLogRepository(databaseManager);
        CardStateBackfill backfill = new CardStateBackfill(words, logs, new ReviewScheduler());

        assertEquals(3, backfill.run());

        // The reference scheduler (py-fsrs 5.1.3) ends these reviews in review with this memory.
        WordCard lucid = words.findById(1).orElseThrow();
        assertEquals(CardState.REVIEW, lucid.getState());
        assertEquals(4.009568, lucid.getStability(), EPSILON);
        assertEquals(7.278873, lucid.getDifficulty(), EPSILON);
        assertEquals(5, lucid.getRepetitions());
        assertEquals(1, lucid.getLapses());
        assertEquals(2, lucid.getConsecutiveCorrect());
        assertEquals(LocalDateTime.of(2026, 4, 14, 20, 0), lucid.getLastReviewedAt());
        assertTrue(lucid.getIntervalDays() >= 3 && lucid.getIntervalDays() <= 5, "interval " + lucid.getIntervalDays());
        assertEquals(LocalDate.of(2026, 4, 14).plusDays(lucid.getIntervalDays()).atTime(4, 0), lucid.getNextReviewAt());
        assertTrue(lucid.isWeak(), "hard and not mastered");

        WordCard abate = words.findById(2).orElseThrow();
        assertEquals(CardState.REVIEW, abate.getState());
        assertEquals(17.0, abate.getStability(), EPSILON);
        assertEquals(9.0, abate.getDifficulty(), EPSILON);
        assertEquals(2, abate.getLapses());
        assertEquals(LocalDateTime.of(2026, 5, 7, 9, 0), abate.getNextReviewAt(), "the SM-2 due date is kept");

        WordCard laud = words.findById(3).orElseThrow();
        assertEquals(CardState.NEW, laud.getState());
        assertEquals(0.0, laud.getStability());

        assertEquals(0, SchemaMigrationTest.intQuery(file, "SELECT COUNT(*) FROM words WHERE card_state IS NULL"));
        assertEquals(5, SchemaMigrationTest.intQuery(file, "SELECT COUNT(*) FROM review_logs"));
        SchemaMigrationTest.assertDatabaseIsConsistent(file);
        assertEquals(0, backfill.run(), "nothing left to derive");
    }

    @Test
    void aWordAddedByAnOlderVersionLaterIsDerivedOnTheNextStart() throws Exception {
        Path file = tempDir.resolve("downgraded.db");
        DatabaseManager databaseManager = databases.open(file);
        WordRepository words = new WordRepository(databaseManager);
        CardStateBackfill backfill = new CardStateBackfill(words, new ReviewLogRepository(databaseManager),
            new ReviewScheduler());
        LegacySchemas.execute(file, "INSERT INTO decks(id, name, created_at) VALUES(1, 'GRE', '2026-03-01T08:00:00')",
            // What a version-4 app inserts: the SM-2 columns only.
            word(1, "candid", "2026-04-20T09:00:00", "2026-04-27T09:00:00", 2.5, 7, 3, 3, 0));

        // Until it is derived, the word reads as estimated from its SM-2 schedule.
        WordCard estimated = words.findById(1).orElseThrow();
        assertEquals(CardState.REVIEW, estimated.getState());
        assertEquals(7.0, estimated.getStability(), EPSILON);
        assertEquals(5.0, estimated.getDifficulty(), EPSILON);
        assertFalse(words.findWithoutCardState().isEmpty());

        assertEquals(1, backfill.run());

        assertTrue(words.findWithoutCardState().isEmpty());
        assertEquals("REVIEW", SchemaMigrationTest.stringColumn(file, "SELECT card_state FROM words").get(0));
    }

    private static String word(long id, String english, String lastReviewedAt, String nextReviewAt, double easiness,
                               int intervalDays, int repetitions, int consecutiveCorrect, int lapses) {
        return "INSERT INTO words(id, deck_id, english, chinese, added_at, last_reviewed_at, next_review_at,"
            + " easiness_factor, interval_days, repetitions, consecutive_correct, lapses) VALUES(" + id + ", 1, '"
            + english + "', '释义', '2026-03-01T08:00:00', " + (lastReviewedAt == null ? "NULL" : "'" + lastReviewedAt + "'")
            + ", '" + nextReviewAt + "', " + easiness + ", " + intervalDays + ", " + repetitions + ", "
            + consecutiveCorrect + ", " + lapses + ")";
    }

    private static String log(long wordId, String reviewedAt, double similarity, String rating) {
        return "INSERT INTO review_logs(word_id, reviewed_at, user_answer, correct_answer, similarity, rating,"
            + " elapsed_millis) VALUES(" + wordId + ", '" + reviewedAt + "', '', '释义', " + similarity + ", '"
            + rating + "', 1000)";
    }
}
