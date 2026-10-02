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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewServiceTest {
    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    @Test
    void chineseToEnglishModeChecksEnglishAnswer() throws Exception {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("review.db"));
        Deck deck = new DeckRepository(databaseManager).ensureDefaultDeck();
        WordRepository wordRepository = new WordRepository(databaseManager);
        ReviewLogRepository reviewLogRepository = new ReviewLogRepository(databaseManager);
        WordCard word = wordRepository.save(WordCard.createNew(deck.getId(), "lucid", "清晰的"));
        ReviewService service = new ReviewService(wordRepository, reviewLogRepository,
            new SimilarityService(), new ReviewScheduler());

        ReviewAnswer answer = service.submitAnswer(word.getId(), "lucid", ReviewMode.ZH_TO_EN);

        assertEquals("lucid", answer.correctAnswer());
        assertEquals(1.0, answer.similarity());
    }

    @Test
    void weakModeCanSelectWeakWordsBeforeTheyAreDue() throws Exception {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("weak.db"));
        Deck deck = new DeckRepository(databaseManager).ensureDefaultDeck();
        WordRepository wordRepository = new WordRepository(databaseManager);
        ReviewService service = new ReviewService(wordRepository, new ReviewLogRepository(databaseManager),
            new SimilarityService(), new ReviewScheduler());

        WordCard weak = WordCard.createNew(deck.getId(), "abate", "减弱");
        weak.setState(CardState.REVIEW);
        weak.setStability(5);
        weak.setDifficulty(5);
        weak.setRepetitions(3);
        weak.setLastReviewedAt(LocalDateTime.now().minusDays(1));
        weak.setNextReviewAt(LocalDateTime.now().plusDays(5));
        weak.setLapses(1);
        weak.setConsecutiveCorrect(0);
        wordRepository.save(weak);

        assertTrue(service.nextDueWord(deck.getId()).isEmpty());
        assertEquals("abate", service.nextWord(deck.getId(), ReviewMode.WEAK_WORDS).orElseThrow().getEnglish());
    }

    @Test
    void sessionTargetStopsReviewAfterTargetIsReachedAndResetClearsProgress() throws Exception {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("session-target.db"));
        Deck deck = new DeckRepository(databaseManager).ensureDefaultDeck();
        WordRepository wordRepository = new WordRepository(databaseManager);
        ReviewService service = new ReviewService(wordRepository, new ReviewLogRepository(databaseManager),
            new SimilarityService(), new ReviewScheduler());

        WordCard word = wordRepository.save(WordCard.createNew(deck.getId(), "lucid", "清晰的"));
        wordRepository.save(WordCard.createNew(deck.getId(), "abate", "减少"));

        service.startSession(deck.getId(), ReviewMode.EN_TO_ZH, 1);
        WordCard next = service.nextWord(deck.getId(), ReviewMode.EN_TO_ZH).orElseThrow();
        service.submitAnswer(next.getId(), next.getChinese(), ReviewMode.EN_TO_ZH);
        // Easy graduates the card at once, so it does not come back in this session.
        service.rateCurrent(next.getId(), ReviewRating.EASY);

        assertTrue(service.isSessionTargetReached());
        assertTrue(service.nextWord(deck.getId(), ReviewMode.EN_TO_ZH).isEmpty());
        assertEquals(1, service.sessionSummary().reviewedCount());
        assertEquals(1, service.sessionSummary().cardsReviewed());

        service.resetSession(deck.getId());
        assertFalse(service.isSessionTargetReached());
        assertEquals(0, service.sessionSummary().reviewedCount());
        assertTrue(wordRepository.findById(word.getId()).isPresent());
    }

    @Test
    void mixedModeUsesConcreteQuestionModeForCurrentCard() throws Exception {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("mixed.db"));
        Deck deck = new DeckRepository(databaseManager).ensureDefaultDeck();
        WordRepository wordRepository = new WordRepository(databaseManager);
        ReviewService service = new ReviewService(wordRepository, new ReviewLogRepository(databaseManager),
            new SimilarityService(), new ReviewScheduler());

        WordCard word = wordRepository.save(WordCard.createNew(deck.getId(), "lucid", "清晰的"));

        service.startSession(deck.getId(), ReviewMode.MIXED, 5);
        assertEquals("lucid", service.nextWord(deck.getId(), ReviewMode.MIXED).orElseThrow().getEnglish());
        assertTrue(service.currentQuestionMode() == ReviewMode.EN_TO_ZH
            || service.currentQuestionMode() == ReviewMode.ZH_TO_EN);
        ReviewAnswer answer = service.submitAnswer(
            word.getId(),
            service.currentQuestionMode() == ReviewMode.ZH_TO_EN ? "lucid" : "清晰的",
            ReviewMode.MIXED
        );
        assertTrue(answer.similarity() > 0.8);
    }

    @Test
    void aFailedWordComesBackInTheSameSessionOnceItsStepIsDue() throws Exception {
        Session session = session("relearn.db", "lucid", "abate", "laud");
        WordCard failed = session.next().orElseThrow();
        session.answer(failed, "完全错误", ReviewRating.AGAIN);
        assertEquals(CardState.LEARNING, session.stored(failed).getState());

        // The step is a minute: other due words come first...
        WordCard second = session.next().orElseThrow();
        assertNotEquals(failed.getId(), second.getId());
        session.answer(second, second.getChinese(), ReviewRating.EASY);
        // ...and once the minute is over the failed word is next, before the remaining new word.
        session.clock.advance(Duration.ofMinutes(2));
        assertEquals(failed.getId(), session.next().orElseThrow().getId());
        session.answer(failed, failed.getChinese(), ReviewRating.GOOD);

        WordCard third = session.next().orElseThrow();
        assertNotEquals(failed.getId(), third.getId());
        assertNotEquals(second.getId(), third.getId());
        session.answer(third, third.getChinese(), ReviewRating.EASY);
        // Nothing is due, but the failed word's 10-minute step ends within 20 minutes: it is shown early.
        assertEquals(failed.getId(), session.next().orElseThrow().getId());
        session.answer(failed, failed.getChinese(), ReviewRating.GOOD);
        assertEquals(CardState.REVIEW, session.stored(failed).getState());

        assertTrue(session.next().isEmpty());
        assertEquals(5, session.service.sessionSummary().reviewedCount());
        assertEquals(3, session.service.sessionSummary().cardsReviewed());
    }

    @Test
    void afterTheTargetOnlyTheSessionsOwnLearningWordsComeBack() throws Exception {
        Session session = session("target.db", "lucid", "abate");
        session.service.startSession(session.deck.getId(), ReviewMode.EN_TO_ZH, 1);
        WordCard first = session.next().orElseThrow();
        session.answer(first, first.getChinese(), ReviewRating.GOOD);
        assertTrue(session.service.isSessionTargetReached());

        // The target is one word, but the word just learned still has a step to go.
        assertEquals(first.getId(), session.next().orElseThrow().getId());
        session.answer(first, first.getChinese(), ReviewRating.GOOD);

        assertTrue(session.next().isEmpty(), "the other new word waits for the next session");
        assertEquals(1, session.service.sessionSummary().cardsReviewed(), "the target counts words, not repeats");
    }

    @Test
    void theResponseTimeRunsFromShowingTheCardToSubmittingTheAnswer() throws Exception {
        Session session = session("response.db", "lucid");
        WordCard word = session.next().orElseThrow();
        LocalDateTime shownAt = session.clock.now();

        session.clock.advance(Duration.ofSeconds(45));
        ReviewAnswer answer = session.service.submitAnswer(word.getId(), "清晰的", ReviewMode.EN_TO_ZH, shownAt);
        // Reading the feedback before rating is not part of the response time.
        session.clock.advance(Duration.ofSeconds(2));
        session.service.rateCurrent(word.getId(), ReviewRating.GOOD);

        assertEquals(45_000, answer.responseMillis());
        List<ReviewLog> logs = session.logs.findByWord(word.getId());
        assertEquals(1, logs.size());
        assertEquals(45_000, logs.get(0).getElapsedMillis());
    }

    @Test
    void ratingPreviewsMatchWhatTheRatingDoes() throws Exception {
        Session session = session("preview.db", "lucid");
        WordCard word = session.next().orElseThrow();
        session.service.submitAnswer(word.getId(), word.getChinese(), ReviewMode.EN_TO_ZH, session.clock.now());

        var previews = session.service.previewRatings(word.getId());
        session.service.rateCurrent(word.getId(), ReviewRating.EASY);

        assertEquals(previews.get(ReviewRating.EASY).intervalDays(), session.stored(word).getIntervalDays());
        assertEquals(Duration.ofMinutes(10), previews.get(ReviewRating.GOOD).learningDelay());
    }

    private Session session(String file, String... english) throws SQLException {
        DatabaseManager databaseManager = databases.open(tempDir.resolve(file));
        Deck deck = new DeckRepository(databaseManager).ensureDefaultDeck();
        WordRepository words = new WordRepository(databaseManager);
        ReviewLogRepository logs = new ReviewLogRepository(databaseManager);
        TestClock clock = new TestClock(LocalDateTime.of(2026, 3, 10, 9, 0));
        for (String word : english) {
            WordCard card = WordCard.createNew(deck.getId(), word, "释义" + word);
            card.setNextReviewAt(clock.now().minusDays(1));
            words.save(card);
        }
        ReviewService service = new ReviewService(words, logs, new SimilarityService(), new ReviewScheduler(),
            clock);
        service.startSession(deck.getId(), ReviewMode.EN_TO_ZH, 0);
        return new Session(deck, words, logs, clock, service);
    }

    private record Session(Deck deck, WordRepository words, ReviewLogRepository logs, TestClock clock,
                           ReviewService service) {
        Optional<WordCard> next() {
            return service.nextWord(deck.getId(), ReviewMode.EN_TO_ZH);
        }

        /** Takes ten seconds to answer, then rates. */
        void answer(WordCard word, String answer, ReviewRating rating) {
            LocalDateTime shownAt = clock.now();
            clock.advance(Duration.ofSeconds(10));
            service.submitAnswer(word.getId(), answer, ReviewMode.EN_TO_ZH, shownAt);
            service.rateCurrent(word.getId(), rating);
        }

        WordCard stored(WordCard word) throws SQLException {
            return words.findById(word.getId()).orElseThrow();
        }
    }
}
