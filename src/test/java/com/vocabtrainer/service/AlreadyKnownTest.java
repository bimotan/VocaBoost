package com.vocabtrainer.service;

import com.vocabtrainer.TestClock;
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
import com.vocabtrainer.service.scheduling.Fsrs;
import com.vocabtrainer.service.scheduling.SchedulingOptions;
import com.vocabtrainer.service.scheduling.StudyDay;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** "Already known" puts a new word straight into review, without counting as a review (review finding G3). */
class AlreadyKnownTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 5, 28, 9, 0);

    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    private final TestClock clock = new TestClock(NOW);
    private DatabaseManager databaseManager;
    private DeckRepository decks;
    private GoalRepository goals;
    private WordRepository words;
    private ReviewLogRepository logs;
    private AchievementRepository achievements;
    private GoalService goalService;
    private StatsService stats;
    private ReviewScheduler scheduler;
    private ReviewService service;
    private Deck deck;

    @BeforeEach
    void setUp() throws SQLException {
        databaseManager = databases.open(tempDir.resolve("known.db"));
        decks = new DeckRepository(databaseManager);
        goals = new GoalRepository(databaseManager);
        words = new WordRepository(databaseManager);
        logs = new ReviewLogRepository(databaseManager);
        achievements = new AchievementRepository(databaseManager);
        scheduler = new ReviewScheduler(SchedulingOptions.defaults(), new Random(3));
        goalService = new GoalService(goals, logs, GoalSettings.inMemory(), scheduler.studyDay(), clock);
        service = new ReviewService(words, logs, new SimilarityService(), scheduler, goalService,
            new AchievementService(achievements, goalService, clock), clock, null, new Random(3));
        stats = new StatsService(words, logs, clock, scheduler.studyDay());
        deck = decks.ensureDefaultDeck();
        service.startSession(deck.getId(), ReviewMode.EN_TO_ZH, 20);
    }

    @Test
    void aKnownWordGoesIntoReviewForAboutTwoMonths() throws SQLException {
        WordCard word = words.insert(newCard("abate", "减弱"));
        assertEquals(word.getId(), service.nextWord(deck.getId(), ReviewMode.EN_TO_ZH).orElseThrow().getId());

        WordCard known = service.markKnown(word.getId());

        WordCard stored = words.findById(word.getId()).orElseThrow();
        assertEquals(CardState.REVIEW, stored.getState());
        assertEquals(ReviewScheduler.KNOWN_STABILITY_DAYS, stored.getStability());
        assertEquals(new Fsrs().initialDifficulty(ReviewRating.EASY.getGrade()), stored.getDifficulty(), 1e-9);
        assertEquals(0, stored.getLearningStep());
        assertEquals(NOW, stored.getLastReviewedAt());
        // 60 days at the default retention, give or take the fuzz (up to 5 days at that length).
        assertTrue(stored.getIntervalDays() >= 55 && stored.getIntervalDays() <= 65, "interval " + stored.getIntervalDays());
        assertEquals(new StudyDay().startOfDayAfter(NOW, stored.getIntervalDays()), stored.getNextReviewAt());
        assertEquals(0, stored.getRepetitions());
        assertEquals(0, stored.getLapses());
        assertFalse(stored.isWeak());
        assertTrue(stored.isMastered(), "its stability is that of a mature card");
        assertEquals(stored.getNextReviewAt(), known.getNextReviewAt());

        List<ReviewLog> history = logs.findByWord(word.getId());
        assertEquals(1, history.size());
        assertEquals(ReviewKind.KNOWN, history.get(0).getKind());
        assertEquals(ReviewRating.EASY, history.get(0).getRating());
        assertEquals(NOW, history.get(0).getReviewedAt());

        // It is no longer due, and another new word takes its place.
        WordCard next = words.insert(newCard("lucid", "清晰的"));
        assertEquals(next.getId(), service.nextWord(deck.getId(), ReviewMode.EN_TO_ZH).orElseThrow().getId());
    }

    @Test
    void markingAWordKnownIsNoReview() throws SQLException {
        WordCard word = words.insert(newCard("abate", "减弱"));
        service.nextWord(deck.getId(), ReviewMode.EN_TO_ZH);
        ReviewQueueCounts before = service.queueCounts(deck.getId());

        service.markKnown(word.getId());

        var today = goalService.getTodayProgress(deck.getId());
        assertEquals(0, today.reviewedCount());
        assertEquals(0, today.newWordsCount());
        assertEquals(0, today.xpEarned());
        assertEquals(0, today.currentStreak());
        assertEquals(0, goalService.totalXp(deck.getId()));
        assertTrue(achievements.findAll().isEmpty());
        assertEquals(0, goalService.reviewCount(deck.getId(), 100));
        assertEquals(0, service.sessionSummary().reviewedCount());
        assertEquals(0, service.sessionSummary().cardsReviewed());
        // The day's new-word allowance is not used up.
        assertEquals(before.newCardsIntroducedToday(), service.queueCounts(deck.getId()).newCardsIntroducedToday());
        assertEquals(0, stats.dashboardStats(deck.getId()).reviewedToday());
        assertTrue(stats.dailyReviewStats(deck.getId(), 7).stream().allMatch(day -> day.reviewCount() == 0));
        assertTrue(stats.hardestWords(deck.getId(), 10).isEmpty());
        assertEquals(null, stats.latestReviewAt(deck.getId()));
    }

    @Test
    void onlyANewWordCanBeMarkedKnown() throws SQLException {
        WordCard word = words.insert(newCard("abate", "减弱"));
        service.nextWord(deck.getId(), ReviewMode.EN_TO_ZH);
        service.submitAnswer(word.getId(), "减弱", ReviewMode.EN_TO_ZH);
        service.rateCurrent(word.getId(), ReviewRating.GOOD);
        WordCard learning = words.findById(word.getId()).orElseThrow();

        assertThrows(IllegalArgumentException.class, () -> service.markKnown(word.getId()));

        WordCard after = words.findById(word.getId()).orElseThrow();
        assertEquals(learning.getState(), after.getState());
        assertEquals(learning.getNextReviewAt(), after.getNextReviewAt());
        assertEquals(1, logs.findByWord(word.getId()).size());
        assertEquals(1, service.undoDepth(), "only the rating can be undone");
    }

    @Test
    void undoingAlreadyKnownMakesTheWordNewAgain() throws SQLException {
        WordCard word = words.insert(newCard("abate", "减弱"));
        service.nextWord(deck.getId(), ReviewMode.EN_TO_ZH);
        service.submitAnswer(word.getId(), "减弱", ReviewMode.EN_TO_ZH);
        service.markKnown(word.getId());
        assertFalse(service.hasPendingAnswer(word.getId()));

        UndoAction undone = service.undoLast();

        assertEquals(UndoAction.Kind.KNOWN, undone.kind());
        ReviewUndoTest.assertSameCard(word, words.findById(word.getId()).orElseThrow());
        assertTrue(logs.findByWord(word.getId()).isEmpty());
        assertEquals("减弱", undone.answer().userAnswer());
        assertTrue(service.hasPendingAnswer(word.getId()));
    }

    @Test
    void replayingTheHistoryOfAKnownWordPutsItIntoReviewAgain() {
        WordCard word = newCard("abate", "减弱");
        word.setId(42);
        ReviewLog known = new ReviewLog(1, 42, NOW, "", "减弱", 1.0, ReviewRating.EASY, 0, ReviewKind.KNOWN, null,
            ReviewRating.EASY, false);
        ReviewLog review = new ReviewLog(2, 42, NOW.plusDays(61), "减弱", "减弱", 1.0, ReviewRating.GOOD, 3000,
            ReviewKind.REVIEW, ReviewMode.EN_TO_ZH, ReviewRating.GOOD, false);

        scheduler.replay(word, List.of(known));

        assertEquals(CardState.REVIEW, word.getState());
        assertEquals(ReviewScheduler.KNOWN_STABILITY_DAYS, word.getStability());
        assertEquals(0, word.getRepetitions());
        assertEquals(NOW, word.getLastReviewedAt());

        scheduler.replay(word, List.of(known, review));

        assertEquals(1, word.getRepetitions());
        assertTrue(word.getStability() > ReviewScheduler.KNOWN_STABILITY_DAYS, "a success two months later grows it");
    }

    @Test
    void aKnownWordDueAgainIsReviewedLikeAnyOther() throws SQLException {
        WordCard word = words.insert(newCard("abate", "减弱"));
        service.nextWord(deck.getId(), ReviewMode.EN_TO_ZH);
        WordCard known = service.markKnown(word.getId());
        clock.set(known.getNextReviewAt().plus(Duration.ofHours(5)));
        service.resetSession(deck.getId());

        assertEquals(word.getId(), service.nextWord(deck.getId(), ReviewMode.EN_TO_ZH).orElseThrow().getId());
        service.submitAnswer(word.getId(), "减弱", ReviewMode.EN_TO_ZH);
        service.rateCurrent(word.getId(), ReviewRating.GOOD);

        WordCard reviewed = words.findById(word.getId()).orElseThrow();
        assertEquals(CardState.REVIEW, reviewed.getState());
        assertTrue(reviewed.getIntervalDays() > known.getIntervalDays());
        assertEquals(1, goalService.getTodayProgress(deck.getId()).reviewedCount());
        assertEquals(0, goalService.getTodayProgress(deck.getId()).newWordsCount(), "it was introduced as known");
    }

    @Test
    void aBackupKeepsTheKnownLog() throws SQLException {
        WordCard word = words.insert(newCard("abate", "减弱"));
        service.nextWord(deck.getId(), ReviewMode.EN_TO_ZH);
        service.markKnown(word.getId());
        BackupService backups = new BackupService(decks, words, logs, goals, achievements, databaseManager,
            new WordValidationService(), clock);
        Path json = backups.exportJsonBackup(deck.getId(), tempDir.resolve("known.json"));
        Deck restored = decks.create("Restored");

        backups.importJsonBackup(json, restored.getId());

        WordCard copy = words.findByEnglish(restored.getId(), "abate").orElseThrow();
        assertEquals(CardState.REVIEW, copy.getState());
        List<ReviewLog> history = logs.findByWord(copy.getId());
        assertEquals(1, history.size());
        assertEquals(ReviewKind.KNOWN, history.get(0).getKind());
        assertEquals(0, goalService.getTodayProgress(restored.getId()).reviewedCount());
    }

    private WordCard newCard(String english, String chinese) {
        WordCard card = WordCard.createNew(deck.getId(), english, chinese);
        card.setAddedAt(NOW.minusDays(1));
        card.setNextReviewAt(NOW.minusDays(1));
        return card;
    }
}
