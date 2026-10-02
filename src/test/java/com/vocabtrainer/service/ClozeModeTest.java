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
import com.vocabtrainer.service.cloze.Cloze;
import com.vocabtrainer.service.cloze.ClozeMaker;
import com.vocabtrainer.service.cloze.WordForms;
import com.vocabtrainer.service.scheduling.CardScheduler;
import com.vocabtrainer.service.scheduling.SchedulingOptions;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cloze mode asks a card with its example sentence blanked out, skips and counts the cards whose
 * example makes no cloze, accepts the form the sentence has and logs the direction CLOZE on the
 * card's one schedule (review finding G1).
 */
class ClozeModeTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 3, 10, 9, 0);
    private static final CardScheduler CARDS = new CardScheduler(SchedulingOptions.defaults());

    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    private final TestClock clock = new TestClock(NOW);
    private Deck deck;
    private CountingWords words;
    private ReviewLogRepository logs;
    private ReviewService service;
    private int added;

    @BeforeEach
    void setUp() throws SQLException {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("cloze.db"));
        deck = new DeckRepository(databaseManager).ensureDefaultDeck();
        words = new CountingWords(databaseManager);
        logs = new ReviewLogRepository(databaseManager);
        WordForms ecdict = english -> english.equals("forgo") ? Set.of("forwent", "forgone") : Set.of();
        service = new ReviewService(words, logs, new SimilarityService(), new ReviewScheduler(), null, null, clock,
            null, new Random(), new ClozeMaker(ecdict));
    }

    @Test
    void cardsWithoutAUsableExampleAreSkippedAndCounted() throws SQLException {
        words.insert(newCard("abate", "减弱", null));
        words.insert(newCard("lucid", "清晰的", "The explanation was clear."));
        WordCard admonish = words.insert(newCard("admonish", "告诫", "The mentor admonished him."));
        WordCard forgo = words.insert(newCard("forgo", "放弃", "She forwent the bonus."));
        service.startSession(deck.getId(), ReviewMode.CLOZE, 0);

        WordCard first = service.nextWord(deck.getId(), ReviewMode.CLOZE).orElseThrow();

        assertEquals(admonish.getId(), first.getId());
        assertEquals(ReviewMode.CLOZE, service.currentQuestionMode());
        assertEquals("The mentor _____ him.", service.currentCloze().map(Cloze::masked).orElseThrow());
        assertEquals(2, service.clozeSkippedCount(), "abate has no example; lucid's example lacks the word");
        answer(first, "admonished", ReviewRating.EASY);

        WordCard second = service.nextWord(deck.getId(), ReviewMode.CLOZE).orElseThrow();
        assertEquals(forgo.getId(), second.getId(), "an irregular form the dictionary lists");
        assertEquals("She _____ the bonus.", service.currentCloze().map(Cloze::masked).orElseThrow());
        answer(second, "forwent", ReviewRating.EASY);

        assertEquals(Optional.empty(), service.nextWord(deck.getId(), ReviewMode.CLOZE).map(WordCard::getEnglish),
            "the skipped cards are not asked");
        assertEquals(2, service.clozeSkippedCount());
        assertEquals(Optional.empty(), service.currentCloze());

        service.startSession(deck.getId(), ReviewMode.EN_TO_ZH, 0);
        assertEquals(0, service.clozeSkippedCount(), "a new session forgets them");
        assertEquals("abate", service.nextWord(deck.getId(), ReviewMode.EN_TO_ZH).orElseThrow().getEnglish(),
            "other modes ask cards without an example");
        assertEquals(0, service.clozeSkippedCount());
    }

    @Test
    void theFormInTheSentenceCountsAsTheWordAndTheLogSaysCloze() throws SQLException {
        WordCard admonish = words.insert(newCard("admonish", "告诫", "The mentor admonished him."));
        service.startSession(deck.getId(), ReviewMode.CLOZE, 0);
        service.nextWord(deck.getId(), ReviewMode.CLOZE).orElseThrow();
        clock.advance(Duration.ofSeconds(4));

        ReviewAnswer answer = service.submitAnswer(admonish.getId(), "Admonished", ReviewMode.CLOZE, NOW);

        assertEquals(ReviewMode.CLOZE, answer.direction());
        assertEquals("admonish", answer.correctAnswer());
        assertEquals(1.0, answer.similarity());
        assertTrue(!answer.canOverride(), "a match leaves nothing to override");
        service.rateCurrent(admonish.getId(), ReviewRating.GOOD);

        ReviewLog log = onlyLog(admonish);
        assertEquals(ReviewMode.CLOZE, log.getDirection());
        assertEquals("Admonished", log.getUserAnswer());
        assertEquals("admonish", log.getCorrectAnswer());
        assertEquals(ReviewRating.GOOD, log.getEffectiveRating());
        assertEquals(4000, log.getElapsedMillis());
    }

    @Test
    void aTypoInTheFormInTheSentenceCountsAtMostAsHard() throws SQLException {
        WordCard admonish = words.insert(newCard("admonish", "告诫", "The mentor admonished him."));
        service.startSession(deck.getId(), ReviewMode.CLOZE, 0);
        service.nextWord(deck.getId(), ReviewMode.CLOZE).orElseThrow();

        ReviewAnswer answer = service.submitAnswer(admonish.getId(), "admonishd", ReviewMode.CLOZE);

        assertEquals(ReviewRating.HARD, answer.countsAs(ReviewRating.GOOD, false));
        service.rateCurrent(admonish.getId(), ReviewRating.GOOD);
        assertEquals(ReviewRating.HARD, onlyLog(admonish).getEffectiveRating());
    }

    @Test
    void aClozeReviewSchedulesTheCardsOneScheduleAndEasyCountsAsEasy() throws SQLException {
        WordCard lucid = words.insert(dueReview("lucid", "清晰的", "The explanation was lucid."));
        CardScheduler.Outcome easy = CARDS.outcome(lucid, ReviewRating.EASY, NOW);
        service.startSession(deck.getId(), ReviewMode.CLOZE, 0);
        assertEquals(lucid.getId(), service.nextWord(deck.getId(), ReviewMode.CLOZE).orElseThrow().getId());

        service.submitAnswer(lucid.getId(), "lucid", ReviewMode.CLOZE);
        service.rateCurrent(lucid.getId(), ReviewRating.EASY);

        WordCard after = words.findById(lucid.getId()).orElseThrow();
        assertEquals(CardState.REVIEW, after.getState());
        assertEquals(easy.stability(), after.getStability(), 1e-9, "the same FSRS state every mode updates");
        assertEquals(easy.intervalDays(), after.getIntervalDays());
        assertEquals(lucid.getRepetitions() + 1, after.getRepetitions());
    }

    @Test
    void aFailedClozeComesBackAsAClozeAfterItsLearningStep() throws SQLException {
        WordCard admonish = words.insert(newCard("admonish", "告诫", "The mentor admonished him."));
        service.startSession(deck.getId(), ReviewMode.CLOZE, 0);
        service.nextWord(deck.getId(), ReviewMode.CLOZE).orElseThrow();
        service.submitAnswer(admonish.getId(), "warned", ReviewMode.CLOZE);
        service.rateCurrent(admonish.getId(), ReviewRating.AGAIN);
        clock.advance(Duration.ofMinutes(2));

        WordCard again = service.nextWord(deck.getId(), ReviewMode.CLOZE).orElseThrow();

        assertEquals(admonish.getId(), again.getId());
        assertEquals("The mentor _____ him.", service.currentCloze().map(Cloze::masked).orElseThrow());
    }

    @Test
    void aDeckOfCardsWithoutExamplesIsPassedOverInAFewReads() throws SQLException {
        List<WordCard> withoutExamples = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            withoutExamples.add(newCard("word" + i, "词" + i, null));
        }
        words.insertAll(withoutExamples);
        WordCard admonish = words.insert(newCard("admonish", "告诫", "The mentor admonished him."));
        service.startSession(deck.getId(), ReviewMode.CLOZE, 0);
        words.newCardReads = 0;

        WordCard shown = service.nextWord(deck.getId(), ReviewMode.CLOZE).orElseThrow();

        assertEquals(admonish.getId(), shown.getId());
        assertEquals(300, service.clozeSkippedCount());
        assertTrue(words.newCardReads <= 10, "read the new cards " + words.newCardReads + " times");
    }

    private void answer(WordCard word, String typed, ReviewRating rating) {
        service.submitAnswer(word.getId(), typed, ReviewMode.CLOZE);
        service.rateCurrent(word.getId(), rating);
    }

    private ReviewLog onlyLog(WordCard word) throws SQLException {
        List<ReviewLog> history = logs.findByWord(word.getId());
        assertEquals(1, history.size());
        return history.get(0);
    }

    /** A new card, due, added after the ones before it. */
    private WordCard newCard(String english, String chinese, String example) {
        WordCard card = WordCard.createNew(deck.getId(), english, chinese);
        card.setExampleSentence(example);
        card.setAddedAt(NOW.minusDays(1).plusSeconds(added++));
        card.setNextReviewAt(NOW.minusDays(1));
        return card;
    }

    /** A mature card, due today. */
    private WordCard dueReview(String english, String chinese, String example) {
        WordCard card = newCard(english, chinese, example);
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

    /** Counts how often the new-card queue is read. */
    private static final class CountingWords extends WordRepository {
        int newCardReads;

        CountingWords(DatabaseManager databaseManager) {
            super(databaseManager);
        }

        @Override
        public List<WordCard> findNewCards(long deckId, LocalDateTime dayEnd, int limit) throws SQLException {
            newCardReads++;
            return super.findNewCards(deckId, dayEnd, limit);
        }
    }
}
