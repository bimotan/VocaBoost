package com.vocabtrainer.service;

import com.vocabtrainer.TestClock;
import com.vocabtrainer.domain.CardState;
import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.ReviewLog;
import com.vocabtrainer.domain.ReviewMode;
import com.vocabtrainer.domain.ReviewRating;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.DeckRepository;
import com.vocabtrainer.repository.ReviewLogRepository;
import com.vocabtrainer.repository.TestDatabases;
import com.vocabtrainer.repository.WordRepository;
import com.vocabtrainer.service.scheduling.CardScheduler;
import com.vocabtrainer.service.scheduling.IntervalPreview;
import com.vocabtrainer.service.scheduling.SchedulingOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mixed mode logs the question direction and keeps one schedule per card, on which a recognition
 * success weighs no more than a Good and a production failure counts fully (review finding A11).
 */
class MixedModeTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 3, 10, 9, 0);
    private static final CardScheduler CARDS = new CardScheduler(SchedulingOptions.defaults());

    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    private final TestClock clock = new TestClock(NOW);
    private Deck deck;
    private WordRepository words;
    private ReviewLogRepository logs;

    @BeforeEach
    void setUp() throws SQLException {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("mixed.db"));
        deck = new DeckRepository(databaseManager).ensureDefaultDeck();
        words = new WordRepository(databaseManager);
        logs = new ReviewLogRepository(databaseManager);
    }

    @Test
    void everyReviewLogsTheDirectionTheQuestionWasAskedIn() throws SQLException {
        WordCard lucid = words.insert(dueReview("lucid", "清晰的"));
        WordCard abate = words.insert(dueReview("abate", "减弱"));
        WordCard laud = words.insert(dueReview("laud", "赞扬"));

        rate(serviceAsking(true), ReviewMode.EN_TO_ZH, lucid, ReviewRating.GOOD);
        rate(serviceAsking(true), ReviewMode.ZH_TO_EN, abate, ReviewRating.GOOD);
        ReviewService mixed = serviceAsking(false);
        mixed.startSession(deck.getId(), ReviewMode.MIXED, 0);
        WordCard shown = mixed.nextWord(deck.getId(), ReviewMode.MIXED).orElseThrow();
        assertEquals(ReviewMode.ZH_TO_EN, mixed.currentQuestionMode());
        mixed.submitAnswer(shown.getId(), shown.getEnglish(), ReviewMode.MIXED);
        mixed.rateCurrent(shown.getId(), ReviewRating.GOOD);

        assertEquals(ReviewMode.EN_TO_ZH, onlyLog(lucid).getDirection());
        assertEquals(ReviewMode.ZH_TO_EN, onlyLog(abate).getDirection());
        assertEquals(ReviewMode.ZH_TO_EN, onlyLog(shown).getDirection());
        assertEquals(1.0, onlyLog(shown).getSimilarity(), "the English word was the answer");
        assertTrue(List.of(lucid.getId(), abate.getId(), laud.getId()).contains(shown.getId()));
    }

    @Test
    void anEasyRecognitionInMixedModeGrowsTheScheduleNoMoreThanAGood() throws SQLException {
        WordCard word = words.insert(dueReview("lucid", "清晰的"));
        CardScheduler.Outcome good = CARDS.outcome(word, ReviewRating.GOOD, NOW);
        CardScheduler.Outcome easy = CARDS.outcome(word, ReviewRating.EASY, NOW);
        assertTrue(easy.intervalDays() > good.intervalDays());
        ReviewService service = serviceAsking(true);
        service.startSession(deck.getId(), ReviewMode.MIXED, 0);
        service.nextWord(deck.getId(), ReviewMode.MIXED).orElseThrow();
        assertEquals(ReviewMode.EN_TO_ZH, service.currentQuestionMode());
        service.submitAnswer(word.getId(), "清晰的", ReviewMode.MIXED);

        Map<ReviewRating, IntervalPreview> previews = service.previewRatings(word.getId());
        assertEquals(IntervalPreview.days(good.intervalDays()), previews.get(ReviewRating.EASY));
        service.rateCurrent(word.getId(), ReviewRating.EASY);

        WordCard after = words.findById(word.getId()).orElseThrow();
        assertEquals(good.stability(), after.getStability(), 1e-9);
        assertEquals(good.intervalDays(), after.getIntervalDays());
        assertEquals(ReviewRating.EASY, onlyLog(word).getRating(), "the log keeps what the user chose");
    }

    @Test
    void anEasyProductionInMixedModeCountsAsEasy() throws SQLException {
        WordCard word = words.insert(dueReview("lucid", "清晰的"));
        CardScheduler.Outcome easy = CARDS.outcome(word, ReviewRating.EASY, NOW);

        WordCard after = rateInMixedMode(word, false, word.getEnglish(), ReviewRating.EASY);

        assertEquals(easy.stability(), after.getStability(), 1e-9);
        assertEquals(easy.intervalDays(), after.getIntervalDays());
    }

    @Test
    void anEasyRecognitionOutsideMixedModeCountsAsEasy() throws SQLException {
        WordCard word = words.insert(dueReview("lucid", "清晰的"));
        CardScheduler.Outcome easy = CARDS.outcome(word, ReviewRating.EASY, NOW);

        rate(serviceAsking(true), ReviewMode.EN_TO_ZH, word, ReviewRating.EASY);

        assertEquals(easy.stability(), words.findById(word.getId()).orElseThrow().getStability(), 1e-9);
    }

    @Test
    void aFailedProductionCountsAsAFullLapse() throws SQLException {
        WordCard word = words.insert(dueReview("lucid", "清晰的"));
        CardScheduler.Outcome again = CARDS.outcome(word, ReviewRating.AGAIN, NOW);

        WordCard after = rateInMixedMode(word, false, "lucent", ReviewRating.AGAIN);

        assertEquals(CardState.RELEARNING, after.getState());
        assertEquals(word.getLapses() + 1, after.getLapses());
        assertEquals(again.stability(), after.getStability(), 1e-9);
        assertTrue(after.getStability() < word.getStability() / 2, "stability " + after.getStability());
        assertEquals(ReviewMode.ZH_TO_EN, onlyLog(word).getDirection());
    }

    private WordCard rateInMixedMode(WordCard word, boolean recognition, String answer, ReviewRating rating)
        throws SQLException {
        ReviewService service = serviceAsking(recognition);
        service.startSession(deck.getId(), ReviewMode.MIXED, 0);
        service.nextWord(deck.getId(), ReviewMode.MIXED).orElseThrow();
        service.submitAnswer(word.getId(), answer, ReviewMode.MIXED);
        service.rateCurrent(word.getId(), rating);
        return words.findById(word.getId()).orElseThrow();
    }

    private void rate(ReviewService service, ReviewMode mode, WordCard word, ReviewRating rating) {
        service.submitAnswer(word.getId(), mode == ReviewMode.ZH_TO_EN ? word.getEnglish() : word.getChinese(), mode);
        service.rateCurrent(word.getId(), rating);
    }

    /** A review service whose Mixed mode always asks English to Chinese, or always Chinese to English. */
    private ReviewService serviceAsking(boolean recognition) {
        Random direction = new Random() {
            @Override
            public boolean nextBoolean() {
                return recognition;
            }
        };
        return new ReviewService(words, logs, new SimilarityService(), new ReviewScheduler(), null, null, clock,
            null, direction);
    }

    private ReviewLog onlyLog(WordCard word) throws SQLException {
        List<ReviewLog> history = logs.findByWord(word.getId());
        assertEquals(1, history.size());
        return history.get(0);
    }

    /** A mature card, due today: last reviewed 30 days ago at a stability of 30 days. */
    private WordCard dueReview(String english, String chinese) {
        WordCard card = WordCard.createNew(deck.getId(), english, chinese);
        card.setState(CardState.REVIEW);
        card.setStability(30);
        card.setDifficulty(5);
        card.setRepetitions(6);
        card.setConsecutiveCorrect(6);
        card.setIntervalDays(30);
        card.setLastReviewedAt(NOW.minusDays(30));
        card.setNextReviewAt(NOW.minusHours(5));
        return card;
    }
}
