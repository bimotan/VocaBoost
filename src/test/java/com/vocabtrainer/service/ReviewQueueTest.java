package com.vocabtrainer.service;

import com.vocabtrainer.TestClock;
import com.vocabtrainer.domain.CardState;
import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.ReviewKind;
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
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Review cards and new cards come from separate queues, and new cards are limited per day (review
 * finding A2): before, a bulk import filled the due window with new words, learned words waited
 * behind them and every imported word was due at once.
 */
class ReviewQueueTest {
    /** Tuesday 10 March 2026, 9:00. */
    private static final LocalDateTime START = LocalDateTime.of(2026, 3, 10, 9, 0);

    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    private final TestClock clock = new TestClock(START);
    private DatabaseManager databaseManager;
    private Deck deck;
    private WordRepository words;
    private ReviewLogRepository logs;
    private GoalService goals;
    private AchievementService achievements;
    private ReviewSettings settings;
    private ReviewService service;

    @BeforeEach
    void setUp() throws SQLException {
        databaseManager = databases.open(tempDir.resolve("queue.db"));
        deck = new DeckRepository(databaseManager).ensureDefaultDeck();
        words = new WordRepository(databaseManager);
        logs = new ReviewLogRepository(databaseManager);
        goals = new GoalService(new GoalRepository(databaseManager), logs, clock);
        achievements = new AchievementService(new AchievementRepository(databaseManager), goals, clock);
        settings = new ReviewSettings(new SettingsService(new SettingsRepository(databaseManager)));
        service = newService();
    }

    /** A review service on the same database, as after restarting the app. */
    private ReviewService newService() {
        return new ReviewService(words, logs, new SimilarityService(), new ReviewScheduler(), goals, achievements,
            clock, settings, new Random(3));
    }

    @Test
    void learnedReviewsComeFirstAndNewWordsStopAtTheDailyLimit() throws SQLException {
        List<Long> learned = insertLearned(10);
        insertNew(300);
        service.startSession(deck.getId(), ReviewMode.EN_TO_ZH, 0);

        List<WordCard> shown = reviewAll();

        assertEquals(10 + ReviewSettings.DEFAULT_NEW_CARDS_PER_DAY, shown.size(), "all due reviews and 20 new words");
        assertTrue(learned.contains(shown.get(0).getId()), "a learned word comes first");
        List<Long> firstTwelve = shown.subList(0, 12).stream().map(WordCard::getId).toList();
        assertTrue(firstTwelve.containsAll(learned), "the learned words are not starved by 300 new ones: " + firstTwelve);
        assertEquals(ReviewSettings.DEFAULT_NEW_CARDS_PER_DAY,
            shown.stream().filter(word -> word.getState() == CardState.NEW).count());
        assertEquals(ReviewSettings.DEFAULT_NEW_CARDS_PER_DAY,
            logs.countNewCardsIntroducedSince(deck.getId(), START.withHour(4)));
        ReviewQueueCounts left = service.queueCounts(deck.getId());
        assertEquals(0, left.dueReviews());
        assertEquals(280, left.newCardsDue());
        assertEquals(0, left.newAvailableToday());
    }

    @Test
    void newWordsComeInTheOrderTheyWereAddedOneAfterEveryFourReviews() throws SQLException {
        insertLearned(12);
        List<Long> added = insertNew(5);
        service.startSession(deck.getId(), ReviewMode.EN_TO_ZH, 0);

        List<WordCard> shown = reviewAll();

        StringBuilder pattern = new StringBuilder();
        shown.forEach(word -> pattern.append(word.getState() == CardState.NEW ? 'N' : 'R'));
        assertEquals("RRRRNRRRRNRRRRNNN", pattern.toString());
        assertEquals(added, shown.stream().filter(word -> word.getState() == CardState.NEW).map(WordCard::getId).toList());
    }

    @Test
    void newWordsWaitWhileTheDueReviewsFillTheSession() throws SQLException {
        insertLearned(12);
        insertNew(30);
        service.startSession(deck.getId(), ReviewMode.EN_TO_ZH, 10);

        List<WordCard> shown = reviewAll();

        assertEquals(10, shown.size());
        assertTrue(shown.stream().allMatch(word -> word.getState() == CardState.REVIEW), "only learned words");

        // With room to spare, the session mixes new words in, but keeps room for every due review.
        service.startSession(deck.getId(), ReviewMode.EN_TO_ZH, 6);
        List<WordCard> next = reviewAll();
        assertEquals(6, next.size());
        assertEquals(2, next.stream().filter(word -> word.getState() == CardState.REVIEW).count(),
            "the two reviews left come first: " + next.stream().map(word -> word.getState().name()).toList());
    }

    @Test
    void inAnAllDueSessionALargeBacklogOfReviewsComesBeforeNewWords() throws SQLException {
        insertLearned(ReviewService.LARGE_BACKLOG + 6);
        insertNew(3);
        service.startSession(deck.getId(), ReviewMode.EN_TO_ZH, 0);

        List<WordCard> shown = reviewAll();

        int firstNew = 0;
        while (shown.get(firstNew).getState() != CardState.NEW) {
            firstNew++;
        }
        assertEquals(6, firstNew, "new words wait until no more than " + ReviewService.LARGE_BACKLOG + " reviews are due");
        assertEquals(ReviewService.LARGE_BACKLOG + 6 + 3, shown.size());
    }

    @Test
    void theNewWordLimitCountsWhatEarlierSessionsIntroducedTodayAndResetsTheNextStudyDay() throws SQLException {
        insertNew(30);
        service.setNewCardsPerDay(deck.getId(), 5);
        service.startSession(deck.getId(), ReviewMode.EN_TO_ZH, 3);
        assertEquals(3, reviewAll().size());

        // The app is restarted: the limit was saved, and three of today's five are used.
        ReviewService restarted = newService();
        assertEquals(5, restarted.newCardsPerDay(deck.getId()));
        restarted.startSession(deck.getId(), ReviewMode.EN_TO_ZH, 0);
        assertEquals(2, reviewAll(restarted).size());
        assertEquals(new ReviewQueueCounts(0, 0, 25, 5, 5), restarted.queueCounts(deck.getId()));

        // Still the same study day at 3 am, a new one from 4 am.
        clock.set(START.plusDays(1).withHour(3));
        restarted.startSession(deck.getId(), ReviewMode.EN_TO_ZH, 0);
        assertTrue(restarted.nextWord(deck.getId(), ReviewMode.EN_TO_ZH).isEmpty());
        clock.set(START.plusDays(1).withHour(4));
        assertEquals(5, restarted.queueCounts(deck.getId()).newAvailableToday());
        restarted.startSession(deck.getId(), ReviewMode.EN_TO_ZH, 0);
        assertEquals(5, reviewAll(restarted).size());
    }

    @Test
    void eachDeckHasItsOwnNewWordLimit() throws SQLException {
        insertNew(10);
        Deck other = new DeckRepository(databaseManager).create("Other");
        for (int i = 0; i < 10; i++) {
            WordCard card = WordCard.createNew(other.getId(), "other" + i, "释义");
            card.setNextReviewAt(START.minusHours(1));
            words.insert(card);
        }
        service.setNewCardsPerDay(other.getId(), 0);

        assertEquals(10, service.queueCounts(deck.getId()).newAvailableToday());
        assertEquals(0, service.queueCounts(other.getId()).newAvailableToday());
        service.startSession(other.getId(), ReviewMode.EN_TO_ZH, 0);
        assertTrue(service.nextWord(other.getId(), ReviewMode.EN_TO_ZH).isEmpty());
        assertEquals(ReviewSettings.DEFAULT_NEW_CARDS_PER_DAY, settings.newCardsPerDay(deck.getId()));
    }

    @Test
    void theDashboardSplitsDueTodayIntoDueReviewsAndNewWords() throws SQLException {
        insertLearned(4);
        insertNew(30);
        StatsService stats = new StatsService(words, logs, clock, new ReviewScheduler().studyDay(), settings);

        DashboardStats before = stats.dashboardStats(deck.getId());
        assertEquals(4, before.dueReviews());
        assertEquals(20, before.newAvailableToday());
        assertEquals(24, before.dueToday());
        assertEquals(4, stats.overdueCount(deck.getId()), "new words are not overdue");

        service.startSession(deck.getId(), ReviewMode.EN_TO_ZH, 7);
        reviewAll();

        DashboardStats after = stats.dashboardStats(deck.getId());
        assertEquals(0, after.dueReviews());
        assertEquals(17, after.newAvailableToday());
        assertEquals(17, after.dueToday());
        assertEquals(17, stats.deckOverviews(List.of(deck)).get(0).due(), "the deck table says the same");
    }

    @Test
    void onlyTheFirstReviewOfANewWordIsLoggedAsLearning() throws SQLException {
        WordCard word = words.insert(newWord("lucid", START.minusMinutes(1)));
        service.startSession(deck.getId(), ReviewMode.EN_TO_ZH, 0);

        // Good starts the learning steps; the second step comes back in the same session.
        answer(service, service.nextWord(deck.getId(), ReviewMode.EN_TO_ZH).orElseThrow(), ReviewRating.GOOD);
        WordCard again = service.nextWord(deck.getId(), ReviewMode.EN_TO_ZH).orElseThrow();
        assertEquals(word.getId(), again.getId());
        answer(service, again, ReviewRating.GOOD);

        assertEquals(List.of(ReviewKind.LEARN, ReviewKind.REVIEW),
            logs.findByWord(word.getId()).stream().map(log -> log.getKind()).toList());
        assertEquals(1, logs.countNewCardsIntroducedSince(deck.getId(), START.withHour(4)));
    }

    /** Rates every card of the session Easy until it ends; returns the cards as they were shown. */
    private List<WordCard> reviewAll() {
        return reviewAll(service);
    }

    private List<WordCard> reviewAll(ReviewService reviews) {
        List<WordCard> shown = new ArrayList<>();
        Optional<WordCard> next = reviews.nextWord(deck.getId(), ReviewMode.EN_TO_ZH);
        while (next.isPresent()) {
            if (shown.size() > 1000) {
                throw new AssertionError("the session does not end");
            }
            shown.add(next.get());
            answer(reviews, next.get(), ReviewRating.EASY);
            next = reviews.nextWord(deck.getId(), ReviewMode.EN_TO_ZH);
        }
        return shown;
    }

    private void answer(ReviewService reviews, WordCard word, ReviewRating rating) {
        LocalDateTime shownAt = clock.now();
        clock.advance(Duration.ofSeconds(2));
        reviews.submitAnswer(word.getId(), word.getChinese(), ReviewMode.EN_TO_ZH, shownAt);
        reviews.rateCurrent(word.getId(), rating);
    }

    /** Learned words, three days overdue, reviewed 10 days ago at a stability of 7 days. */
    private List<Long> insertLearned(int count) throws SQLException {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            WordCard card = WordCard.createNew(deck.getId(), "learned" + i, "已学" + i);
            card.setAddedAt(START.minusDays(30));
            card.setState(CardState.REVIEW);
            card.setStability(7);
            card.setDifficulty(5);
            card.setRepetitions(3);
            card.setConsecutiveCorrect(3);
            card.setIntervalDays(7);
            card.setLastReviewedAt(START.minusDays(10));
            card.setNextReviewAt(START.minusDays(3));
            ids.add(words.insert(card).getId());
        }
        return ids;
    }

    /** New words, as a bulk import adds them: due at once, added a second apart. */
    private List<Long> insertNew(int count) throws SQLException {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(words.insert(newWord(String.format("new%03d", i), START.minusHours(1).plusSeconds(i))).getId());
        }
        return ids;
    }

    private WordCard newWord(String english, LocalDateTime addedAt) {
        WordCard card = WordCard.createNew(deck.getId(), english, "释义" + english);
        card.setAddedAt(addedAt);
        card.setNextReviewAt(addedAt);
        return card;
    }
}
