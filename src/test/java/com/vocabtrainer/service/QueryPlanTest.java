package com.vocabtrainer.service;

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
import com.vocabtrainer.repository.SettingsRepository;
import com.vocabtrainer.repository.TestDatabases;
import com.vocabtrainer.repository.WordRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs what the dashboard, the deck table, the statistics tab, a rating and its undo, a backup
 * restore, a word delete and adding a word do, records every SQL statement the repositories prepare,
 * and checks with EXPLAIN QUERY PLAN that none of them reads the whole review log, goal or word table.
 */
class QueryPlanTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-28T09:00:00Z"), ZoneId.of("UTC"));
    private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);
    /**
     * A full scan of a table that grows with use; SEARCH lines use an index. Scanning the partial
     * index of words without a card state is fine: it is empty once the startup backfill ran.
     */
    private static final Pattern FULL_SCAN = Pattern.compile(
        "^SCAN (review_logs|l|daily_goals|words|w)\\b(?!.*idx_words_without_card_state)");

    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    @Test
    void hotPathsUseIndexesInsteadOfScanningGrowingTables() throws Exception {
        RecordingDatabaseManager databaseManager = databases.track(new RecordingDatabaseManager(tempDir.resolve("plans.db")));
        databaseManager.initialize();
        DeckRepository deckRepository = new DeckRepository(databaseManager);
        WordRepository wordRepository = new WordRepository(databaseManager);
        ReviewLogRepository logRepository = new ReviewLogRepository(databaseManager);
        GoalRepository goalRepository = new GoalRepository(databaseManager);
        AchievementRepository achievementRepository = new AchievementRepository(databaseManager);
        Deck deck = deckRepository.ensureDefaultDeck();
        Deck other = deckRepository.create("Other");
        List<WordCard> words = seed(databaseManager, wordRepository, logRepository, goalRepository, deck, other);
        StatsService stats = new StatsService(wordRepository, logRepository, CLOCK);
        GoalService goals = new GoalService(goalRepository, logRepository, CLOCK);
        AchievementService achievements = new AchievementService(achievementRepository, goals, CLOCK);
        DeckService decks = new DeckService(deckRepository, new SettingsService(new SettingsRepository(databaseManager)));
        BackupService backup = new BackupService(deckRepository, wordRepository, logRepository, goalRepository,
            achievementRepository, databaseManager, new WordValidationService(), CLOCK);
        Path json = backup.exportJsonBackup(deck.getId(), tempDir.resolve("backup.json"));
        databaseManager.statements.clear();

        // Dashboard and deck table.
        stats.dashboardStats(deck.getId());
        goals.getTodayProgress(deck.getId());
        achievements.getUnlockedAchievements(deck.getId());
        for (Deck each : decks.activeDecks()) {
            stats.latestReviewAt(each.getId());
            wordRepository.countAll(each.getId());
            wordRepository.countDue(each.getId(), NOW, NOW.plusDays(1));
        }
        // Statistics tab and report.
        stats.dailyReviewStats(deck.getId(), 7);
        stats.dailyReviewStats(7);
        stats.hardestWords(deck.getId(), 8);
        stats.overdueCount(deck.getId());
        stats.workloadForecast(deck.getId(), 30);
        stats.buildMarkdownReport(deck.getId(), deck.getName(), goals.getTodayProgress(deck.getId()));
        // A rating.
        ReviewService review = new ReviewService(wordRepository, logRepository, new SimilarityService(),
            new ReviewScheduler(), goals, achievements, CLOCK);
        WordCard next = review.nextWord(deck.getId(), ReviewMode.EN_TO_ZH).orElseThrow();
        review.submitAnswer(next.getId(), "释义", ReviewMode.EN_TO_ZH, NOW.minusSeconds(5));
        review.previewRatings(next.getId());
        review.rateCurrent(next.getId(), ReviewRating.GOOD);
        wordRepository.findLearningDueBy(deck.getId(), NOW.plusMinutes(20), 1);
        // Undoing it, marking a new word as known, suspending one, and the Word List with suspended words.
        review.undoLast();
        review.markKnown(review.nextWord(deck.getId(), ReviewMode.EN_TO_ZH).orElseThrow().getId());
        review.suspendWord(review.nextWord(deck.getId(), ReviewMode.EN_TO_ZH).orElseThrow().getId(), true);
        review.undoLast();
        wordRepository.setSuspended(List.of(words.get(3).getId(), words.get(4).getId()), true);
        wordRepository.search(deck.getId(), "");
        wordRepository.search(deck.getId(), "word");
        wordRepository.countSuspended(deck.getId());
        logRepository.countByWords(List.of(words.get(3).getId(), words.get(4).getId()));
        // The review session's queues: due reviews, new cards and the new cards introduced today.
        wordRepository.findDueReviews(deck.getId(), NOW, NOW.plusDays(1), 10);
        wordRepository.findNewCards(deck.getId(), NOW.plusDays(1), 10);
        wordRepository.countDueByState(deck.getId(), NOW, NOW.plusDays(1));
        logRepository.countNewCardsIntroducedSince(deck.getId(), NOW.minusHours(5));
        logRepository.newCardsIntroducedByDeckSince(NOW.minusHours(5));
        review.nextWord(deck.getId(), ReviewMode.WEAK_WORDS);
        wordRepository.countMastered(deck.getId());
        // Startup: deriving the card state of words stored without one, as an older version leaves them.
        try (Connection connection = databaseManager.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE words SET card_state = NULL WHERE id IN (" + words.get(1).getId() + ", "
                + words.get(2).getId() + ")");
        }
        new CardStateBackfill(wordRepository, logRepository, new ReviewScheduler()).run();
        // Restoring the same backup again, and deleting a word with its history.
        backup.importJsonBackup(json, deck.getId());
        wordRepository.deleteById(words.get(0).getId());
        wordRepository.deleteByIds(List.of(words.get(5).getId(), words.get(6).getId()));
        // Adding a word: is it in another deck already?
        wordRepository.findInOtherDecks(words.get(1).getEnglish(), other.getId());

        List<String> problems = new ArrayList<>();
        for (String sql : databaseManager.statements) {
            List<String> plan = plan(databaseManager, sql);
            if (plan.stream().anyMatch(line -> FULL_SCAN.matcher(line).find())) {
                problems.add(sql.strip().replaceAll("\\s+", " ") + System.lineSeparator() + "  -> " + plan);
            }
        }
        assertTrue(databaseManager.statements.size() > 20, "recorded " + databaseManager.statements.size());
        assertTrue(problems.isEmpty(), "full table scans:" + System.lineSeparator()
            + String.join(System.lineSeparator(), problems));
    }

    private static List<WordCard> seed(DatabaseManager databaseManager, WordRepository wordRepository,
                                       ReviewLogRepository logRepository, GoalRepository goalRepository,
                                       Deck deck, Deck other) throws SQLException {
        return databaseManager.inTransaction(() -> {
            List<WordCard> words = new ArrayList<>();
            for (Deck each : List.of(deck, other)) {
                for (int i = 0; i < 50; i++) {
                    WordCard word = WordCard.createNew(each.getId(), "word" + (char) ('a' + i % 26) + (char) ('a' + i / 26), "释义");
                    word.setNextReviewAt(NOW.minusHours(i));
                    words.add(wordRepository.save(word));
                }
            }
            for (int i = 0; i < 400; i++) {
                WordCard word = words.get(i % words.size());
                logRepository.insert(new ReviewLog(0, word.getId(), NOW.minusMinutes(37L * i),
                    "释义", "释义", i % 3 == 0 ? 0.2 : 0.9, i % 3 == 0 ? ReviewRating.AGAIN : ReviewRating.GOOD, 1000));
            }
            for (int i = 1; i <= 10; i++) {
                LocalDate day = NOW.toLocalDate().minusDays(i);
                goalRepository.ensure(deck.getId(), day, 20, 5, 10);
                goalRepository.addProgress(deck.getId(), day, 5, 4, 0, 30);
            }
            return words;
        });
    }

    private static List<String> plan(DatabaseManager databaseManager, String sql) throws SQLException {
        List<String> lines = new ArrayList<>();
        try (Connection connection = databaseManager.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("EXPLAIN QUERY PLAN " + sql)) {
            while (rs.next()) {
                lines.add(rs.getString("detail"));
            }
        }
        return lines;
    }

    /** Records the SQL of every statement prepared through {@link #getConnection()}. */
    private static final class RecordingDatabaseManager extends DatabaseManager {
        private final Set<String> statements = new LinkedHashSet<>();

        private RecordingDatabaseManager(Path file) {
            super(file);
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection connection = super.getConnection();
            return (Connection) Proxy.newProxyInstance(QueryPlanTest.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    if (method.getName().equals("prepareStatement")) {
                        synchronized (statements) {
                            statements.add((String) args[0]);
                        }
                    }
                    try {
                        return method.invoke(connection, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        }
    }
}
