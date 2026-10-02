package com.vocabtrainer.service;

import com.vocabtrainer.TestClock;
import com.vocabtrainer.domain.CardState;
import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.ReviewMode;
import com.vocabtrainer.domain.ReviewRating;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.DeckRepository;
import com.vocabtrainer.repository.GoalRepository;
import com.vocabtrainer.repository.ReviewLogRepository;
import com.vocabtrainer.repository.SettingsRepository;
import com.vocabtrainer.repository.TestDatabases;
import com.vocabtrainer.repository.WordRepository;
import com.vocabtrainer.service.scheduling.SchedulingOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The desired retention and the day rollover hour, changed on the Settings tab, are saved and apply
 * to the running app at once: the next rating and interval preview, what is due today and which
 * study day it is.
 */
class SchedulingSettingsTest {
    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    private final TestClock clock = new TestClock(LocalDateTime.of(2026, 3, 11, 10, 0));
    private DatabaseManager databaseManager;
    private Deck deck;
    private WordRepository words;
    private SettingsService settings;
    private ReviewScheduler scheduler;
    private SchedulingSettings scheduling;
    private StatsService stats;
    private GoalService goals;
    private ReviewService review;

    @BeforeEach
    void setUp() throws SQLException {
        databaseManager = databases.open(tempDir.resolve("scheduling.db"));
        deck = new DeckRepository(databaseManager).ensureDefaultDeck();
        words = new WordRepository(databaseManager);
        ReviewLogRepository logs = new ReviewLogRepository(databaseManager);
        settings = new SettingsService(new SettingsRepository(databaseManager));
        scheduler = new ReviewScheduler(settings.getSchedulingOptions());
        scheduling = new SchedulingSettings(settings, scheduler);
        ReviewSettings reviewSettings = new ReviewSettings(settings);
        // Wired like the app: the services read the scheduler's study day at every call.
        stats = new StatsService(words, logs, clock, scheduler::studyDay, reviewSettings);
        goals = new GoalService(new GoalRepository(databaseManager), logs,
            new GoalSettings(settings, reviewSettings), scheduler::studyDay, clock);
        review = new ReviewService(words, logs, new SimilarityService(), scheduler, clock);
    }

    @Test
    void aHigherRetentionShortensTheNextIntervalsAndIsKeptForTheNextStart() throws SQLException {
        WordCard lucid = words.insert(reviewCard("lucid", LocalDateTime.of(2026, 3, 1, 10, 0),
            LocalDateTime.of(2026, 3, 11, 9, 0)));
        int atDefault = goodInterval(lucid);

        scheduling.saveDesiredRetention(0.95);

        assertEquals(0.95, scheduler.options().desiredRetention());
        assertEquals(0.95, scheduling.options().desiredRetention());
        int atHigher = goodInterval(lucid);
        assertTrue(atHigher < atDefault, atHigher + " days at 95%, " + atDefault + " at 90%");

        scheduling.saveDesiredRetention(0.8);
        assertTrue(goodInterval(lucid) > atDefault, "a lower retention lengthens the interval");

        SettingsService restarted = new SettingsService(new SettingsRepository(databaseManager));
        assertEquals(0.8, restarted.getSchedulingOptions().desiredRetention(), "read again at the next start");
    }

    @Test
    void theNextRatingUsesTheNewRetention() throws SQLException {
        WordCard lucid = words.insert(reviewCard("lucid", LocalDateTime.of(2026, 3, 1, 10, 0),
            LocalDateTime.of(2026, 3, 11, 9, 0)));
        WordCard abate = words.insert(reviewCard("abate", LocalDateTime.of(2026, 3, 1, 10, 0),
            LocalDateTime.of(2026, 3, 11, 9, 0)));
        review.startSession(deck.getId(), ReviewMode.EN_TO_ZH, 0);
        review.submitAnswer(lucid.getId(), "释义");
        review.rateCurrent(lucid.getId(), ReviewRating.GOOD);

        scheduling.saveDesiredRetention(0.95);
        review.submitAnswer(abate.getId(), "释义");
        int previewed = review.previewRatings(abate.getId()).get(ReviewRating.GOOD).intervalDays();
        review.rateCurrent(abate.getId(), ReviewRating.GOOD);

        int atDefault = words.findById(lucid.getId()).orElseThrow().getIntervalDays();
        int atHigher = words.findById(abate.getId()).orElseThrow().getIntervalDays();
        assertEquals(previewed, atHigher, "the preview showed what the rating did");
        assertTrue(atHigher < atDefault, atHigher + " days at 95%, " + atDefault + " at 90%");
    }

    @Test
    void aNewRolloverHourChangesWhatIsDueTodayAndWhichStudyDayItIs() throws SQLException {
        // Due at 2 am tomorrow: today's with the rollover at 4 am, tomorrow's with it at midnight.
        words.insert(reviewCard("lucid", LocalDateTime.of(2026, 3, 10, 10, 0), LocalDateTime.of(2026, 3, 12, 2, 0)));
        assertEquals(1, stats.dashboardStats(deck.getId()).dueReviews());
        assertEquals(LocalDate.of(2026, 3, 11), goals.today());

        scheduling.saveDayRolloverHour(0);

        assertEquals(0, scheduler.options().dayRolloverHour());
        assertEquals(0, stats.dashboardStats(deck.getId()).dueReviews());
        assertEquals(0, stats.overdueCount(deck.getId()));
        assertEquals(0, review.queueCounts(deck.getId()).dueReviews());

        scheduling.saveDayRolloverHour(3);
        assertEquals(1, stats.dashboardStats(deck.getId()).dueReviews());

        // At 10 am with the day starting at noon, it is still the day before.
        scheduling.saveDayRolloverHour(12);
        assertEquals(LocalDate.of(2026, 3, 10), goals.today());
        assertEquals(12, new SettingsService(new SettingsRepository(databaseManager))
            .getSchedulingOptions().dayRolloverHour(), "read again at the next start");
    }

    @Test
    void valuesOutOfRangeAreRefusedAndNothingChanges() {
        assertThrows(IllegalArgumentException.class, () -> scheduling.saveDesiredRetention(0.99));
        assertThrows(IllegalArgumentException.class, () -> scheduling.saveDesiredRetention(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> scheduling.saveDayRolloverHour(24));
        assertThrows(IllegalArgumentException.class, () -> scheduling.saveDayRolloverHour(-1));

        assertEquals(SchedulingOptions.defaults(), scheduler.options());
        assertTrue(settings.get(SettingsService.DESIRED_RETENTION_KEY).isEmpty());
        assertTrue(settings.get(SettingsService.DAY_ROLLOVER_HOUR_KEY).isEmpty());
    }

    private int goodInterval(WordCard word) {
        return scheduler.intervals(word, clock.now()).get(ReviewRating.GOOD).intervalDays();
    }

    private WordCard reviewCard(String english, LocalDateTime lastReviewed, LocalDateTime due) {
        WordCard card = WordCard.createNew(deck.getId(), english, "释义");
        card.setState(CardState.REVIEW);
        card.setStability(10);
        card.setDifficulty(5);
        card.setRepetitions(2);
        card.setConsecutiveCorrect(2);
        card.setIntervalDays(10);
        card.setLastReviewedAt(lastReviewed);
        card.setNextReviewAt(due);
        return card;
    }
}
