package com.vocabtrainer.service;

import com.vocabtrainer.domain.Achievement;
import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.ReviewLog;
import com.vocabtrainer.domain.ReviewMode;
import com.vocabtrainer.domain.ReviewOutcome;
import com.vocabtrainer.domain.ReviewRating;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.AchievementRepository;
import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.DeckRepository;
import com.vocabtrainer.repository.GoalRepository;
import com.vocabtrainer.repository.ReviewLogRepository;
import com.vocabtrainer.repository.TestDatabases;
import com.vocabtrainer.repository.WordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewRatingTransactionTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-28T09:00:00Z"), ZoneId.of("UTC"));

    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    private DatabaseManager databaseManager;
    private Deck deck;
    private WordRepository wordRepository;
    private FailingReviewLogRepository logRepository;
    private FailingAchievementRepository achievementRepository;
    private GoalService goalService;
    private AchievementService achievementService;
    private ReviewService service;
    private WordCard word;

    @BeforeEach
    void setUp() throws Exception {
        databaseManager = databases.open(tempDir.resolve("rating.db"));
        deck = new DeckRepository(databaseManager).ensureDefaultDeck();
        wordRepository = new WordRepository(databaseManager);
        logRepository = new FailingReviewLogRepository(databaseManager);
        achievementRepository = new FailingAchievementRepository(databaseManager);
        goalService = new GoalService(new GoalRepository(databaseManager), logRepository, CLOCK);
        achievementService = new AchievementService(achievementRepository, goalService, CLOCK);
        service = new ReviewService(wordRepository, logRepository, new SimilarityService(), new ReviewScheduler(),
            goalService, achievementService, CLOCK);
        word = wordRepository.save(WordCard.createNew(deck.getId(), "lucid", "清晰的", LocalDateTime.now(CLOCK)));
        service.startSession(deck.getId(), ReviewMode.EN_TO_ZH, 5);
    }

    @Test
    void failedLogInsertSavesNothingAndRetryReusesSubmittedAnswer() throws Exception {
        WordCard before = storedWord();
        ReviewAnswer answer = service.submitAnswer(word.getId(), "清晰的", ReviewMode.EN_TO_ZH);
        assertEquals(1.0, answer.similarity());

        logRepository.failNextInsert = true;
        assertThrows(IllegalStateException.class, () -> service.rateCurrent(word.getId(), ReviewRating.GOOD));

        assertSameSchedule(before, storedWord());
        assertEquals(0, logCount());
        assertEquals(0, goalService.getTodayProgress(deck.getId()).reviewedCount());
        assertEquals(0, service.sessionSummary().reviewedCount());

        // The user clicks Good again: the original answer and similarity must be used.
        ReviewOutcome outcome = service.rateCurrent(word.getId(), ReviewRating.GOOD);

        WordCard after = storedWord();
        assertEquals(0, after.getLapses());
        assertEquals(1, after.getConsecutiveCorrect());
        assertEquals(1, after.getRepetitions());
        assertEquals(1, logCount());
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT user_answer, similarity, rating FROM review_logs WHERE word_id = ?")) {
            statement.setLong(1, word.getId());
            try (ResultSet rs = statement.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("清晰的", rs.getString("user_answer"));
                assertEquals(1.0, rs.getDouble("similarity"));
                assertEquals("GOOD", rs.getString("rating"));
            }
        }
        assertEquals(1, goalService.getTodayProgress(deck.getId()).reviewedCount());
        assertEquals(1, outcome.sessionSummary().reviewedCount());
        assertEquals(1, outcome.sessionSummary().correctCount());
    }

    @Test
    void failedAchievementUnlockRollsBackScheduleLogAndGoalProgress() throws Exception {
        WordCard before = storedWord();
        service.submitAnswer(word.getId(), "清晰的", ReviewMode.EN_TO_ZH);

        achievementRepository.failNextInsert = true;
        assertThrows(IllegalStateException.class, () -> service.rateCurrent(word.getId(), ReviewRating.GOOD));

        assertSameSchedule(before, storedWord());
        assertEquals(0, logCount());
        assertEquals(0, goalService.getTodayProgress(deck.getId()).reviewedCount());
        assertEquals(0, goalService.totalXp(deck.getId()));
        assertTrue(achievementService.getUnlockedAchievements(deck.getId()).isEmpty());

        ReviewOutcome outcome = service.rateCurrent(word.getId(), ReviewRating.GOOD);

        assertEquals(0, storedWord().getLapses());
        assertEquals(1, logCount());
        assertEquals(1, goalService.getTodayProgress(deck.getId()).reviewedCount());
        assertTrue(outcome.unlockedAchievements().stream().anyMatch(a -> a.code().equals("first_review")));
        // Review XP and achievement XP were both written exactly once.
        assertEquals(outcome.xpEarned(), goalService.totalXp(deck.getId()));
    }

    @Test
    void ratingWithoutSubmittedAnswerIsRejectedInsteadOfRecordedAsLapse() throws Exception {
        WordCard before = storedWord();

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> service.rateCurrent(word.getId(), ReviewRating.GOOD));

        assertTrue(error.getMessage().contains("submit"), error.getMessage());
        assertSameSchedule(before, storedWord());
        assertEquals(0, logCount());
    }

    @Test
    void submittedAnswerIsKeptUntilTheRatingIsSaved() {
        service.submitAnswer(word.getId(), "清晰的", ReviewMode.EN_TO_ZH);
        logRepository.failNextInsert = true;

        assertThrows(IllegalStateException.class, () -> service.rateCurrent(word.getId(), ReviewRating.GOOD));
        assertTrue(service.hasPendingAnswer(word.getId()));

        service.rateCurrent(word.getId(), ReviewRating.GOOD);
        assertFalse(service.hasPendingAnswer(word.getId()));
    }

    @Test
    void ratingsReusePooledConnectionsInsteadOfOpeningNewOnes() {
        service.submitAnswer(word.getId(), "清晰的", ReviewMode.EN_TO_ZH);
        service.rateCurrent(word.getId(), ReviewRating.GOOD);
        long opened = databaseManager.connectionsOpened();

        for (String english : new String[] {"abate", "candid", "laconic"}) {
            WordCard next = saveWord(english);
            service.submitAnswer(next.getId(), "释义", ReviewMode.EN_TO_ZH);
            service.rateCurrent(next.getId(), ReviewRating.HARD);
            goalService.getTodayProgress(deck.getId());
        }

        assertEquals(opened, databaseManager.connectionsOpened());
    }

    private WordCard saveWord(String english) {
        try {
            return wordRepository.save(WordCard.createNew(deck.getId(), english, "释义", LocalDateTime.now(CLOCK)));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private WordCard storedWord() throws SQLException {
        return wordRepository.findById(word.getId()).orElseThrow();
    }

    private int logCount() throws SQLException {
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT COUNT(*) FROM review_logs WHERE word_id = ?")) {
            statement.setLong(1, word.getId());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private static void assertSameSchedule(WordCard expected, WordCard actual) {
        assertEquals(expected.getNextReviewAt(), actual.getNextReviewAt());
        assertEquals(expected.getLastReviewedAt(), actual.getLastReviewedAt());
        assertEquals(expected.getIntervalDays(), actual.getIntervalDays());
        assertEquals(expected.getRepetitions(), actual.getRepetitions());
        assertEquals(expected.getConsecutiveCorrect(), actual.getConsecutiveCorrect());
        assertEquals(expected.getLapses(), actual.getLapses());
        assertEquals(expected.getEasinessFactor(), actual.getEasinessFactor());
    }

    private static final class FailingReviewLogRepository extends ReviewLogRepository {
        private boolean failNextInsert;

        private FailingReviewLogRepository(DatabaseManager databaseManager) {
            super(databaseManager);
        }

        @Override
        public ReviewLog insert(ReviewLog log) throws SQLException {
            if (failNextInsert) {
                failNextInsert = false;
                throw new SQLException("[SQLITE_BUSY] simulated lock while inserting review log");
            }
            return super.insert(log);
        }
    }

    private static final class FailingAchievementRepository extends AchievementRepository {
        private boolean failNextInsert;

        private FailingAchievementRepository(DatabaseManager databaseManager) {
            super(databaseManager);
        }

        @Override
        public boolean insertIfAbsent(long deckId, Achievement achievement) throws SQLException {
            if (failNextInsert) {
                failNextInsert = false;
                throw new SQLException("[SQLITE_BUSY] simulated lock while unlocking achievement");
            }
            return super.insertIfAbsent(deckId, achievement);
        }
    }
}
