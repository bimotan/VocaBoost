package com.vocabtrainer.service;

import com.vocabtrainer.domain.DailyReviewStat;
import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.HardWordStat;
import com.vocabtrainer.domain.ReviewLog;
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
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class StatsServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-28T09:00:00Z"), ZoneId.of("UTC"));
    private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);

    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    @Test
    void dailyReviewStatsCanBeScopedToDeck() throws Exception {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("stats.db"));
        DeckRepository deckRepository = new DeckRepository(databaseManager);
        WordRepository wordRepository = new WordRepository(databaseManager);
        ReviewLogRepository reviewLogRepository = new ReviewLogRepository(databaseManager);

        Deck defaultDeck = deckRepository.ensureDefaultDeck();
        Deck satDeck = deckRepository.create("SAT");
        WordCard defaultWord = wordRepository.save(WordCard.createNew(defaultDeck.getId(), "abate", "减弱"));
        WordCard satWord = wordRepository.save(WordCard.createNew(satDeck.getId(), "lucid", "清晰的"));

        reviewLogRepository.insert(new ReviewLog(0, defaultWord.getId(), NOW, "减弱", "减弱", 1,
            ReviewRating.GOOD, 1000));
        reviewLogRepository.insert(new ReviewLog(0, satWord.getId(), NOW, "错", "清晰的", 0,
            ReviewRating.AGAIN, 1000));

        StatsService statsService = new StatsService(wordRepository, reviewLogRepository, CLOCK);

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
    void reviewCurveCoversEachDayFromItsFirstMoment() throws Exception {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("curve.db"));
        WordRepository wordRepository = new WordRepository(databaseManager);
        ReviewLogRepository logs = new ReviewLogRepository(databaseManager);
        Deck deck = new DeckRepository(databaseManager).ensureDefaultDeck();
        Deck other = new DeckRepository(databaseManager).create("Other");
        WordCard word = wordRepository.save(WordCard.createNew(deck.getId(), "lucid", "清晰的"));
        WordCard otherWord = wordRepository.save(WordCard.createNew(other.getId(), "abate", "减弱"));
        LocalDate firstDay = NOW.toLocalDate().minusDays(2);
        log(logs, word, firstDay.minusDays(1).atTime(23, 59, 59, 999_000_000), ReviewRating.GOOD);
        log(logs, word, firstDay.atStartOfDay(), ReviewRating.AGAIN);
        log(logs, word, firstDay.atTime(12, 0), ReviewRating.GOOD);
        log(logs, word, NOW, ReviewRating.HARD);
        log(logs, otherWord, NOW, ReviewRating.AGAIN);
        StatsService statsService = new StatsService(wordRepository, logs, CLOCK);

        List<DailyReviewStat> deckCurve = statsService.dailyReviewStats(deck.getId(), 3);
        assertEquals(List.of(firstDay, firstDay.plusDays(1), NOW.toLocalDate()),
            deckCurve.stream().map(DailyReviewStat::date).toList());
        assertEquals(List.of(2, 0, 1), deckCurve.stream().map(DailyReviewStat::reviewCount).toList());
        assertEquals(0.5, deckCurve.get(0).accuracy());
        assertEquals(List.of(2, 0, 2), statsService.dailyReviewStats(3).stream().map(DailyReviewStat::reviewCount).toList());
    }

    @Test
    void statisticsWorkWithTheRepositoriesAlone() throws Exception {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("repositories.db"));
        WordRepository wordRepository = new WordRepository(databaseManager);
        ReviewLogRepository logs = new ReviewLogRepository(databaseManager);
        Deck deck = new DeckRepository(databaseManager).ensureDefaultDeck();
        WordCard hard = wordRepository.save(WordCard.createNew(deck.getId(), "lucid", "清晰的"));
        WordCard easy = wordRepository.save(WordCard.createNew(deck.getId(), "abate", "减弱"));
        log(logs, hard, NOW.minusDays(1), ReviewRating.AGAIN);
        log(logs, easy, NOW.minusHours(1), ReviewRating.EASY);

        // Before, this constructor silently returned no hardest words and no latest review.
        StatsService statsService = new StatsService(wordRepository, logs);

        assertEquals(List.of("lucid", "abate"),
            statsService.hardestWords(deck.getId(), 5).stream().map(HardWordStat::english).toList());
        assertEquals(NOW.minusHours(1), statsService.latestReviewAt(deck.getId()));
        assertNull(statsService.latestReviewAt(new DeckRepository(databaseManager).create("Empty").getId()));
    }

    private static void log(ReviewLogRepository logs, WordCard word, LocalDateTime at, ReviewRating rating)
        throws Exception {
        double similarity = rating == ReviewRating.AGAIN ? 0.0 : 1.0;
        logs.insert(new ReviewLog(0, word.getId(), at, "答", word.getChinese(), similarity, rating, 1000));
    }
}
