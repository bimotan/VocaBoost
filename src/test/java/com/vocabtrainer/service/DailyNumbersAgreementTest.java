package com.vocabtrainer.service;

import com.vocabtrainer.domain.DailyGoalProgress;
import com.vocabtrainer.domain.DailyReviewStat;
import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.ReviewKind;
import com.vocabtrainer.domain.ReviewLog;
import com.vocabtrainer.domain.ReviewMode;
import com.vocabtrainer.domain.ReviewRating;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.DeckRepository;
import com.vocabtrainer.repository.GoalRepository;
import com.vocabtrainer.repository.ReviewLogRepository;
import com.vocabtrainer.repository.TestDatabases;
import com.vocabtrainer.repository.WordRepository;
import com.vocabtrainer.service.scheduling.StudyDay;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dashboard, the daily goal, the statistics chart and the Markdown report show the same reviews,
 * accuracy and new words for a day, read from the review logs: practice and other decks are left
 * out, a review counts on its study day (from 4 am), and every count of correct answers uses the
 * same rule (review findings B8 and E10).
 */
class DailyNumbersAgreementTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-28T10:00:00Z"), ZoneId.of("UTC"));
    private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);
    private static final LocalDate TODAY = LocalDate.of(2026, 5, 28);

    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    @Test
    void dashboardGoalStatisticsAndReportAgreeOnADayWithTwoDecksAndPractice() throws Exception {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("agreement.db"));
        DeckRepository decks = new DeckRepository(databaseManager);
        WordRepository words = new WordRepository(databaseManager);
        ReviewLogRepository logs = new ReviewLogRepository(databaseManager);
        GoalService goals = new GoalService(new GoalRepository(databaseManager), logs, CLOCK);
        StatsService stats = new StatsService(words, logs, CLOCK, new StudyDay());
        Deck gre = decks.ensureDefaultDeck();
        Deck toefl = decks.create("TOEFL");
        WordCard lucid = words.save(WordCard.createNew(gre.getId(), "lucid", "清晰的", NOW));
        WordCard abate = words.save(WordCard.createNew(gre.getId(), "abate", "减弱", NOW));
        WordCard laud = words.save(WordCard.createNew(toefl.getId(), "laud", "赞扬", NOW));

        // GRE today: four reviews, two of them correct, one of them a new word.
        save(goals, logs, gre, log(lucid, TODAY.atTime(8, 0), ReviewKind.LEARN, ReviewRating.GOOD, null, 1.0));
        save(goals, logs, gre, log(abate, TODAY.atTime(8, 5), ReviewKind.REVIEW, ReviewRating.AGAIN, null, 0.0));
        // Rated Good, but a log without an effective rating counts its similarity: below 55% it was Again.
        save(goals, logs, gre, log(abate, TODAY.atTime(8, 10), ReviewKind.REVIEW, ReviewRating.GOOD, null, 0.3));
        // The user overrode the answer check: it counted as Good.
        save(goals, logs, gre, log(lucid, TODAY.atTime(8, 15), ReviewKind.REVIEW, ReviewRating.GOOD,
            ReviewRating.GOOD, 0.3));
        // Practice of words that were not due is no review.
        practice(goals, logs, gre, log(abate, TODAY.atTime(9, 0), ReviewKind.PRACTICE, ReviewRating.GOOD, null, 1.0));
        practice(goals, logs, gre, log(lucid, TODAY.atTime(9, 5), ReviewKind.PRACTICE, ReviewRating.AGAIN, null, 0.0));
        // The study day before: last night, and 3:30 this morning, before the 4 am rollover.
        save(goals, logs, gre, log(lucid, TODAY.minusDays(1).atTime(23, 0), ReviewKind.REVIEW, ReviewRating.GOOD,
            null, 1.0));
        save(goals, logs, gre, log(abate, TODAY.atTime(3, 30), ReviewKind.REVIEW, ReviewRating.AGAIN, null, 0.0));
        // Another deck.
        save(goals, logs, toefl, log(laud, TODAY.atTime(8, 30), ReviewKind.LEARN, ReviewRating.GOOD, null, 1.0));
        save(goals, logs, toefl, log(laud, TODAY.atTime(8, 45), ReviewKind.REVIEW, ReviewRating.AGAIN, null, 0.0));

        DashboardStats dashboard = stats.dashboardStats(gre.getId());
        DailyGoalProgress progress = goals.getTodayProgress(gre.getId());
        List<DailyReviewStat> curve = stats.dailyReviewStats(gre.getId(), 7);
        DailyReviewStat todayOnChart = curve.get(curve.size() - 1);

        assertEquals(4, dashboard.reviewedToday());
        assertEquals(4, progress.reviewedCount());
        assertEquals(4, todayOnChart.reviewCount());
        assertEquals(2, progress.correctCount());
        assertEquals(0.5, dashboard.accuracyToday());
        assertEquals(0.5, progress.accuracy());
        assertEquals(0.5, todayOnChart.accuracy());
        assertEquals(1, progress.newWordsCount());
        assertEquals(2, curve.get(curve.size() - 2).reviewCount(), "last night and 3:30 am are yesterday");
        assertEquals(6, stats.dailyReviewStats(7).get(6).reviewCount(), "every deck");

        String report = stats.buildMarkdownReport(gre.getId(), gre.getName(), progress);
        assertLine(report, "- Reviews today: 4");
        assertLine(report, "- Accuracy today: 50%");
        assertLine(report, "- Review goal: 4/20");
        assertLine(report, "- New-word goal: 1/5");
        assertLine(report, "- " + TODAY + ": 4 reviews, 50% accuracy");
        assertLine(report, "- " + TODAY.minusDays(1) + ": 2 reviews, 50% accuracy");
        assertLine(report, "- Streak (all decks): 2 days");
    }

    private static void save(GoalService goals, ReviewLogRepository logs, Deck deck, ReviewLog log)
        throws SQLException {
        goals.recordReview(deck.getId(), logs.insert(log));
    }

    private static void practice(GoalService goals, ReviewLogRepository logs, Deck deck, ReviewLog log)
        throws SQLException {
        goals.recordPractice(deck.getId(), logs.insert(log));
    }

    private static ReviewLog log(WordCard word, LocalDateTime at, ReviewKind kind, ReviewRating rating,
                                 ReviewRating effective, double similarity) {
        return new ReviewLog(0, word.getId(), at, "答案", word.getChinese(), similarity, rating, 1000, kind,
            ReviewMode.EN_TO_ZH, effective, effective != null);
    }

    private static void assertLine(String report, String line) {
        assertTrue(report.lines().anyMatch(line::equals), "no line '" + line + "' in:\n" + report);
    }
}
