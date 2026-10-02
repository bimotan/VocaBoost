package com.vocabtrainer.service;

import com.vocabtrainer.TestClock;
import com.vocabtrainer.domain.Achievement;
import com.vocabtrainer.domain.CardState;
import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.GoalTargets;
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
import com.vocabtrainer.service.scheduling.SchedulingOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Undoing a rating restores the card exactly and takes back its log, XP, goal completion and badges
 * (review finding G3), on a fixed clock.
 */
class ReviewUndoTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 5, 28, 9, 0);

    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    private final TestClock clock = new TestClock(NOW);
    private DeckRepository decks;
    private WordRepository words;
    private ReviewLogRepository logs;
    private GoalRepository goals;
    private AchievementRepository achievements;
    private GoalSettings goalSettings;
    private GoalService goalService;
    private ReviewService service;
    private Deck deck;

    @BeforeEach
    void setUp() throws SQLException {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("undo.db"));
        decks = new DeckRepository(databaseManager);
        words = new WordRepository(databaseManager);
        logs = new ReviewLogRepository(databaseManager);
        goals = new GoalRepository(databaseManager);
        achievements = new AchievementRepository(databaseManager);
        ReviewScheduler scheduler = new ReviewScheduler(SchedulingOptions.defaults(), new Random(7));
        goalSettings = GoalSettings.inMemory();
        goalService = new GoalService(goals, logs, goalSettings, scheduler.studyDay(), clock);
        AchievementService achievementService = new AchievementService(achievements, goalService, clock);
        service = new ReviewService(words, logs, new SimilarityService(), scheduler, goalService, achievementService,
            clock, null, new Random(7));
        deck = decks.ensureDefaultDeck();
        service.startSession(deck.getId(), ReviewMode.EN_TO_ZH, 20);
    }

    @Test
    void undoRestoresTheExactCardDeletesItsLogAndTakesBackXpAndBadges() throws SQLException {
        WordCard before = words.insert(reviewCard("lucid", "清晰的", 7));
        assertEquals(before.getId(), service.nextWord(deck.getId(), ReviewMode.EN_TO_ZH).orElseThrow().getId());
        service.submitAnswer(before.getId(), "清晰的", ReviewMode.EN_TO_ZH, NOW.minusSeconds(4));

        ReviewOutcome outcome = service.rateCurrent(before.getId(), ReviewRating.GOOD);

        assertNotEquals(before.getStability(), stored(before).getStability());
        assertEquals(1, logs.findByWord(before.getId()).size());
        assertTrue(outcome.xpEarned() > 0);
        assertEquals(List.of("first_review"), outcome.unlockedAchievements().stream().map(Achievement::code).toList());
        assertTrue(service.canUndo());

        UndoAction undone = service.undoLast();

        assertSameCard(before, stored(before));
        assertTrue(logs.findByWord(before.getId()).isEmpty());
        assertEquals(0, goalService.totalXp(deck.getId()));
        assertTrue(goals.find(deck.getId(), today()).isEmpty(), "the day had nothing before the rating");
        assertTrue(achievements.findAll().isEmpty());
        assertEquals(0, goalService.getTodayProgress(deck.getId()).reviewedCount());
        assertEquals(0, service.sessionSummary().reviewedCount());
        assertEquals(0, service.sessionSummary().xpEarned());
        assertEquals(0, service.sessionSummary().cardsReviewed());
        assertTrue(service.sessionSummary().unlockedAchievements().isEmpty());
        assertFalse(service.canUndo());
        assertEquals(UndoAction.Kind.RATING, undone.kind());
        assertEquals(ReviewRating.GOOD, undone.rating());
        assertEquals(outcome.xpEarned(), undone.xp());
        assertTrue(undone.wasShown());
        assertEquals(ReviewMode.EN_TO_ZH, undone.questionMode());
        assertSameCard(before, undone.word());

        // The checked answer is kept: the card is rated again without typing it again.
        assertTrue(service.hasPendingAnswer(before.getId()));
        service.rateCurrent(before.getId(), ReviewRating.HARD);
        List<ReviewLog> again = logs.findByWord(before.getId());
        assertEquals(1, again.size());
        assertEquals(ReviewRating.HARD, again.get(0).getRating());
        assertEquals("清晰的", again.get(0).getUserAnswer());
        assertEquals(4_000, again.get(0).getElapsedMillis());
    }

    @Test
    void undoTakesBackANewLeechTagAndTheDailyGoalItCompleted() throws SQLException {
        goalSettings.saveDefaults(new GoalTargets(1, 0));
        WordCard before = words.insert(reviewCard("cavil", "挑剔", WordCard.LEECH_LAPSES - 1));
        service.nextWord(deck.getId(), ReviewMode.EN_TO_ZH).orElseThrow();
        service.submitAnswer(before.getId(), "完全错误", ReviewMode.EN_TO_ZH);

        ReviewOutcome outcome = service.rateCurrent(before.getId(), ReviewRating.AGAIN);

        assertTrue(outcome.becameLeech());
        assertEquals("gre; leech", stored(before).getTags());
        assertTrue(goalService.getTodayProgress(deck.getId()).completed());
        assertEquals(List.of("first_review", "daily_goal"),
            outcome.unlockedAchievements().stream().map(Achievement::code).toList());

        service.undoLast();

        WordCard restored = stored(before);
        assertSameCard(before, restored);
        assertEquals("gre", restored.getTags());
        assertFalse(restored.isLeech());
        assertFalse(goalService.getTodayProgress(deck.getId()).completed());
        assertTrue(achievements.findAll().isEmpty());
        assertEquals(0, goalService.totalXp(deck.getId()));
    }

    @Test
    void tagsEditedSinceTheRatingStayButTheScheduleGoesBack() throws SQLException {
        WordCard before = words.insert(reviewCard("cavil", "挑剔", WordCard.LEECH_LAPSES - 1));
        service.nextWord(deck.getId(), ReviewMode.EN_TO_ZH).orElseThrow();
        service.submitAnswer(before.getId(), "完全错误", ReviewMode.EN_TO_ZH);
        service.rateCurrent(before.getId(), ReviewRating.AGAIN);
        WordCard edited = stored(before);
        edited.setTags("gre; leech; mnemonic: carp");
        edited.setNote("Think of a carp complaining.");
        words.update(edited);

        service.undoLast();

        WordCard restored = stored(before);
        assertEquals(WordCard.LEECH_LAPSES - 1, restored.getLapses());
        assertEquals(before.getNextReviewAt(), restored.getNextReviewAt());
        assertEquals("gre; leech; mnemonic: carp", restored.getTags());
        assertEquals("Think of a carp complaining.", restored.getNote());
    }

    @Test
    void severalRatingsAreUndoneLastFirst() throws SQLException {
        WordCard first = words.insert(newCard("abate", "减弱", 3));
        WordCard second = words.insert(newCard("lucid", "清晰的", 2));
        WordCard third = words.insert(newCard("cavil", "挑剔", 1));
        int newAllowance = service.queueCounts(deck.getId()).newAvailableToday();
        rateNext(first, ReviewRating.GOOD);
        rateNext(second, ReviewRating.AGAIN);
        rateNext(third, ReviewRating.EASY);
        assertEquals(3, service.undoDepth());
        assertEquals(3, logs.findByDeck(deck.getId()).size());
        assertEquals(newAllowance - 3, service.queueCounts(deck.getId()).newAvailableToday());

        assertEquals(third.getId(), service.nextUndo().orElseThrow().word().getId());
        assertEquals(third.getId(), service.undoLast().word().getId());
        assertSameCard(third, stored(third));
        assertEquals(CardState.LEARNING, stored(second).getState());
        assertEquals(2, service.undoDepth());

        assertEquals(second.getId(), service.undoLast().word().getId());
        assertEquals(first.getId(), service.undoLast().word().getId());

        for (WordCard card : List.of(first, second, third)) {
            assertSameCard(card, stored(card));
        }
        assertTrue(logs.findByDeck(deck.getId()).isEmpty());
        assertEquals(newAllowance, service.queueCounts(deck.getId()).newAvailableToday());
        assertEquals(0, goalService.totalXp(deck.getId()));
        assertEquals(0, service.sessionSummary().cardsReviewed());
        assertFalse(service.canUndo());
        assertThrows(IllegalStateException.class, service::undoLast);
        // The session goes on with the cards it had shown: the first one comes again.
        assertEquals(first.getId(), service.nextWord(deck.getId(), ReviewMode.EN_TO_ZH).orElseThrow().getId());
    }

    @Test
    void aNewSessionHasNothingToUndo() throws SQLException {
        Deck other = decks.create("Other");
        WordCard word = words.insert(newCard("abate", "减弱", 1));
        rateNext(word, ReviewRating.GOOD);
        assertTrue(service.canUndo());

        // A deck switch starts a new session.
        service.nextWord(other.getId(), ReviewMode.EN_TO_ZH);
        assertFalse(service.canUndo());

        service.nextWord(deck.getId(), ReviewMode.EN_TO_ZH);
        WordCard another = words.insert(newCard("lucid", "清晰的", 0));
        rateNext(another, ReviewRating.GOOD);
        assertTrue(service.canUndo());
        service.resetSession(deck.getId());
        assertFalse(service.canUndo());

        rateNext(words.insert(newCard("cavil", "挑剔", 0)), ReviewRating.GOOD);
        service.nextWord(deck.getId(), ReviewMode.ZH_TO_EN);
        assertFalse(service.canUndo(), "a mode change starts a new session too");
    }

    @Test
    void aRatingOfAWordDeletedSinceCannotBeUndoneButTheOneBeforeCan() throws SQLException {
        WordCard kept = words.insert(newCard("abate", "减弱", 2));
        WordCard deleted = words.insert(newCard("lucid", "清晰的", 1));
        rateNext(kept, ReviewRating.GOOD);
        rateNext(deleted, ReviewRating.GOOD);
        int xpOfBoth = goalService.totalXp(deck.getId());
        words.deleteById(deleted.getId());

        IllegalStateException error = assertThrows(IllegalStateException.class, service::undoLast);

        assertTrue(error.getMessage().contains("\"lucid\" was deleted"), error.getMessage());
        assertEquals(xpOfBoth, goalService.totalXp(deck.getId()), "nothing was taken back");
        assertEquals(1, service.undoDepth());
        assertEquals(kept.getId(), service.undoLast().word().getId());
        assertSameCard(kept, stored(kept));
    }

    @Test
    void undoingAPracticeRestoresTheDueDateAndTakesBackItsXp() throws SQLException {
        WordCard weak = reviewCard("abate", "减弱", 1);
        weak.setConsecutiveCorrect(0);
        weak.setNextReviewAt(NOW.plusDays(5));
        WordCard before = words.insert(weak);
        service.startSession(deck.getId(), ReviewMode.WEAK_WORDS, 20);
        assertEquals(before.getId(), service.nextWord(deck.getId(), ReviewMode.WEAK_WORDS).orElseThrow().getId());
        service.submitAnswer(before.getId(), "完全错误", ReviewMode.WEAK_WORDS);
        ReviewOutcome outcome = service.rateCurrent(before.getId(), ReviewRating.AGAIN);
        assertTrue(outcome.isPractice());
        assertNotEquals(before.getNextReviewAt(), stored(before).getNextReviewAt());
        assertTrue(goalService.totalXp(deck.getId()) > 0);

        service.undoLast();

        assertSameCard(before, stored(before));
        assertTrue(logs.findByWord(before.getId()).isEmpty());
        assertEquals(0, goalService.totalXp(deck.getId()));
    }

    @Test
    void aRatingUndoneOnALaterDayTakesTheXpFromTheDayItWasEarned() throws SQLException {
        WordCard word = words.insert(newCard("abate", "减弱", 1));
        rateNext(word, ReviewRating.GOOD);
        LocalDate ratedOn = today();
        clock.set(NOW.plusDays(1));

        service.undoLast();

        assertTrue(goals.find(deck.getId(), ratedOn).isEmpty());
        assertEquals(0, goalService.totalXp(deck.getId()));
    }

    @Test
    void onlyTheLastHundredActionsCanBeUndone() throws SQLException {
        for (int index = 0; index <= ReviewService.UNDO_LIMIT; index++) {
            WordCard word = words.insert(newCard("word" + index, "词" + index, 0));
            service.suspendWord(word.getId(), true);
        }

        assertEquals(ReviewService.UNDO_LIMIT, service.undoDepth());
        assertEquals("word" + ReviewService.UNDO_LIMIT, service.nextUndo().orElseThrow().word().getEnglish());
    }

    @Test
    void suspendingFromTheReviewTabIsUndoneWithTheAnswerItHad() throws SQLException {
        WordCard word = words.insert(newCard("abate", "减弱", 1));
        assertEquals(word.getId(), service.nextWord(deck.getId(), ReviewMode.EN_TO_ZH).orElseThrow().getId());
        service.submitAnswer(word.getId(), "减弱", ReviewMode.EN_TO_ZH);

        service.suspendWord(word.getId(), true);

        assertTrue(stored(word).isSuspended());
        assertFalse(service.hasPendingAnswer(word.getId()));
        assertTrue(service.nextWord(deck.getId(), ReviewMode.EN_TO_ZH).isEmpty());

        UndoAction undone = service.undoLast();

        assertEquals(UndoAction.Kind.SUSPEND, undone.kind());
        assertFalse(stored(word).isSuspended());
        assertSameCard(word, stored(word));
        assertEquals("减弱", undone.answer().userAnswer());
        assertTrue(service.hasPendingAnswer(word.getId()));
        assertTrue(logs.findByWord(word.getId()).isEmpty());
    }

    @Test
    void undoingARatingUnsuspendsACardSuspendedSince() throws SQLException {
        WordCard word = words.insert(newCard("abate", "减弱", 1));
        rateNext(word, ReviewRating.AGAIN);
        words.setSuspended(List.of(word.getId()), true);

        service.undoLast();

        assertSameCard(word, stored(word));
        assertFalse(stored(word).isSuspended());
    }

    private void rateNext(WordCard expected, ReviewRating rating) {
        WordCard shown = service.nextWord(deck.getId(), ReviewMode.EN_TO_ZH).orElseThrow();
        assertEquals(expected.getId(), shown.getId());
        service.submitAnswer(shown.getId(), rating == ReviewRating.AGAIN ? "完全错误" : shown.getChinese(),
            ReviewMode.EN_TO_ZH);
        service.rateCurrent(shown.getId(), rating);
    }

    private LocalDate today() {
        return goalService.today();
    }

    private WordCard stored(WordCard word) throws SQLException {
        return words.findById(word.getId()).orElseThrow();
    }

    private WordCard newCard(String english, String chinese, int daysAgo) {
        WordCard card = WordCard.createNew(deck.getId(), english, chinese);
        card.setAddedAt(NOW.minusDays(daysAgo));
        card.setNextReviewAt(NOW.minusDays(daysAgo));
        return card;
    }

    /** A review card due today, with every scheduling field set to something of its own. */
    private WordCard reviewCard(String english, String chinese, int lapses) {
        WordCard card = WordCard.createNew(deck.getId(), english, chinese);
        card.setAddedAt(NOW.minusDays(40));
        card.setState(CardState.REVIEW);
        card.setStability(12.5);
        card.setDifficulty(6.25);
        card.setLearningStep(0);
        card.setRepetitions(9);
        card.setConsecutiveCorrect(2);
        card.setLapses(lapses);
        card.setIntervalDays(12);
        card.setEasinessFactor(2.15);
        card.setLastReviewedAt(NOW.minusDays(13).minusMinutes(17));
        card.setNextReviewAt(NOW.minusHours(2).minusMinutes(17));
        card.setTags("gre");
        return card;
    }

    static void assertSameCard(WordCard expected, WordCard actual) {
        String name = expected.getEnglish();
        assertEquals(expected.getId(), actual.getId(), name);
        assertEquals(expected.getState(), actual.getState(), name + " state");
        assertEquals(expected.getStability(), actual.getStability(), name + " stability");
        assertEquals(expected.getDifficulty(), actual.getDifficulty(), name + " difficulty");
        assertEquals(expected.getLearningStep(), actual.getLearningStep(), name + " learning step");
        assertEquals(expected.getNextReviewAt(), actual.getNextReviewAt(), name + " next review");
        assertEquals(expected.getLastReviewedAt(), actual.getLastReviewedAt(), name + " last review");
        assertEquals(expected.getEasinessFactor(), actual.getEasinessFactor(), name + " easiness");
        assertEquals(expected.getIntervalDays(), actual.getIntervalDays(), name + " interval");
        assertEquals(expected.getRepetitions(), actual.getRepetitions(), name + " repetitions");
        assertEquals(expected.getConsecutiveCorrect(), actual.getConsecutiveCorrect(), name + " consecutive correct");
        assertEquals(expected.getLapses(), actual.getLapses(), name + " lapses");
        assertEquals(expected.getTags(), actual.getTags(), name + " tags");
        assertEquals(expected.isSuspended(), actual.isSuspended(), name + " suspended");
        assertEquals(expected.getChinese(), actual.getChinese(), name + " chinese");
        assertEquals(expected.getAddedAt(), actual.getAddedAt(), name + " added");
    }
}
