package com.vocabtrainer.service;

import com.vocabtrainer.TestClock;
import com.vocabtrainer.domain.CardState;
import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.ReviewMode;
import com.vocabtrainer.domain.ReviewRating;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.domain.WorkloadDay;
import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.DeckRepository;
import com.vocabtrainer.repository.ReviewLogRepository;
import com.vocabtrainer.repository.SettingsRepository;
import com.vocabtrainer.repository.TestDatabases;
import com.vocabtrainer.repository.WordRepository;
import com.vocabtrainer.service.scheduling.SchedulingOptions;
import com.vocabtrainer.service.scheduling.StudyDay;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The workload forecast (review finding G5): reviews due per study day, counted with one grouped
 * query, agree with counting every word one by one, whatever the rollover hour; new words follow
 * the new-cards-per-day limit.
 */
class WorkloadForecastTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 2, 10, 30);
    private static final CardState[] STATES = CardState.values();

    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    private final TestClock clock = new TestClock(NOW);
    private Deck deck;
    private Deck other;
    private WordRepository words;
    private ReviewLogRepository logs;
    private ReviewSettings reviewSettings;

    @BeforeEach
    void setUp() throws SQLException {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("forecast.db"));
        DeckRepository decks = new DeckRepository(databaseManager);
        deck = decks.ensureDefaultDeck();
        other = decks.create("Other");
        words = new WordRepository(databaseManager);
        logs = new ReviewLogRepository(databaseManager);
        reviewSettings = new ReviewSettings(new SettingsService(new SettingsRepository(databaseManager)));
    }

    @Test
    void reviewsPerStudyDayAgreeWithCountingEachWord() throws SQLException {
        Random random = new Random(42);
        List<WordCard> all = new ArrayList<>();
        for (int i = 0; i < 600; i++) {
            WordCard word = WordCard.createNew(i % 7 == 0 ? other.getId() : deck.getId(), "word" + i, "释义");
            word.setState(STATES[random.nextInt(STATES.length)]);
            // From 10 days overdue to 45 days ahead, at any minute of the day, around every rollover hour.
            word.setNextReviewAt(NOW.minusDays(10).plusMinutes(random.nextInt(55 * 24 * 60)));
            word.setArchived(i % 13 == 0);
            all.add(words.insert(word));
        }

        for (int rolloverHour : new int[] {4, 0, 23}) {
            StudyDay studyDay = new StudyDay(rolloverHour);
            StatsService stats = new StatsService(words, logs, clock, studyDay, reviewSettings);
            for (int days : new int[] {14, 30}) {
                List<WorkloadDay> forecast = stats.workloadForecast(deck.getId(), days);
                List<Integer> expected = bruteForce(all, studyDay, days);
                assertEquals(days, forecast.size());
                assertEquals(studyDay.of(NOW), forecast.get(0).date(), "today first");
                for (int i = 0; i < days; i++) {
                    assertEquals(studyDay.of(NOW).plusDays(i), forecast.get(i).date());
                    assertEquals(expected.get(i), forecast.get(i).reviews(),
                        "day " + i + " with the day starting at " + rolloverHour + ":00");
                }
            }
        }
    }

    @Test
    void aReviewBeforeTheRolloverBelongsToThePreviousStudyDay() throws SQLException {
        words.insert(reviewCard("lucid", LocalDateTime.of(2026, 10, 4, 3, 59)));
        words.insert(reviewCard("abate", LocalDateTime.of(2026, 10, 4, 4, 0)));
        words.insert(reviewCard("laud", LocalDateTime.of(2026, 9, 20, 12, 0)));

        StatsService stats = new StatsService(words, logs, clock, new StudyDay(), reviewSettings);
        List<WorkloadDay> forecast = stats.workloadForecast(deck.getId(), 14);

        assertEquals(1, forecast.get(0).reviews(), "overdue counts for today");
        assertEquals(1, forecast.get(1).reviews(), "3:59 on the 4th is still the 3rd");
        assertEquals(1, forecast.get(2).reviews());
        assertEquals(3, forecast.stream().mapToInt(WorkloadDay::reviews).sum());
    }

    @Test
    void newWordsFollowTheDailyLimitUntilNoneAreLeft() throws SQLException {
        for (int i = 0; i < 47; i++) {
            WordCard word = WordCard.createNew(deck.getId(), "new" + (char) ('a' + i / 26) + (char) ('a' + i % 26), "释义");
            word.setNextReviewAt(NOW.minusDays(1));
            words.insert(word);
        }
        ReviewService review = new ReviewService(words, logs, new SimilarityService(),
            new ReviewScheduler(SchedulingOptions.defaults()), null, null, clock, reviewSettings, new Random(1));
        review.startSession(deck.getId(), ReviewMode.EN_TO_ZH, 0);
        // Three new words are introduced today.
        for (int i = 0; i < 3; i++) {
            WordCard card = review.nextWord(deck.getId(), ReviewMode.EN_TO_ZH).orElseThrow();
            review.submitAnswer(card.getId(), "释义", ReviewMode.EN_TO_ZH, NOW);
            review.rateCurrent(card.getId(), ReviewRating.GOOD);
        }
        reviewSettings.saveNewCardsPerDay(deck.getId(), 10);

        StatsService stats = new StatsService(words, logs, clock, new StudyDay(), reviewSettings);
        List<WorkloadDay> forecast = stats.workloadForecast(deck.getId(), 14);

        assertEquals(List.of(7, 10, 10, 10, 7, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            forecast.stream().map(WorkloadDay::newWords).toList(), "what is left of today's 10, then 10 a day");
        assertEquals(3, forecast.get(0).reviews(), "the three learning cards are due today");
    }

    private List<Integer> bruteForce(List<WordCard> all, StudyDay studyDay, int days) {
        LocalDate today = studyDay.of(NOW);
        List<Integer> counts = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            counts.add(0);
        }
        for (WordCard word : all) {
            if (word.getDeckId() != deck.getId() || word.isArchived() || word.getState() == CardState.NEW) {
                continue;
            }
            LocalDate day = studyDay.of(word.getNextReviewAt());
            int index = (int) Math.max(0, day.toEpochDay() - today.toEpochDay());
            if (index < days) {
                counts.set(index, counts.get(index) + 1);
            }
        }
        return counts;
    }

    private WordCard reviewCard(String english, LocalDateTime due) {
        WordCard card = WordCard.createNew(deck.getId(), english, "释义");
        card.setState(CardState.REVIEW);
        card.setStability(5);
        card.setDifficulty(5);
        card.setLastReviewedAt(NOW.minusDays(5));
        card.setNextReviewAt(due);
        return card;
    }
}
