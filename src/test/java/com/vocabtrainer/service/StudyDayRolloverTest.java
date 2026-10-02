package com.vocabtrainer.service;

import com.vocabtrainer.TestClock;
import com.vocabtrainer.domain.CardState;
import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.ReviewMode;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.DeckRepository;
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
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** "Due today" runs until the next 4 am rollover (review finding A9). */
class StudyDayRolloverTest {
    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    private final TestClock clock = new TestClock(LocalDateTime.of(2026, 3, 11, 3, 59));
    private DatabaseManager databaseManager;
    private Deck deck;
    private WordRepository words;
    private StatsService stats;
    private ReviewService review;

    @BeforeEach
    void setUp() throws SQLException {
        databaseManager = databases.open(tempDir.resolve("rollover.db"));
        deck = new DeckRepository(databaseManager).ensureDefaultDeck();
        words = new WordRepository(databaseManager);
        ReviewLogRepository logs = new ReviewLogRepository(databaseManager);
        ReviewScheduler scheduler = new ReviewScheduler();
        stats = new StatsService(words, logs, clock, scheduler.studyDay());
        review = new ReviewService(words, logs, new SimilarityService(), scheduler, clock);
        review.startSession(deck.getId(), ReviewMode.EN_TO_ZH, 0);
    }

    @Test
    void aWordDueTomorrowIsDueFromFourInTheMorning() throws SQLException {
        // Reviewed on the evening of the 10th with a 1-day interval: due when the 11th starts.
        words.save(reviewCard("lucid", LocalDateTime.of(2026, 3, 11, 4, 0)));
        // An older version stored due times with the time of day of the review.
        words.save(reviewCard("abate", LocalDateTime.of(2026, 3, 11, 21, 30)));

        clock.set(LocalDateTime.of(2026, 3, 11, 3, 59));
        assertEquals(0, stats.dashboardStats(deck.getId()).dueToday());
        assertTrue(review.nextWord(deck.getId(), ReviewMode.EN_TO_ZH).isEmpty());

        clock.set(LocalDateTime.of(2026, 3, 11, 4, 1));
        assertEquals(2, stats.dashboardStats(deck.getId()).dueToday());
        assertEquals(2, stats.overdueCount(deck.getId()));
        assertTrue(review.nextWord(deck.getId(), ReviewMode.EN_TO_ZH).isPresent());
    }

    @Test
    void aLearningStepIsDueAtItsExactTime() throws SQLException {
        WordCard learning = reviewCard("laud", LocalDateTime.of(2026, 3, 11, 9, 10));
        learning.setState(CardState.LEARNING);
        words.save(learning);

        clock.set(LocalDateTime.of(2026, 3, 11, 9, 0));
        assertEquals(0, stats.dashboardStats(deck.getId()).dueToday());
        clock.set(LocalDateTime.of(2026, 3, 11, 9, 10));
        assertEquals(1, stats.dashboardStats(deck.getId()).dueToday());
    }

    @Test
    void theRolloverHourAndDesiredRetentionAreSettings() throws SQLException {
        SettingsService settings = new SettingsService(new SettingsRepository(databaseManager));
        assertEquals(SchedulingOptions.defaults(), settings.getSchedulingOptions());

        settings.save(SettingsService.DAY_ROLLOVER_HOUR_KEY, "0");
        settings.save(SettingsService.DESIRED_RETENTION_KEY, "0.85");
        SchedulingOptions options = settings.getSchedulingOptions();
        assertEquals(0, options.dayRolloverHour());
        assertEquals(0.85, options.desiredRetention());

        // Values that are not valid are ignored.
        settings.save(SettingsService.DAY_ROLLOVER_HOUR_KEY, "25");
        settings.save(SettingsService.DESIRED_RETENTION_KEY, "always");
        assertEquals(SchedulingOptions.defaults(), settings.getSchedulingOptions());
    }

    private WordCard reviewCard(String english, LocalDateTime due) {
        WordCard card = WordCard.createNew(deck.getId(), english, "释义");
        card.setState(CardState.REVIEW);
        card.setStability(1);
        card.setDifficulty(5);
        card.setRepetitions(1);
        card.setConsecutiveCorrect(1);
        card.setLastReviewedAt(LocalDateTime.of(2026, 3, 10, 21, 30));
        card.setNextReviewAt(due);
        return card;
    }
}
