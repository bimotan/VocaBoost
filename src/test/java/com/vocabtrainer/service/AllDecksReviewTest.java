package com.vocabtrainer.service;

import com.vocabtrainer.TestClock;
import com.vocabtrainer.domain.CardState;
import com.vocabtrainer.domain.Deck;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reviewing every active deck in one session (review finding G6): each queue takes the best card
 * of every deck, and each card keeps its own deck's schedule, new-card limit and goals.
 */
class AllDecksReviewTest {
    /** Tuesday 10 March 2026, 9:00. */
    private static final LocalDateTime START = LocalDateTime.of(2026, 3, 10, 9, 0);

    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    private final TestClock clock = new TestClock(START);
    private DeckRepository decks;
    private Deck gre;
    private Deck toefl;
    private WordRepository words;
    private GoalService goals;
    private ReviewSettings settings;
    private ReviewService service;

    @BeforeEach
    void setUp() throws SQLException {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("all-decks.db"));
        decks = new DeckRepository(databaseManager);
        gre = decks.create("GRE");
        toefl = decks.create("TOEFL");
        words = new WordRepository(databaseManager);
        ReviewLogRepository logs = new ReviewLogRepository(databaseManager);
        goals = new GoalService(new GoalRepository(databaseManager), logs, clock);
        AchievementService achievements = new AchievementService(new AchievementRepository(databaseManager), goals,
            clock);
        settings = new ReviewSettings(new SettingsService(new SettingsRepository(databaseManager)));
        service = new ReviewService(words, logs, new SimilarityService(), new ReviewScheduler(), goals, achievements,
            clock, settings, new Random(3));
    }

    @Test
    void theReviewsOfEveryDeckComeMostLikelyForgottenFirst() throws SQLException {
        WordCard greOld = review(gre, "gre-old", 20);
        WordCard toeflOlder = review(toefl, "toefl-older", 40);
        WordCard greRecent = review(gre, "gre-recent", 8);
        settings.saveNewCardsPerDay(gre.getId(), 0);
        settings.saveNewCardsPerDay(toefl.getId(), 0);
        service.startSession(ReviewService.ALL_DECKS, ReviewMode.EN_TO_ZH, 0);

        assertEquals(List.of(toeflOlder.getId(), greOld.getId(), greRecent.getId()),
            reviewAll().stream().map(WordCard::getId).toList());
    }

    @Test
    void eachDeckKeepsItsOwnNewWordLimitAndTheirNewWordsTakeTurns() throws SQLException {
        for (int i = 0; i < 5; i++) {
            newWord(gre, "gre" + i);
            newWord(toefl, "toefl" + i);
        }
        settings.saveNewCardsPerDay(gre.getId(), 2);
        settings.saveNewCardsPerDay(toefl.getId(), 1);
        service.startSession(ReviewService.ALL_DECKS, ReviewMode.EN_TO_ZH, 0);

        List<WordCard> shown = reviewAll();

        assertEquals(List.of("gre0", "gre1", "toefl0"), shown.stream().map(WordCard::getEnglish).toList(),
            "the deck with the most of its limit left first; the deck selector's order breaks ties");
        assertEquals(7, service.queueCounts(ReviewService.ALL_DECKS).newCardsDue());
        assertEquals(0, service.queueCounts(ReviewService.ALL_DECKS).newAvailableToday());
        assertTrue(service.newCardsHeldBackByLimit(ReviewService.ALL_DECKS));
    }

    @Test
    void aRatingCountsForTheDeckOfItsCard() throws SQLException {
        WordCard word = review(toefl, "toefl-old", 30);
        service.startSession(ReviewService.ALL_DECKS, ReviewMode.EN_TO_ZH, 0);
        assertEquals(word.getId(), service.nextWord(ReviewService.ALL_DECKS, ReviewMode.EN_TO_ZH).orElseThrow().getId());

        ReviewOutcome outcome = answer(word, ReviewRating.GOOD);

        assertEquals(toefl.getId(), outcome.word().getDeckId());
        assertEquals(1, goals.getTodayProgress(toefl.getId()).reviewedCount());
        assertEquals(0, goals.getTodayProgress(gre.getId()).reviewedCount());
        assertTrue(goals.totalXp(toefl.getId()) > 0);
        assertEquals(0, goals.totalXp(gre.getId()));
        assertTrue(service.nextWord(ReviewService.ALL_DECKS, ReviewMode.EN_TO_ZH).isEmpty(),
            "the session goes on after the rating instead of starting again on the card's deck");
        assertEquals(1, service.sessionSummary().cardsReviewed());
    }

    @Test
    void suspendedWordsAndTheWordsOfAnArchivedDeckAreLeftOut() throws SQLException {
        WordCard suspended = review(gre, "gre-suspended", 50);
        words.setSuspended(List.of(suspended.getId()), true);
        WordCard archivedLater = review(toefl, "toefl-old", 40);
        WordCard greOld = review(gre, "gre-old", 20);
        Deck empty = decks.create("Empty");
        service.startSession(ReviewService.ALL_DECKS, ReviewMode.EN_TO_ZH, 0);
        assertEquals(archivedLater.getId(), service.nextWord(ReviewService.ALL_DECKS, ReviewMode.EN_TO_ZH)
            .orElseThrow().getId());

        decks.archive(toefl.getId());

        assertFalse(service.isReviewable(archivedLater.getId()), "a card on screen of an archived deck must go");
        assertEquals(greOld.getId(), service.nextWord(ReviewService.ALL_DECKS, ReviewMode.EN_TO_ZH).orElseThrow().getId());
        assertEquals(List.of(empty.getId(), gre.getId()), service.sessionDecks(ReviewService.ALL_DECKS),
            "the active decks by name, an empty one too");
    }

    @Test
    void weakWordsComeFromEveryDeck() throws SQLException {
        WordCard greWeak = weak(gre, "gre-weak");
        WordCard toeflWeak = weak(toefl, "toefl-weak");
        service.startSession(ReviewService.ALL_DECKS, ReviewMode.WEAK_WORDS, 0);

        List<Long> shown = new ArrayList<>();
        Optional<WordCard> next = service.nextWord(ReviewService.ALL_DECKS, ReviewMode.WEAK_WORDS);
        while (next.isPresent() && shown.size() < 5) {
            shown.add(next.get().getId());
            answer(next.get(), ReviewRating.GOOD);
            next = service.nextWord(ReviewService.ALL_DECKS, ReviewMode.WEAK_WORDS);
        }

        assertTrue(shown.contains(greWeak.getId()) && shown.contains(toeflWeak.getId()), shown.toString());
    }

    @Test
    void theNextSessionStartsWithEveryDeckAgain() {
        assertFalse(service.sessionAllDecks());
        service.startSession(ReviewService.ALL_DECKS, ReviewMode.EN_TO_ZH, 20);
        assertTrue(settings.allDecks());
        assertTrue(service.sessionAllDecks());

        service.startSession(gre.getId(), ReviewMode.EN_TO_ZH, 20);
        assertFalse(service.sessionAllDecks());
    }

    private List<WordCard> reviewAll() {
        List<WordCard> shown = new ArrayList<>();
        Optional<WordCard> next = service.nextWord(ReviewService.ALL_DECKS, ReviewMode.EN_TO_ZH);
        while (next.isPresent()) {
            if (shown.size() > 100) {
                throw new AssertionError("the session does not end");
            }
            shown.add(next.get());
            answer(next.get(), ReviewRating.EASY);
            next = service.nextWord(ReviewService.ALL_DECKS, ReviewMode.EN_TO_ZH);
        }
        return shown;
    }

    private ReviewOutcome answer(WordCard word, ReviewRating rating) {
        LocalDateTime shownAt = clock.now();
        clock.advance(Duration.ofSeconds(2));
        service.submitAnswer(word.getId(), word.getChinese(), ReviewMode.EN_TO_ZH, shownAt);
        return service.rateCurrent(word.getId(), rating);
    }

    /** A review card last reviewed {@code daysAgo} days ago at a stability of 10 days: the older, the likelier forgotten. */
    private WordCard review(Deck deck, String english, int daysAgo) throws SQLException {
        WordCard card = WordCard.createNew(deck.getId(), english, english + "的意思");
        card.setAddedAt(START.minusDays(60));
        card.setState(CardState.REVIEW);
        card.setStability(10);
        card.setDifficulty(5);
        card.setRepetitions(3);
        card.setConsecutiveCorrect(3);
        card.setIntervalDays(10);
        card.setLastReviewedAt(START.minusDays(daysAgo));
        card.setNextReviewAt(START.minusDays(Math.max(0, daysAgo - 10)).minusHours(1));
        return words.insert(card);
    }

    private WordCard weak(Deck deck, String english) throws SQLException {
        WordCard card = review(deck, english, 12);
        card.setState(CardState.RELEARNING);
        card.setStability(1);
        card.setNextReviewAt(START.minusMinutes(5));
        words.update(card);
        return card;
    }

    private void newWord(Deck deck, String english) throws SQLException {
        WordCard card = WordCard.createNew(deck.getId(), english, english + "的意思");
        card.setAddedAt(START.minusHours(1));
        card.setNextReviewAt(START.minusHours(1));
        words.insert(card);
    }
}
