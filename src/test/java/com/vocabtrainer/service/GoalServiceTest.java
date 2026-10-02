package com.vocabtrainer.service;

import com.vocabtrainer.TestClock;
import com.vocabtrainer.domain.DailyGoalProgress;
import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.GoalUpdate;
import com.vocabtrainer.domain.ReviewKind;
import com.vocabtrainer.domain.ReviewLog;
import com.vocabtrainer.domain.ReviewMode;
import com.vocabtrainer.domain.ReviewOutcome;
import com.vocabtrainer.domain.ReviewRating;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.AchievementRepository;
import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.DeckRepository;
import com.vocabtrainer.repository.GoalRepository;
import com.vocabtrainer.repository.ReviewLogRepository;
import com.vocabtrainer.repository.TestDatabases;
import com.vocabtrainer.repository.WordRepository;
import com.vocabtrainer.service.scheduling.StudyDay;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Daily goals, XP and the streak: the day's numbers come from the review logs, a new word counts on
 * its first review, imports earn nothing, and the streak counts study days in every deck (review
 * findings E8, E10, B8 and E11).
 */
class GoalServiceTest {
    private static final LocalDate DAY = LocalDate.of(2026, 5, 28);

    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    private DatabaseManager databaseManager;
    private DeckRepository decks;
    private WordRepository words;
    private ReviewLogRepository logs;
    private GoalRepository goalRepository;
    private TestClock clock;
    private GoalService goals;
    private Deck deck;

    @BeforeEach
    void openDatabase() throws SQLException {
        databaseManager = databases.open(tempDir.resolve("goals.db"));
        decks = new DeckRepository(databaseManager);
        words = new WordRepository(databaseManager);
        logs = new ReviewLogRepository(databaseManager);
        goalRepository = new GoalRepository(databaseManager);
        clock = new TestClock(DAY.atTime(9, 0));
        goals = new GoalService(goalRepository, logs, new StudyDay(), clock);
        deck = decks.ensureDefaultDeck();
    }

    @Test
    void importingTwoThousandWordsEarnsNoXpAndCountsNoNewWords() throws Exception {
        Path csv = tempDir.resolve("words.csv");
        List<String> lines = new ArrayList<>(List.of("english,chinese"));
        for (int i = 0; i < 2000; i++) {
            lines.add(letters(i) + ",释义");
        }
        Files.write(csv, lines, StandardCharsets.UTF_8);
        ImportExportService imports = new ImportExportService(words, new WordValidationService());

        assertEquals(2000, imports.importGreCsv(csv, deck.getId()).importedCount());
        imports.importBundledGreStarter(decks.create("Starter").getId());

        DailyGoalProgress progress = goals.getTodayProgress(deck.getId());
        assertEquals(0, progress.newWordsCount());
        assertEquals(0, progress.xpEarned());
        assertEquals(0, progress.totalXp());
        assertEquals(0, goalRepository.countAll(), "adding words writes no goal history");
    }

    @Test
    void aNewWordCountsOnTheDayOfItsFirstReview() throws Exception {
        ReviewService review = new ReviewService(words, logs, new SimilarityService(), new ReviewScheduler(), goals,
            new AchievementService(new AchievementRepository(databaseManager), goals, clock),
            clock, null, new Random(1));
        WordCard word = word(deck, "lucid");

        review.nextWord(deck.getId(), ReviewMode.EN_TO_ZH).orElseThrow();
        review.submitAnswer(word.getId(), "释义");
        ReviewOutcome first = review.rateCurrent(word.getId(), ReviewRating.GOOD);
        assertEquals(1, first.progress().newWordsCount(), "the first review of a new word");
        assertEquals(1, first.progress().reviewedCount());

        clock.advance(Duration.ofMinutes(11));
        review.nextWord(deck.getId(), ReviewMode.EN_TO_ZH).orElseThrow();
        review.submitAnswer(word.getId(), "释义");
        ReviewOutcome step = review.rateCurrent(word.getId(), ReviewRating.GOOD);
        assertEquals(1, step.progress().newWordsCount(), "a learning step of the same word is no new word");
        assertEquals(2, step.progress().reviewedCount());
        assertEquals(1, goals.getTodayProgress(deck.getId()).newWordsCount());
    }

    @Test
    void theDayCompletesWhenTheLogsReachBothGoals() throws Exception {
        WordCard word = word(deck, "abate");
        int xp = 0;
        for (int i = 1; i < 20; i++) {
            // Five new words, then reviews; one of them wrong.
            GoalUpdate update = record(deck, word, DAY.atTime(9, i), i <= 5 ? ReviewKind.LEARN : ReviewKind.REVIEW,
                i == 6 ? ReviewRating.AGAIN : ReviewRating.GOOD);
            assertFalse(update.dailyGoalCompleted(), "after " + i + " reviews");
            xp += update.xpEarned();
        }
        GoalUpdate last = record(deck, word, DAY.atTime(9, 30), ReviewKind.REVIEW, ReviewRating.GOOD);

        assertTrue(last.dailyGoalCompleted());
        DailyGoalProgress progress = goals.getTodayProgress(deck.getId());
        assertEquals(20, progress.reviewedCount());
        assertEquals(19, progress.correctCount());
        assertEquals(5, progress.newWordsCount());
        assertTrue(progress.completed());
        assertEquals(xp + last.xpEarned(), progress.xpEarned());
        assertFalse(record(deck, word, DAY.atTime(9, 40), ReviewKind.REVIEW, ReviewRating.GOOD).dailyGoalCompleted(),
            "a day completes once");
    }

    @Test
    void practiceEarnsXpButCountsTowardsNothing() throws Exception {
        WordCard word = word(deck, "lucid");
        ReviewLog practice = logs.insert(log(word, DAY.atTime(9, 0), ReviewKind.PRACTICE, ReviewRating.GOOD));

        GoalUpdate update = goals.recordPractice(deck.getId(), practice);

        assertTrue(update.xpEarned() > 0);
        assertEquals(0, update.progress().reviewedCount());
        assertEquals(0, update.progress().currentStreak());
        assertEquals(update.xpEarned(), goals.totalXp(deck.getId()));
        assertEquals(0, goals.reviewCount(deck.getId(), 100));
    }

    @Test
    void deletingAReviewedWordTakesItsReviewsOutOfTheDay() throws Exception {
        WordCard kept = word(deck, "abate");
        WordCard deleted = word(deck, "lucid");
        record(deck, kept, DAY.atTime(9, 0), ReviewKind.REVIEW, ReviewRating.GOOD);
        record(deck, deleted, DAY.atTime(9, 1), ReviewKind.REVIEW, ReviewRating.AGAIN);

        words.deleteById(deleted.getId());

        DailyGoalProgress progress = goals.getTodayProgress(deck.getId());
        assertEquals(1, progress.reviewedCount());
        assertEquals(1.0, progress.accuracy());
    }

    @Test
    void theStreakCountsStudyDaysInEveryDeck() throws Exception {
        Deck other = decks.create("TOEFL");
        WordCard gre = word(deck, "abate");
        WordCard toefl = word(other, "laud");
        // Mon in one deck, Tue at 1:30 am (still Mon's study day) and Tue evening in the other.
        record(deck, gre, DAY.minusDays(2).atTime(22, 0), ReviewKind.REVIEW, ReviewRating.GOOD);
        record(other, toefl, DAY.minusDays(1).atTime(1, 30), ReviewKind.REVIEW, ReviewRating.GOOD);
        record(other, toefl, DAY.minusDays(1).atTime(20, 0), ReviewKind.REVIEW, ReviewRating.GOOD);

        assertEquals(2, goals.getTodayProgress(deck.getId()).currentStreak(),
            "two study days, one in each deck; today has no review yet");
        assertEquals(2, goals.getTodayProgress(other.getId()).currentStreak(), "the same streak in every deck");

        clock.set(DAY.plusDays(1).atTime(3, 59));
        assertEquals(3, record(deck, gre, LocalDateTime.now(clock), ReviewKind.REVIEW, ReviewRating.GOOD)
            .progress().currentStreak(), "3:59 am still belongs to the study day before");
        clock.set(DAY.plusDays(1).atTime(4, 0));
        assertEquals(3, goals.getTodayProgress(deck.getId()).currentStreak(), "a new study day has begun");
        clock.set(DAY.plusDays(2).atTime(4, 0));
        assertEquals(0, goals.getTodayProgress(deck.getId()).currentStreak(), "a whole study day without reviews");
    }

    @Test
    void theStreakEndsAtTheFirstDayWithoutReviewsAndIgnoresPractice() throws Exception {
        WordCard word = word(deck, "abate");
        for (int daysAgo : new int[] {1, 2, 3, 5, 6}) {
            record(deck, word, DAY.minusDays(daysAgo).atTime(12, 0), ReviewKind.REVIEW, ReviewRating.GOOD);
        }
        // A day with only a practice is not a review day.
        logs.insert(log(word, DAY.minusDays(4).atTime(12, 0), ReviewKind.PRACTICE, ReviewRating.GOOD));

        assertEquals(3, goals.getTodayProgress(deck.getId()).currentStreak(), "today is not over");
        record(deck, word, DAY.atTime(9, 30), ReviewKind.REVIEW, ReviewRating.AGAIN);
        assertEquals(4, goals.getTodayProgress(deck.getId()).currentStreak(), "a failed review is still a review");
        assertEquals(2, goals.progressFor(deck.getId(), DAY.minusDays(5)).currentStreak(), "counted back from that day");
    }

    @Test
    void aLongStreakIsCountedInFull() throws Exception {
        WordCard word = word(deck, "abate");
        databaseManager.inTransaction(() -> {
            for (int i = 1; i <= 500; i++) {
                logs.insert(log(word, DAY.minusDays(i).atTime(20, 0), ReviewKind.REVIEW, ReviewRating.GOOD));
            }
            return null;
        });

        long started = System.nanoTime();
        assertEquals(500, goals.getTodayProgress(deck.getId()).currentStreak());
        long millis = (System.nanoTime() - started) / 1_000_000;
        assertTrue(millis < 2_000, "500 days took " + millis + " ms");
    }

    @Test
    void readingProgressWritesNothing() throws Exception {
        DailyGoalProgress progress = goals.getTodayProgress(deck.getId());
        goals.progressFor(deck.getId(), DAY.minusDays(30));

        assertEquals(GoalService.DEFAULT_REVIEW_GOAL, progress.reviewGoal());
        assertEquals(GoalService.DEFAULT_NEW_WORD_GOAL, progress.newWordGoal());
        assertEquals(GoalService.DEFAULT_SESSION_GOAL, progress.sessionGoal());
        assertEquals(0, progress.reviewedCount());
        assertEquals(DAY, progress.date());
        assertEquals(0, goalRepository.countAll(), "no placeholder rows");

        // So the dashboard can still be read while a background import holds the write lock.
        try (Connection writer = DriverManager.getConnection("jdbc:sqlite:" + databaseManager.getDatabasePath());
             Statement statement = writer.createStatement()) {
            statement.execute("BEGIN IMMEDIATE");
            assertEquals(0, goals.getTodayProgress(deck.getId()).reviewedCount());
            statement.execute("ROLLBACK");
        }
    }

    /** A new word, due from the test clock's morning (createNew dates it by the wall clock). */
    private WordCard word(Deck target, String english) throws SQLException {
        WordCard word = WordCard.createNew(target.getId(), english, "释义");
        word.setAddedAt(DAY.atTime(8, 0));
        word.setNextReviewAt(DAY.atTime(8, 0));
        return words.save(word);
    }

    /** Saves a log of the word at {@code at} and records it, as a rating does. */
    private GoalUpdate record(Deck target, WordCard word, LocalDateTime at, ReviewKind kind, ReviewRating rating)
        throws SQLException {
        return goals.recordReview(target.getId(), logs.insert(log(word, at, kind, rating)));
    }

    private static ReviewLog log(WordCard word, LocalDateTime at, ReviewKind kind, ReviewRating rating) {
        double similarity = rating == ReviewRating.AGAIN ? 0.0 : 1.0;
        return new ReviewLog(0, word.getId(), at, "释义", "释义", similarity, rating, 1000, kind, ReviewMode.EN_TO_ZH);
    }

    /** "aaa", "aab", ...: distinct English words made of letters only. */
    private static String letters(int number) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            text.insert(0, (char) ('a' + number % 26));
            number /= 26;
        }
        return "word" + text;
    }
}
