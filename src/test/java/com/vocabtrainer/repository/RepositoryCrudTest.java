package com.vocabtrainer.repository;

import com.vocabtrainer.domain.Achievement;
import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.ReviewLog;
import com.vocabtrainer.domain.ReviewRating;
import com.vocabtrainer.domain.WordCard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepositoryCrudTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 5, 28, 9, 0);

    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    @Test
    void wordAndReviewLogCrudWorks() throws Exception {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("test.db"));
        Deck deck = new DeckRepository(databaseManager).ensureDefaultDeck();
        WordRepository wordRepository = new WordRepository(databaseManager);
        ReviewLogRepository logRepository = new ReviewLogRepository(databaseManager);

        WordCard word = WordCard.createNew(deck.getId(), "querulous", "抱怨的", NOW);
        wordRepository.save(word);

        assertTrue(word.getId() > 0);
        assertEquals(1, wordRepository.countAll(deck.getId()));
        assertTrue(wordRepository.findByEnglish(deck.getId(), "QUERULOUS").isPresent());
        LocalDateTime soon = NOW.plusMinutes(1);
        assertEquals(1, wordRepository.countDue(deck.getId(), soon, soon.plusDays(1)));

        word.setChinese("爱抱怨的");
        wordRepository.save(word);
        assertEquals("爱抱怨的", wordRepository.findById(word.getId()).orElseThrow().getChinese());

        logRepository.insert(new ReviewLog(
            0,
            word.getId(),
            NOW,
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
    void decksAreDatedByTheRepositorysClock() throws Exception {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("decks.db"));
        DeckRepository decks = new DeckRepository(databaseManager,
            Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));

        Deck created = decks.create("GRE");

        assertEquals(NOW, created.getCreatedAt());
        assertEquals(NOW, decks.findByName("GRE").orElseThrow().getCreatedAt());
    }

    @Test
    void goalAchievementAndDictionaryCacheCrudWorks() throws Exception {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("new-tables.db"));
        GoalRepository goalRepository = new GoalRepository(databaseManager);
        AchievementRepository achievementRepository = new AchievementRepository(databaseManager);
        DictionaryCacheRepository cacheRepository = new DictionaryCacheRepository(databaseManager);

        LocalDate date = LocalDate.of(2026, 5, 28);
        goalRepository.ensure(date, 20, 5, 10);
        goalRepository.addProgress(date, 1, 1, 2, 9);
        assertEquals(1, goalRepository.find(date).orElseThrow().reviewedCount());
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
        assertEquals(new DictionaryCacheRepository.CachedLookup("payload", "test", LocalDateTime.of(2026, 5, 28, 9, 0)),
            cacheRepository.find("LUCID").orElseThrow());
    }
}
