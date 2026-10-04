package com.vocabtrainer.service;

import com.vocabtrainer.TestClock;
import com.vocabtrainer.domain.DailyGoalProgress;
import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.GoalTargets;
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
import com.vocabtrainer.repository.SettingsRepository;
import com.vocabtrainer.repository.TestDatabases;
import com.vocabtrainer.repository.WordRepository;
import com.vocabtrainer.service.scheduling.StudyDay;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
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
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

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
    private SettingsService settings;
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
        settings = new SettingsService(new SettingsRepository(databaseManager));
        clock = new TestClock(DAY.atTime(9, 0));
        goals = new GoalService(goalRepository, logs, new GoalSettings(settings, new ReviewSettings(settings)),
            new StudyDay(), clock);
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
        goals.settings().saveDefaults(new GoalTargets(2, 1));
        WordCard first = word(deck, "abate");
        WordCard second = word(deck, "lucid");

        GoalUpdate learn = record(deck, first, DAY.atTime(9, 0), ReviewKind.LEARN, ReviewRating.GOOD);
        assertFalse(learn.dailyGoalCompleted());
        assertEquals(1, learn.progress().newWordsCount());
        GoalUpdate review = record(deck, second, DAY.atTime(9, 5), ReviewKind.REVIEW, ReviewRating.AGAIN);

        assertTrue(review.dailyGoalCompleted());
        DailyGoalProgress progress = goals.getTodayProgress(deck.getId());
        assertEquals(2, progress.reviewedCount());
        assertEquals(1, progress.correctCount());
        assertEquals(0.5, progress.accuracy());
        assertTrue(progress.completed());
        assertEquals(learn.xpEarned() + review.xpEarned(), progress.xpEarned());
        assertFalse(record(deck, second, DAY.atTime(9, 10), ReviewKind.REVIEW, ReviewRating.GOOD).dailyGoalCompleted(),
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
    void theStreakIsReadWithOneQueryWhateverItsLengthAndARatingCountsItOnce() throws Exception {
        CountingDatabaseManager counting = databases.track(new CountingDatabaseManager(tempDir.resolve("count.db")));
        counting.initialize();
        WordRepository countedWords = new WordRepository(counting);
        ReviewLogRepository countedLogs = new ReviewLogRepository(counting);
        Deck countedDeck = new DeckRepository(counting).ensureDefaultDeck();
        GoalService countedGoals = new GoalService(new GoalRepository(counting), countedLogs, clock);
        WordCard reviewed = WordCard.createNew(countedDeck.getId(), "abate", "释义");
        reviewed.setNextReviewAt(DAY.atTime(8, 0));
        countedWords.save(reviewed);
        WordCard fresh = WordCard.createNew(countedDeck.getId(), "lucid", "释义");
        fresh.setAddedAt(DAY.atTime(8, 0));
        fresh.setNextReviewAt(DAY.atTime(8, 0));
        countedWords.save(fresh);
        counting.inTransaction(() -> {
            for (int i = 1; i <= 400; i++) {
                countedLogs.insert(log(reviewed, DAY.minusDays(i).atTime(20, 0), ReviewKind.REVIEW, ReviewRating.GOOD));
            }
            return null;
        });

        counting.statements.clear();
        assertEquals(400, countedGoals.getTodayProgress(countedDeck.getId()).currentStreak());
        assertEquals(1, counting.count("WITH RECURSIVE"), counting.statements.toString());
        assertTrue(counting.statements.size() <= 5, "reading progress took " + counting.statements);

        ReviewService review = new ReviewService(countedWords, countedLogs, new SimilarityService(),
            new ReviewScheduler(), countedGoals,
            new AchievementService(new AchievementRepository(counting), countedGoals, clock), clock, null, new Random(1));
        review.nextWord(countedDeck.getId(), ReviewMode.EN_TO_ZH).orElseThrow();
        review.submitAnswer(fresh.getId(), "释义");
        counting.statements.clear();
        ReviewOutcome outcome = review.rateCurrent(fresh.getId(), ReviewRating.GOOD);

        assertEquals(401, outcome.progress().currentStreak());
        assertEquals(1, counting.count("WITH RECURSIVE"), "the streak is counted once per rating");
        assertTrue(counting.statements.size() < 40, "a rating took " + counting.statements.size() + " statements");
        DailyGoalProgress reread = countedGoals.getTodayProgress(countedDeck.getId());
        assertEquals(reread, outcome.progress(), "the rating's progress, badges' XP included, is what a read gives");
        assertFalse(outcome.unlockedAchievements().isEmpty(), "the first-review and streak badges");
        assertEquals(outcome.xpEarned(), outcome.progress().totalXp(), "the badges' XP is in the progress");
    }

    @Test
    void theStreakFollowsTheStudyDaysOfAnyHistoryAsTheDayByDayWalkDid() throws Exception {
        WordCard word = word(deck, "abate");
        Random random = new Random(7);
        List<ReviewLog> history = new ArrayList<>();
        // Days with and without reviews, some with only a practice or a word marked as known.
        for (int daysAgo = 0; daysAgo < 60; daysAgo++) {
            if (random.nextInt(5) == 0) {
                continue;
            }
            LocalDateTime at = DAY.minusDays(daysAgo).atTime(random.nextInt(24), random.nextInt(60), random.nextInt(60));
            ReviewKind kind = switch (random.nextInt(6)) {
                case 0 -> ReviewKind.PRACTICE;
                case 1 -> ReviewKind.KNOWN;
                default -> ReviewKind.REVIEW;
            };
            history.add(logs.insert(log(word, at, kind, ReviewRating.GOOD)));
        }
        for (int rollover : new int[] {0, 4, 23}) {
            StudyDay studyDay = new StudyDay(rollover);
            GoalService byRollover = new GoalService(goalRepository, logs, GoalSettings.inMemory(), studyDay, clock);
            for (int daysAgo = -1; daysAgo < 62; daysAgo++) {
                LocalDate day = DAY.minusDays(daysAgo);
                assertEquals(walkedStreak(history, studyDay, day),
                    byRollover.progressFor(deck.getId(), day).currentStreak(), "rollover " + rollover + ", " + day);
            }
        }
    }

    @Test
    void todayUsesTheCurrentGoalsAndAPastDayTheGoalsItWasStudiedWith() throws Exception {
        Deck other = decks.create("TOEFL");
        WordCard word = word(deck, "abate");
        record(deck, word, DAY.minusDays(1).atTime(12, 0), ReviewKind.REVIEW, ReviewRating.GOOD);

        goals.settings().saveDefaults(new GoalTargets(40, 10));
        goals.settings().saveDeckGoals(other.getId(), new GoalTargets(5, 0));
        goals.settings().saveSessionGoal(30);

        DailyGoalProgress today = goals.getTodayProgress(deck.getId());
        assertEquals(40, today.reviewGoal());
        assertEquals(10, today.newWordGoal());
        assertEquals(30, today.sessionGoal());
        assertEquals(5, goals.getTodayProgress(other.getId()).reviewGoal(), "the deck's own goals");
        DailyGoalProgress yesterday = goals.progressFor(deck.getId(), DAY.minusDays(1));
        assertEquals(GoalService.DEFAULT_REVIEW_GOAL, yesterday.reviewGoal());
        assertEquals(GoalService.DEFAULT_NEW_WORD_GOAL, yesterday.newWordGoal());
        assertEquals(1, yesterday.reviewedCount());

        record(deck, word, DAY.atTime(12, 0), ReviewKind.REVIEW, ReviewRating.GOOD);
        GoalRepository.GoalRow row = goalRepository.find(deck.getId(), DAY).orElseThrow();
        assertEquals(List.of(40, 10, 30), List.of(row.reviewGoal(), row.newWordGoal(), row.sessionGoal()),
            "the day's row keeps the goals it was studied with");
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

    /**
     * The streak as the earlier versions counted it, one lookup of the newest review before the
     * start of a day at a time, here over the logs in memory.
     */
    private static int walkedStreak(List<ReviewLog> history, StudyDay studyDay, LocalDate day) {
        int days = 0;
        LocalDate expected = day;
        LocalDateTime before = studyDay.start(day.plusDays(1));
        while (true) {
            LocalDateTime limit = before;
            Optional<LocalDateTime> latest = history.stream()
                .filter(log -> log.getKind() != ReviewKind.PRACTICE && log.getKind() != ReviewKind.KNOWN)
                .map(ReviewLog::getReviewedAt)
                .filter(at -> at.isBefore(limit))
                .max(LocalDateTime::compareTo);
            if (latest.isEmpty()) {
                return days;
            }
            LocalDate reviewed = studyDay.of(latest.get());
            if (days == 0 && reviewed.equals(day.minusDays(1))) {
                expected = reviewed;
            }
            if (!reviewed.equals(expected)) {
                return days;
            }
            days++;
            expected = reviewed.minusDays(1);
            before = studyDay.start(reviewed);
        }
    }

    /** Records the SQL of every statement the repositories prepare through {@link #getConnection()}. */
    private static final class CountingDatabaseManager extends DatabaseManager {
        private final List<String> statements = new CopyOnWriteArrayList<>();

        private CountingDatabaseManager(Path file) {
            super(file);
        }

        long count(String fragment) {
            return statements.stream().filter(sql -> sql.contains(fragment)).count();
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection connection = super.getConnection();
            return (Connection) Proxy.newProxyInstance(GoalServiceTest.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    if (method.getName().equals("prepareStatement")) {
                        statements.add((String) args[0]);
                    }
                    try {
                        return method.invoke(connection, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        }
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
