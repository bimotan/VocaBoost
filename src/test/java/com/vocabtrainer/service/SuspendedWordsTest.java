package com.vocabtrainer.service;

import com.vocabtrainer.TestClock;
import com.vocabtrainer.domain.CardState;
import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.ReviewLog;
import com.vocabtrainer.domain.ReviewMode;
import com.vocabtrainer.domain.ReviewRating;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.AchievementRepository;
import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.DeckRepository;
import com.vocabtrainer.repository.GoalRepository;
import com.vocabtrainer.repository.ReviewLogRepository;
import com.vocabtrainer.repository.TestDatabases;
import com.vocabtrainer.repository.WordRepository;
import com.vocabtrainer.service.scheduling.SchedulingOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Suspended words (review finding G3) are in no review queue and no count, but stay in the Word
 * List, backups and the CSV export. Each test compares a deck of words in study with the same deck
 * plus a suspended twin of every word: whatever the first deck shows, the second must show too.
 */
class SuspendedWordsTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 5, 28, 9, 0);

    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    private final TestClock clock = new TestClock(NOW);
    private DatabaseManager databaseManager;
    private DeckRepository decks;
    private WordRepository words;
    private ReviewLogRepository logs;
    private ReviewService service;
    private StatsService stats;
    /** The words in study only. */
    private Deck plain;
    /** The same words, and a suspended twin of each. */
    private Deck mixed;

    @BeforeEach
    void setUp() throws SQLException {
        databaseManager = databases.open(tempDir.resolve("suspended.db"));
        decks = new DeckRepository(databaseManager);
        words = new WordRepository(databaseManager);
        logs = new ReviewLogRepository(databaseManager);
        ReviewScheduler scheduler = new ReviewScheduler(SchedulingOptions.defaults(), new Random(5));
        GoalService goals = new GoalService(new GoalRepository(databaseManager), logs, GoalSettings.inMemory(),
            scheduler.studyDay(), clock);
        service = new ReviewService(words, logs, new SimilarityService(), scheduler, goals,
            new AchievementService(new AchievementRepository(databaseManager), goals, clock), clock, null, new Random(5));
        stats = new StatsService(words, logs, clock, scheduler.studyDay());
        plain = decks.create("Plain");
        mixed = decks.create("Mixed");
        for (Deck deck : List.of(plain, mixed)) {
            addEveryKind(deck, "", false);
        }
        addEveryKind(mixed, " suspended", true);
    }

    /** A learning card at its step, a due review, a mastered review, a relearning (weak) card and two new cards. */
    private void addEveryKind(Deck deck, String suffix, boolean suspended) throws SQLException {
        List<WordCard> cards = new ArrayList<>();
        cards.add(card(deck, "learning" + suffix, CardState.LEARNING, 0.5, 5, NOW.minusMinutes(3)));
        cards.add(card(deck, "review" + suffix, CardState.REVIEW, 4, 5, NOW.minusHours(1)));
        cards.add(card(deck, "mastered" + suffix, CardState.REVIEW, 40, 4, NOW.plusDays(20)));
        cards.add(card(deck, "relearning" + suffix, CardState.RELEARNING, 1, 8, NOW.plusMinutes(5)));
        cards.add(card(deck, "new" + suffix, CardState.NEW, 0, 0, NOW.minusDays(1)));
        cards.add(card(deck, "newer" + suffix, CardState.NEW, 0, 0, NOW.minusHours(2)));
        for (WordCard card : cards) {
            card.setSuspended(suspended);
            WordCard saved = words.insert(card);
            if (card.getState() != CardState.NEW) {
                logs.insert(new ReviewLog(0, saved.getId(), NOW.minusDays(3), "词", "词", 0.4, ReviewRating.AGAIN, 900));
            }
        }
    }

    private static WordCard card(Deck deck, String english, CardState state, double stability, double difficulty,
                                 LocalDateTime due) {
        WordCard card = WordCard.createNew(deck.getId(), english, english.startsWith("new") ? "新" : "旧");
        card.setAddedAt(due.minusDays(10));
        card.setState(state);
        card.setStability(stability);
        card.setDifficulty(difficulty);
        if (state != CardState.NEW) {
            card.setRepetitions(4);
            card.setConsecutiveCorrect(state == CardState.RELEARNING ? 0 : 4);
            card.setLastReviewedAt(NOW.minusDays(3));
        }
        card.setNextReviewAt(due);
        return card;
    }

    @Test
    void theReviewQueuesSkipSuspendedWords() {
        for (ReviewMode mode : List.of(ReviewMode.EN_TO_ZH, ReviewMode.MIXED, ReviewMode.WEAK_WORDS)) {
            assertEquals(session(plain, mode), session(mixed, mode), mode.name());
        }
        assertFalse(session(mixed, ReviewMode.EN_TO_ZH).isEmpty());
    }

    /** The words a session shows, in order, each answered and rated Good 30 seconds after the last, until it ends. */
    private List<String> session(Deck deck, ReviewMode mode) {
        clock.set(NOW);
        service.startSession(deck.getId(), mode, 0);
        List<String> shown = new ArrayList<>();
        for (int count = 0; count < 30; count++) {
            Optional<WordCard> next = service.nextWord(deck.getId(), mode);
            if (next.isEmpty()) {
                break;
            }
            WordCard word = next.get();
            shown.add(word.getEnglish());
            assertFalse(word.isSuspended(), word.getEnglish());
            boolean english = service.currentQuestionMode() == ReviewMode.ZH_TO_EN;
            service.submitAnswer(word.getId(), english ? word.getEnglish() : word.getChinese(), mode);
            service.rateCurrent(word.getId(), ReviewRating.GOOD);
            clock.advance(Duration.ofSeconds(30));
        }
        // Take every rating back, so the next mode starts from the same cards.
        while (service.canUndo()) {
            service.undoLast();
        }
        return shown;
    }

    @Test
    void dueCountsLeaveSuspendedWordsOut() {
        assertEquals(service.queueCounts(plain.getId()), service.queueCounts(mixed.getId()));
        DashboardStats plainStats = stats.dashboardStats(plain.getId());
        DashboardStats mixedStats = stats.dashboardStats(mixed.getId());
        assertEquals(plainStats.dueToday(), mixedStats.dueToday());
        assertEquals(plainStats.dueReviews(), mixedStats.dueReviews());
        assertEquals(stats.overdueCount(plain.getId()), stats.overdueCount(mixed.getId()));
        List<DeckOverview> overviews = stats.deckOverviews(List.of(plain, mixed));
        assertEquals(overviews.get(0).due(), overviews.get(1).due());
        assertEquals(12, overviews.get(1).words(), "the deck's words, suspended ones included");
        assertEquals(12, mixedStats.totalWords());
        assertEquals(6, mixedStats.suspendedWords());
        assertEquals(0, plainStats.suspendedWords());
        assertTrue(plainStats.dueToday() > 0);
    }

    @Test
    void newCardCountsLeaveSuspendedWordsOut() {
        ReviewQueueCounts plainQueue = service.queueCounts(plain.getId());
        ReviewQueueCounts mixedQueue = service.queueCounts(mixed.getId());

        assertEquals(2, mixedQueue.newCardsDue());
        assertEquals(plainQueue.newAvailableToday(), mixedQueue.newAvailableToday());
        assertEquals(stats.dashboardStats(plain.getId()).newAvailableToday(),
            stats.dashboardStats(mixed.getId()).newAvailableToday());
    }

    @Test
    void weakListsLeaveSuspendedWordsOut() throws SQLException {
        assertEquals(english(words.findWeak(plain.getId(), 100)), english(words.findWeak(mixed.getId(), 100)));
        assertEquals(List.of("relearning"), english(words.findWeak(mixed.getId(), 100)));
    }

    @Test
    void statisticsLeaveSuspendedWordsOut() throws SQLException {
        assertEquals(stats.dashboardStats(plain.getId()).masteredWords(), stats.dashboardStats(mixed.getId()).masteredWords());
        assertEquals(1, stats.dashboardStats(mixed.getId()).masteredWords());
        assertEquals(stats.memoryDistribution(plain.getId()), stats.memoryDistribution(mixed.getId()));
        assertEquals(stats.hardestWords(plain.getId(), 10).stream().map(stat -> stat.english()).toList(),
            stats.hardestWords(mixed.getId(), 10).stream().map(stat -> stat.english()).toList());
    }

    @Test
    void theWordListBackupsAndCsvExportKeepSuspendedWords() throws Exception {
        assertEquals(12, words.search(mixed.getId(), "").size());
        assertEquals(6, words.search(mixed.getId(), "suspended").size());

        BackupService backups = new BackupService(decks, words, logs, new GoalRepository(databaseManager),
            new AchievementRepository(databaseManager), databaseManager, new WordValidationService(), clock);
        Path json = backups.exportJsonBackup(mixed.getId(), tempDir.resolve("mixed.json"));
        Deck restored = decks.create("Restored");
        backups.importJsonBackup(json, restored.getId());
        List<WordCard> restoredWords = words.findAllIncludingSuspended(restored.getId());
        assertEquals(12, restoredWords.size());
        assertEquals(6, restoredWords.stream().filter(WordCard::isSuspended).count());
        assertTrue(restoredWords.stream().allMatch(word -> word.isSuspended() == word.getEnglish().endsWith(" suspended")));

        Path csv = backups.exportWordsCsv(mixed.getId(), tempDir.resolve("mixed.csv"));
        String exported = Files.readString(csv, StandardCharsets.UTF_8);
        assertTrue(exported.contains("relearning suspended"), exported);
        assertEquals(13, exported.lines().count(), "the header and every word");
    }

    @Test
    void anUnsuspendedWordIsReviewedAgain() throws SQLException {
        WordCard twin = words.findByEnglish(mixed.getId(), "review suspended").orElseThrow();
        assertTrue(twin.isSuspended());
        int due = service.queueCounts(mixed.getId()).reviewsDue();

        assertEquals(1, words.setSuspended(List.of(twin.getId()), false));
        assertEquals(0, words.setSuspended(List.of(twin.getId()), false), "it is not suspended any more");

        assertEquals(due + 1, service.queueCounts(mixed.getId()).reviewsDue());
        assertTrue(session(mixed, ReviewMode.EN_TO_ZH).contains("review suspended"));
    }

    private static List<String> english(List<WordCard> cards) {
        return cards.stream().map(WordCard::getEnglish).toList();
    }
}
