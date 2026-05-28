package com.vocabtrainer.repository;

import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.ReviewLog;
import com.vocabtrainer.domain.ReviewRating;
import com.vocabtrainer.domain.WordCard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepositoryCrudTest {
    @TempDir
    Path tempDir;

    @Test
    void wordAndReviewLogCrudWorks() throws Exception {
        DatabaseManager databaseManager = new DatabaseManager(tempDir.resolve("test.db"));
        databaseManager.initialize();
        Deck deck = new DeckRepository(databaseManager).ensureDefaultDeck();
        WordRepository wordRepository = new WordRepository(databaseManager);
        ReviewLogRepository logRepository = new ReviewLogRepository(databaseManager);

        WordCard word = WordCard.createNew(deck.getId(), "querulous", "抱怨的");
        wordRepository.save(word);

        assertTrue(word.getId() > 0);
        assertEquals(1, wordRepository.countAll(deck.getId()));
        assertTrue(wordRepository.findByEnglish(deck.getId(), "QUERULOUS").isPresent());
        assertEquals(1, wordRepository.countDue(deck.getId(), LocalDateTime.now().plusMinutes(1)));

        word.setChinese("爱抱怨的");
        wordRepository.save(word);
        assertEquals("爱抱怨的", wordRepository.findById(word.getId()).orElseThrow().getChinese());

        logRepository.insert(new ReviewLog(
            0,
            word.getId(),
            LocalDateTime.now(),
            "抱怨的",
            "爱抱怨的",
            0.75,
            ReviewRating.GOOD,
            1200
        ));
        assertEquals(1, logRepository.countByRating(ReviewRating.GOOD));

        List<WordCard> results = wordRepository.search(deck.getId(), "怨");
        assertEquals(1, results.size());

        wordRepository.deleteById(word.getId());
        assertFalse(wordRepository.findById(word.getId()).isPresent());
    }
}