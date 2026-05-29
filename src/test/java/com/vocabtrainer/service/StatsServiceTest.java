package com.vocabtrainer.service;

import com.vocabtrainer.domain.DailyReviewStat;
import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.ReviewLog;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StatsServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void dailyReviewStatsCanBeScopedToDeck() throws Exception {
        DatabaseManager databaseManager = new DatabaseManager(tempDir.resolve("stats.db"));
        databaseManager.initialize();
        DeckRepository deckRepository = new DeckRepository(databaseManager);
        WordRepository wordRepository = new WordRepository(databaseManager);
        ReviewLogRepository reviewLogRepository = new ReviewLogRepository(databaseManager);

        Deck defaultDeck = deckRepository.ensureDefaultDeck();
        Deck satDeck = deckRepository.create("SAT");
        WordCard defaultWord = wordRepository.save(WordCard.createNew(defaultDeck.getId(), "abate", "减弱"));
        WordCard satWord = wordRepository.save(WordCard.createNew(satDeck.getId(), "lucid", "清晰的"));

        reviewLogRepository.insert(new ReviewLog(0, defaultWord.getId(), LocalDateTime.now(), "减弱", "减弱", 1,
            ReviewRating.GOOD, 1000));
        reviewLogRepository.insert(new ReviewLog(0, satWord.getId(), LocalDateTime.now(), "错", "清晰的", 0,
            ReviewRating.AGAIN, 1000));

        StatsService statsService = new StatsService(wordRepository, reviewLogRepository, databaseManager);

        List<DailyReviewStat> defaultStats = statsService.dailyReviewStats(defaultDeck.getId(), 1);
        List<DailyReviewStat> satStats = statsService.dailyReviewStats(satDeck.getId(), 1);

        assertEquals(1, defaultStats.get(0).reviewCount());
        assertEquals(1.0, defaultStats.get(0).accuracy());
        assertEquals(1, satStats.get(0).reviewCount());
        assertEquals(0.0, satStats.get(0).accuracy());
    }
}
