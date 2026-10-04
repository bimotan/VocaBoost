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
        WordCard defaultWord = wordRepository.save(WordCard.createNew(defaultDeck.getId(), "abate", "减弱", NOW));
        WordCard satWord = wordRepository.save(WordCard.createNew(satDeck.getId(), "lucid", "清晰的", NOW));

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
    void deckOverviewsMatchThePerDeckCountsAndLatestReview() throws Exception {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("overview.db"));
        DeckRepository deckRepository = new DeckRepository(databaseManager);
        WordRepository wordRepository = new WordRepository(databaseManager);
        ReviewLogRepository reviewLogRepository = new ReviewLogRepository(databaseManager);
        StatsService statsService = new StatsService(wordRepository, reviewLogRepository, CLOCK);
        Deck gre = deckRepository.create("GRE");
        Deck sat = deckRepository.create("SAT");
        Deck empty = deckRepository.create("Empty");
        // Due dates are set relative to CLOCK.
        WordCard due = wordRepository.save(wordDueAt(gre, "abate", "减弱", NOW.minusHours(1)));
        wordRepository.save(wordDueAt(gre, "lucid", "清晰的", NOW.plusDays(3)));
        WordCard suspended = wordDueAt(gre, "gone", "消失的", NOW.minusHours(1));
        suspended.setSuspended(true);
        wordRepository.save(suspended);
        wordRepository.save(wordDueAt(sat, "laud", "赞扬", NOW.minusDays(1)));
        LocalDateTime reviewed = NOW.minusHours(2);
        reviewLogRepository.insert(new ReviewLog(0, due.getId(), reviewed.minusDays(1), "减弱", "减弱", 1,
            ReviewRating.GOOD, 1000));
        reviewLogRepository.insert(new ReviewLog(0, suspended.getId(), reviewed, "消失的", "消失的", 1,
            ReviewRating.GOOD, 1000));

        List<DeckOverview> overviews = statsService.deckOverviews(List.of(gre, sat, empty));

        assertEquals(List.of(gre, sat, empty), overviews.stream().map(DeckOverview::deck).toList());
        for (DeckOverview overview : overviews) {
            long deckId = overview.deck().getId();
            assertEquals(wordRepository.countAll(deckId), overview.words(), overview.deck().getName());
            assertEquals(statsService.dashboardStats(deckId).dueToday(), overview.due(), overview.deck().getName());
            assertEquals(statsService.latestReviewAt(deckId), overview.latestReviewAt(), overview.deck().getName());
        }
        // The suspended word is one of the deck's words, but never due.
        assertEquals(new DeckOverview(gre, 3, 1, reviewed), overviews.get(0));
        assertEquals(new DeckOverview(sat, 1, 1, null), overviews.get(1));
        assertEquals(new DeckOverview(empty, 0, 0, null), overviews.get(2));
    }

    @Test
    void reviewCurveCoversEachStudyDayFromItsFirstMoment() throws Exception {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("curve.db"));
        WordRepository wordRepository = new WordRepository(databaseManager);
        ReviewLogRepository logs = new ReviewLogRepository(databaseManager);
        Deck deck = new DeckRepository(databaseManager).ensureDefaultDeck();
        Deck other = new DeckRepository(databaseManager).create("Other");
        WordCard word = wordRepository.save(WordCard.createNew(deck.getId(), "lucid", "清晰的", NOW));
        WordCard otherWord = wordRepository.save(WordCard.createNew(other.getId(), "abate", "减弱", NOW));
        LocalDate firstDay = NOW.toLocalDate().minusDays(2);
        // A study day starts at 4 am: 3:59 am still belongs to the day before.
        log(logs, word, firstDay.atTime(3, 59, 59, 999_000_000), ReviewRating.GOOD);
        log(logs, word, firstDay.atTime(4, 0), ReviewRating.AGAIN);
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
        WordCard hard = wordRepository.save(WordCard.createNew(deck.getId(), "lucid", "清晰的", NOW));
        WordCard easy = wordRepository.save(WordCard.createNew(deck.getId(), "abate", "减弱", NOW));
        log(logs, hard, NOW.minusDays(1), ReviewRating.AGAIN);
        log(logs, easy, NOW.minusHours(1), ReviewRating.EASY);

        // Before, this constructor silently returned no hardest words and no latest review.
        StatsService statsService = new StatsService(wordRepository, logs);

        assertEquals(List.of("lucid", "abate"),
            statsService.hardestWords(deck.getId(), 5).stream().map(HardWordStat::english).toList());
        assertEquals(NOW.minusHours(1), statsService.latestReviewAt(deck.getId()));
        assertNull(statsService.latestReviewAt(new DeckRepository(databaseManager).create("Empty").getId()));
    }

    private static WordCard wordDueAt(Deck deck, String english, String chinese, LocalDateTime dueAt) {
        WordCard word = WordCard.createNew(deck.getId(), english, chinese, NOW);
        word.setNextReviewAt(dueAt);
        return word;
    }

    private static void log(ReviewLogRepository logs, WordCard word, LocalDateTime at, ReviewRating rating)
        throws Exception {
        double similarity = rating == ReviewRating.AGAIN ? 0.0 : 1.0;
        logs.insert(new ReviewLog(0, word.getId(), at, "答", word.getChinese(), similarity, rating, 1000));
    }
}
