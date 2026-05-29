package com.vocabtrainer.service;

import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.ReviewMode;
import com.vocabtrainer.domain.ReviewRating;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.DeckRepository;
import com.vocabtrainer.repository.ReviewLogRepository;
import com.vocabtrainer.repository.WordRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void chineseToEnglishModeChecksEnglishAnswer() throws Exception {
        DatabaseManager databaseManager = new DatabaseManager(tempDir.resolve("review.db"));
        databaseManager.initialize();
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
        DatabaseManager databaseManager = new DatabaseManager(tempDir.resolve("weak.db"));
        databaseManager.initialize();
        Deck deck = new DeckRepository(databaseManager).ensureDefaultDeck();
        WordRepository wordRepository = new WordRepository(databaseManager);
        ReviewService service = new ReviewService(wordRepository, new ReviewLogRepository(databaseManager),
            new SimilarityService(), new ReviewScheduler());

        WordCard weak = WordCard.createNew(deck.getId(), "abate", "减弱");
        weak.setNextReviewAt(LocalDateTime.now().plusDays(5));
        weak.setLapses(2);
        weak.setConsecutiveCorrect(0);
        wordRepository.save(weak);

        assertTrue(service.nextDueWord(deck.getId()).isEmpty());
        assertEquals("abate", service.nextWord(deck.getId(), ReviewMode.WEAK_WORDS).orElseThrow().getEnglish());
    }

    @Test
    void sessionTargetStopsReviewAfterTargetIsReachedAndResetClearsProgress() throws Exception {
        DatabaseManager databaseManager = new DatabaseManager(tempDir.resolve("session-target.db"));
        databaseManager.initialize();
        Deck deck = new DeckRepository(databaseManager).ensureDefaultDeck();
        WordRepository wordRepository = new WordRepository(databaseManager);
        ReviewService service = new ReviewService(wordRepository, new ReviewLogRepository(databaseManager),
            new SimilarityService(), new ReviewScheduler());

        WordCard word = wordRepository.save(WordCard.createNew(deck.getId(), "lucid", "清晰的"));
        wordRepository.save(WordCard.createNew(deck.getId(), "abate", "减少"));

        service.startSession(deck.getId(), ReviewMode.EN_TO_ZH, 1);
        WordCard next = service.nextWord(deck.getId(), ReviewMode.EN_TO_ZH).orElseThrow();
        service.submitAnswer(next.getId(), next.getChinese(), ReviewMode.EN_TO_ZH);
        service.rateCurrent(next.getId(), ReviewRating.GOOD);

        assertTrue(service.isSessionTargetReached());
        assertTrue(service.nextWord(deck.getId(), ReviewMode.EN_TO_ZH).isEmpty());
        assertEquals(1, service.sessionSummary().reviewedCount());

        service.resetSession(deck.getId());
        assertFalse(service.isSessionTargetReached());
        assertEquals(0, service.sessionSummary().reviewedCount());
        assertTrue(wordRepository.findById(word.getId()).isPresent());
    }

    @Test
    void mixedModeUsesConcreteQuestionModeForCurrentCard() throws Exception {
        DatabaseManager databaseManager = new DatabaseManager(tempDir.resolve("mixed.db"));
        databaseManager.initialize();
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
}
