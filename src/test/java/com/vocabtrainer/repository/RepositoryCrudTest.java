package com.vocabtrainer.repository;

import com.vocabtrainer.domain.Achievement;
import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.ReviewLog;
import com.vocabtrainer.domain.ReviewRating;
import com.vocabtrainer.domain.WordCard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
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

    @Test
    void goalAchievementAndDictionaryCacheCrudWorks() throws Exception {
        DatabaseManager databaseManager = new DatabaseManager(tempDir.resolve("new-tables.db"));
        databaseManager.initialize();
        GoalRepository goalRepository = new GoalRepository(databaseManager);
        AchievementRepository achievementRepository = new AchievementRepository(databaseManager);
        DictionaryCacheRepository cacheRepository = new DictionaryCacheRepository(databaseManager);

        LocalDate date = LocalDate.of(2026, 5, 28);
        goalRepository.ensure(date, 20, 5, 10);
        goalRepository.addProgress(date, 1, 1, 2, 9);
        assertEquals(1, goalRepository.totalReviews());
        assertEquals(9, goalRepository.totalXp());

        Achievement achievement = new Achievement(
            "first_review",
            "First Review",
            "Completed first review.",
            LocalDateTime.of(2026, 5, 28, 9, 0),
            10
        );
        assertTrue(achievementRepository.insertIfAbsent(achievement));
        assertFalse(achievementRepository.insertIfAbsent(achievement));
        assertEquals(1, achievementRepository.findAll().size());

        cacheRepository.save("lucid", "payload", "test", LocalDateTime.of(2026, 5, 28, 9, 0));
        assertEquals("payload", cacheRepository.findPayload("LUCID").orElseThrow());
    }
}
