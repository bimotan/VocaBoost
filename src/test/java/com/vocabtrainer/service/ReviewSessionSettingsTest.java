package com.vocabtrainer.service;

import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.ReviewMode;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The session size the user chose stays the session's target (review findings A10 and C9): before,
 * changing the mode, resetting or switching decks silently fell back to a target of 10.
 */
class ReviewSessionSettingsTest {
    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    private Deck deck;
    private Deck other;
    private WordRepository words;
    private ReviewLogRepository logs;
    private GoalService goals;
    private AchievementService achievements;
    private SettingsService settingsService;
    private ReviewService service;

    @BeforeEach
    void setUp() throws SQLException {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("session.db"));
        DeckRepository decks = new DeckRepository(databaseManager);
        deck = decks.ensureDefaultDeck();
        other = decks.create("Other");
        words = new WordRepository(databaseManager);
        logs = new ReviewLogRepository(databaseManager);
        goals = new GoalService(new GoalRepository(databaseManager), logs, Clock.systemDefaultZone());
        achievements = new AchievementService(new AchievementRepository(databaseManager), goals);
        settingsService = new SettingsService(new SettingsRepository(databaseManager));
        for (String english : new String[] {"lucid", "abate", "laud"}) {
            words.insert(WordCard.createNew(deck.getId(), english, "释义" + english));
            words.insert(WordCard.createNew(other.getId(), english, "释义" + english));
        }
        service = newService();
    }

    private ReviewService newService() {
        return new ReviewService(words, logs, new SimilarityService(), new ReviewScheduler(), goals, achievements,
            Clock.systemDefaultZone(), new ReviewSettings(settingsService), new Random(1));
    }

    @Test
    void theDefaultNewWordsPerDayLimitsEveryDeckWithoutALimitOfItsOwn() {
        ReviewSettings settings = new ReviewSettings(settingsService);
        assertEquals(ReviewSettings.DEFAULT_NEW_CARDS_PER_DAY, settings.defaultNewCardsPerDay());
        service.setNewCardsPerDay(other.getId(), 3);

        settings.saveDefaultNewCardsPerDay(2);

        assertEquals(2, settings.defaultNewCardsPerDay());
        assertEquals(2, service.newCardsPerDay(deck.getId()), "follows the default");
        assertEquals(3, service.newCardsPerDay(other.getId()), "keeps its own limit");
        assertFalse(settings.hasOwnNewCardsPerDay(deck.getId()));
        assertTrue(settings.hasOwnNewCardsPerDay(other.getId()));
        assertEquals(2, service.queueCounts(deck.getId()).newAvailableToday(), "2 of the deck's 3 new words");
        assertEquals(3, service.queueCounts(other.getId()).newAvailableToday());
        assertEquals(2, newService().newCardsPerDay(deck.getId()), "kept for the next start");

        assertThrows(IllegalArgumentException.class,
            () -> settings.saveDefaultNewCardsPerDay(ReviewSettings.MAX_NEW_CARDS_PER_DAY + 1));
        assertThrows(IllegalArgumentException.class, () -> settings.saveDefaultNewCardsPerDay(-1));
        assertEquals(2, settings.defaultNewCardsPerDay());

        settings.clearNewCardsPerDay(other.getId());
        assertFalse(settings.hasOwnNewCardsPerDay(other.getId()));
        assertEquals(2, service.newCardsPerDay(other.getId()), "follows the default again");
        settings.saveDefaultNewCardsPerDay(4);
        assertEquals(4, service.newCardsPerDay(other.getId()));
    }

    @Test
    void anInvalidSavedDefaultIsIgnored() {
        settingsService.save("review.newCardsPerDay", "many");

        assertEquals(ReviewSettings.DEFAULT_NEW_CARDS_PER_DAY, new ReviewSettings(settingsService).defaultNewCardsPerDay());
        assertEquals(ReviewSettings.DEFAULT_NEW_CARDS_PER_DAY, service.newCardsPerDay(deck.getId()));
    }

    @Test
    void theChosenTargetSurvivesAModeChangeAResetAndADeckSwitch() {
        service.startSession(deck.getId(), ReviewMode.EN_TO_ZH, 50);
        assertEquals(50, service.sessionSummary().sessionGoal());

        service.nextWord(deck.getId(), ReviewMode.MIXED);
        assertEquals(ReviewMode.MIXED, service.sessionMode());
        assertEquals(50, service.sessionSummary().sessionGoal(), "after a mode change");

        service.resetSession(deck.getId());
        assertEquals(50, service.sessionSummary().sessionGoal(), "after Reset");
        assertEquals(ReviewMode.MIXED, service.sessionMode(), "Reset keeps the mode");

        service.nextWord(other.getId(), ReviewMode.MIXED);
        assertEquals(50, service.sessionSummary().sessionGoal(), "after a deck switch");

        service.startSession(other.getId(), ReviewMode.MIXED, 0);
        service.nextWord(deck.getId(), ReviewMode.EN_TO_ZH);
        assertEquals(0, service.sessionSummary().sessionGoal(), "All Due stays All Due");
    }

    @Test
    void beforeAnySessionTheTargetIsTwentyAsTheSelectorShows() {
        assertEquals(ReviewSettings.DEFAULT_SESSION_SIZE, service.sessionTarget());
        service.nextWord(deck.getId(), ReviewMode.EN_TO_ZH);
        assertEquals(ReviewSettings.DEFAULT_SESSION_SIZE, service.sessionSummary().sessionGoal());
        assertEquals(20, ReviewSettings.DEFAULT_SESSION_SIZE);
    }

    @Test
    void theLastSizeAndModeAreWhatTheNextStartUses() {
        service.startSession(deck.getId(), ReviewMode.ZH_TO_EN, 50);
        assertEquals(50, newService().sessionTarget());
        assertEquals(ReviewMode.ZH_TO_EN, newService().sessionMode());

        service.startSession(deck.getId(), ReviewMode.WEAK_WORDS, 0);
        ReviewService restarted = newService();
        assertEquals(0, restarted.sessionTarget());
        assertEquals(ReviewMode.WEAK_WORDS, restarted.sessionMode());
        restarted.resetSession(deck.getId());
        assertEquals(0, restarted.sessionSummary().sessionGoal());
    }

    @Test
    void changingTheTargetKeepsWhatTheSessionHasDone() {
        service.startSession(deck.getId(), ReviewMode.EN_TO_ZH, 1);
        WordCard word = service.nextWord(deck.getId(), ReviewMode.EN_TO_ZH).orElseThrow();
        service.submitAnswer(word.getId(), word.getChinese(), ReviewMode.EN_TO_ZH);
        service.rateCurrent(word.getId(), ReviewRating.EASY);
        assertTrue(service.isSessionTargetReached());

        service.setSessionTarget(2);

        assertFalse(service.isSessionTargetReached());
        assertEquals(1, service.sessionSummary().cardsReviewed());
        assertEquals(2, service.sessionSummary().sessionGoal());
        assertTrue(service.nextWord(deck.getId(), ReviewMode.EN_TO_ZH).isPresent());
        assertEquals(2, newService().sessionTarget());
    }

    @Test
    void savedValuesThatAreNotValidAreIgnored() {
        settingsService.save("review.sessionSize", "lots");
        settingsService.save("review.mode", "SIDEWAYS");
        settingsService.save("review.newCardsPerDay." + deck.getId(), "-3");

        ReviewSettings settings = new ReviewSettings(settingsService);
        assertEquals(ReviewSettings.DEFAULT_SESSION_SIZE, settings.sessionSize());
        assertEquals(ReviewMode.EN_TO_ZH, settings.mode());
        assertEquals(ReviewSettings.DEFAULT_NEW_CARDS_PER_DAY, settings.newCardsPerDay(deck.getId()));
        assertThrows(IllegalArgumentException.class, () -> settings.saveNewCardsPerDay(deck.getId(), -1));
        assertThrows(IllegalArgumentException.class, () -> settings.saveSessionSize(501));
        assertThrows(IllegalArgumentException.class, () -> service.setNewCardsPerDay(deck.getId(), 10_000));
    }
}
