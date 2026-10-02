package com.vocabtrainer.service;

import com.vocabtrainer.domain.DailyGoalProgress;
import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.GoalUpdate;
import com.vocabtrainer.domain.ReviewRating;
import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.DeckRepository;
import com.vocabtrainer.repository.GoalRepository;
import com.vocabtrainer.repository.TestDatabases;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoalServiceTest {
    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    @Test
    void recordsNewWordsReviewsXpAndDailyCompletion() throws Exception {
        GoalService service = serviceAt(LocalDate.of(2026, 5, 28));

        service.recordNewWords(5);
        GoalUpdate last = null;
        for (int i = 0; i < 20; i++) {
            last = service.recordReview(ReviewRating.GOOD, 0.9);
        }

        DailyGoalProgress progress = service.getTodayProgress();
        assertEquals(20, progress.reviewedCount());
        assertEquals(5, progress.newWordsCount());
        assertTrue(progress.completed());
        assertTrue(last.dailyGoalCompleted());
        assertTrue(progress.totalXp() > 0);
    }

    @Test
    void calculatesReviewStreakAcrossConsecutiveDays() throws Exception {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("streak.db"));
        GoalRepository repository = new GoalRepository(databaseManager);
        LocalDate today = LocalDate.of(2026, 5, 28);
        for (int i = 0; i < 3; i++) {
            LocalDate date = today.minusDays(i);
            repository.ensure(date, 20, 5, 10);
            repository.addProgress(date, 1, 1, 0, 5);
        }
        GoalService service = new GoalService(repository, clockAt(today));

        assertEquals(3, service.getTodayProgress().currentStreak());
    }

    @Test
    void deckScopedGoalsDoNotPolluteEachOther() throws Exception {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("deck-goals.db"));
        DeckRepository deckRepository = new DeckRepository(databaseManager);
        Deck defaultDeck = deckRepository.ensureDefaultDeck();
        Deck secondDeck = deckRepository.create("Second");
        GoalService service = new GoalService(new GoalRepository(databaseManager), clockAt(LocalDate.of(2026, 5, 28)));

        service.recordNewWords(defaultDeck.getId(), 3);
        service.recordReview(defaultDeck.getId(), ReviewRating.GOOD, 0.9);
        service.recordReview(secondDeck.getId(), ReviewRating.AGAIN, 0.1);

        assertEquals(3, service.getTodayProgress(defaultDeck.getId()).newWordsCount());
        assertEquals(1, service.getTodayProgress(defaultDeck.getId()).reviewedCount());
        assertEquals(0, service.getTodayProgress(secondDeck.getId()).newWordsCount());
        assertEquals(1, service.getTodayProgress(secondDeck.getId()).reviewedCount());
        assertTrue(service.getTodayProgress(defaultDeck.getId()).totalXp()
            > service.getTodayProgress(secondDeck.getId()).totalXp());
    }

    @Test
    void streakEndsTodayOrYesterdayAndStopsAtTheFirstDayWithoutReviews() throws Exception {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("streak-gaps.db"));
        GoalRepository repository = new GoalRepository(databaseManager);
        LocalDate today = LocalDate.of(2026, 5, 28);
        reviewedOn(repository, 1, today.minusDays(1), today.minusDays(2), today.minusDays(3),
            today.minusDays(5), today.minusDays(6));
        // A day with only new words is not a review day.
        repository.ensure(1, today.minusDays(4), 20, 5, 10);
        repository.addProgress(1, today.minusDays(4), 0, 0, 3, 6);
        // Another deck's reviews do not count.
        reviewedOn(repository, 2, today, today.minusDays(4));
        MutableClock clock = new MutableClock(today.atTime(9, 0));
        GoalService service = new GoalService(repository, clock);

        assertEquals(3, service.getTodayProgress(1).currentStreak(), "today is not over: yesterday's streak counts");
        service.recordReview(1, ReviewRating.GOOD, 0.9);
        assertEquals(4, service.getTodayProgress(1).currentStreak());
        assertEquals(1, service.getTodayProgress(2).currentStreak());
        assertEquals(2, service.progressFor(1, today.minusDays(5)).currentStreak(), "counted back from that day");

        clock.set(today.plusDays(1).atTime(23, 59, 59));
        assertEquals(4, service.getTodayProgress(1).currentStreak());
        clock.set(today.plusDays(2).atStartOfDay());
        assertEquals(0, service.getTodayProgress(1).currentStreak(), "a whole day without reviews ends it");
    }

    @Test
    void reviewJustAfterMidnightContinuesTheStreakFromTheEveningBefore() throws Exception {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("midnight.db"));
        LocalDate day = LocalDate.of(2026, 5, 28);
        MutableClock clock = new MutableClock(day.atTime(23, 59, 30));
        GoalService service = new GoalService(new GoalRepository(databaseManager), clock);

        assertEquals(1, service.recordReview(1, ReviewRating.GOOD, 0.9).progress().currentStreak());
        clock.set(day.plusDays(1).atTime(0, 0, 30));
        DailyGoalProgress beforeReview = service.getTodayProgress(1);
        assertEquals(0, beforeReview.reviewedCount());
        assertEquals(1, beforeReview.currentStreak());
        assertEquals(2, service.recordReview(1, ReviewRating.GOOD, 0.9).progress().currentStreak());
    }

    @Test
    void longStreakIsCountedInFull() throws Exception {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("long-streak.db"));
        GoalRepository repository = new GoalRepository(databaseManager);
        LocalDate today = LocalDate.of(2026, 5, 28);
        databaseManager.inTransaction(() -> {
            for (int i = 1; i <= 500; i++) {
                reviewedOn(repository, 1, today.minusDays(i));
            }
            return null;
        });
        GoalService service = new GoalService(repository, clockAt(today));

        assertEquals(500, service.getTodayProgress(1).currentStreak());
    }

    @Test
    void readingProgressWritesNothing() throws Exception {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("read-only.db"));
        GoalRepository repository = new GoalRepository(databaseManager);
        GoalService service = new GoalService(repository, clockAt(LocalDate.of(2026, 5, 28)));

        DailyGoalProgress progress = service.getTodayProgress(1);
        service.progressFor(1, LocalDate.of(2026, 5, 1));
        service.recordNewWords(1, 0);

        assertEquals(GoalService.DEFAULT_REVIEW_GOAL, progress.reviewGoal());
        assertEquals(GoalService.DEFAULT_NEW_WORD_GOAL, progress.newWordGoal());
        assertEquals(GoalService.DEFAULT_SESSION_GOAL, progress.sessionGoal());
        assertEquals(0, progress.reviewedCount());
        assertEquals(LocalDate.of(2026, 5, 28), progress.date());
        assertEquals(0, repository.countAll(), "no placeholder rows");

        // So the dashboard can still be read while a background import holds the write lock.
        try (Connection writer = DriverManager.getConnection("jdbc:sqlite:" + databaseManager.getDatabasePath());
             Statement statement = writer.createStatement()) {
            statement.execute("BEGIN IMMEDIATE");
            assertEquals(0, service.getTodayProgress(1).reviewedCount());
            statement.execute("ROLLBACK");
        }
    }

    private static void reviewedOn(GoalRepository repository, long deckId, LocalDate... dates) throws SQLException {
        for (LocalDate date : dates) {
            repository.ensure(deckId, date, 20, 5, 10);
            repository.addProgress(deckId, date, 1, 1, 0, 5);
        }
    }

    private GoalService serviceAt(LocalDate date) throws Exception {
        DatabaseManager databaseManager = databases.open(tempDir.resolve(date + ".db"));
        return new GoalService(new GoalRepository(databaseManager), clockAt(date));
    }

    private Clock clockAt(LocalDate date) {
        return Clock.fixed(Instant.parse(date + "T09:00:00Z"), ZoneId.of("UTC"));
    }

    /** A clock in UTC that a test moves forward. */
    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(LocalDateTime start) {
            set(start);
        }

        void set(LocalDateTime time) {
            now = time.toInstant(ZoneOffset.UTC);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
