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
        assertEquals(1.0, statsService.dashboardStats(defaultDeck.getId()).accuracyToday());
        assertEquals(0.0, statsService.dashboardStats(satDeck.getId()).accuracyToday());
    }

    @Test
    void deckOverviewsMatchThePerDeckCountsAndLatestReview() throws Exception {
        DatabaseManager databaseManager = new DatabaseManager(tempDir.resolve("overview.db"));
        databaseManager.initialize();
        DeckRepository deckRepository = new DeckRepository(databaseManager);
        WordRepository wordRepository = new WordRepository(databaseManager);
        ReviewLogRepository reviewLogRepository = new ReviewLogRepository(databaseManager);
        StatsService statsService = new StatsService(wordRepository, reviewLogRepository, databaseManager);
        Deck gre = deckRepository.create("GRE");
        Deck sat = deckRepository.create("SAT");
        Deck empty = deckRepository.create("Empty");
        WordCard due = wordRepository.save(WordCard.createNew(gre.getId(), "abate", "减弱"));
        WordCard later = WordCard.createNew(gre.getId(), "lucid", "清晰的");
        later.setNextReviewAt(LocalDateTime.now().plusDays(3));
        wordRepository.save(later);
        WordCard archived = WordCard.createNew(gre.getId(), "gone", "消失的");
        archived.setArchived(true);
        wordRepository.save(archived);
        wordRepository.save(WordCard.createNew(sat.getId(), "laud", "赞扬"));
        LocalDateTime reviewed = LocalDateTime.now().minusHours(2).withNano(0);
        reviewLogRepository.insert(new ReviewLog(0, due.getId(), reviewed.minusDays(1), "减弱", "减弱", 1,
            ReviewRating.GOOD, 1000));
        reviewLogRepository.insert(new ReviewLog(0, archived.getId(), reviewed, "消失的", "消失的", 1,
            ReviewRating.GOOD, 1000));

        List<DeckOverview> overviews = statsService.deckOverviews(List.of(gre, sat, empty));

        assertEquals(List.of(gre, sat, empty), overviews.stream().map(DeckOverview::deck).toList());
        for (DeckOverview overview : overviews) {
            long deckId = overview.deck().getId();
            assertEquals(wordRepository.countAll(deckId), overview.words(), overview.deck().getName());
            assertEquals(statsService.overdueCount(deckId), overview.due(), overview.deck().getName());
            assertEquals(statsService.latestReviewAt(deckId), overview.latestReviewAt(), overview.deck().getName());
        }
        assertEquals(new DeckOverview(gre, 2, 1, reviewed), overviews.get(0));
        assertEquals(new DeckOverview(sat, 1, 1, null), overviews.get(1));
        assertEquals(new DeckOverview(empty, 0, 0, null), overviews.get(2));
    }
}
