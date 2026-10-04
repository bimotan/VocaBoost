package com.vocabtrainer.service;

import com.vocabtrainer.TestClock;
import com.vocabtrainer.domain.CardState;
import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.ReviewKind;
import com.vocabtrainer.domain.ReviewLog;
import com.vocabtrainer.domain.ReviewMode;
import com.vocabtrainer.domain.ReviewRating;
import com.vocabtrainer.domain.ReviewSessionSummary;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.AchievementRepository;
import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.DeckRepository;
import com.vocabtrainer.repository.GoalRepository;
import com.vocabtrainer.repository.ReviewLogRepository;
import com.vocabtrainer.repository.TestDatabases;
import com.vocabtrainer.repository.WordRepository;
import com.vocabtrainer.service.scheduling.CardScheduler;
import com.vocabtrainer.service.scheduling.IntervalPreview;
import com.vocabtrainer.service.scheduling.SchedulingOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The answer check caps a rating, the review log keeps both the chosen and the effective rating,
 * and the user can override the check (review findings A4 and C4); every count of correct answers
 * uses the effective rating.
 */
class AnswerCheckTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 3, 10, 9, 0);
    private static final CardScheduler CARDS = new CardScheduler(SchedulingOptions.defaults());
    private static final String LUCID = "清晰的; 明白易懂的";

    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    private final TestClock clock = new TestClock(NOW);
    private Deck deck;
    private WordRepository words;
    private ReviewLogRepository logs;
    private GoalService goals;
    private StatsService stats;
    private ReviewService service;

    @BeforeEach
    void setUp() throws SQLException {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("check.db"));
        deck = new DeckRepository(databaseManager).ensureDefaultDeck();
        words = new WordRepository(databaseManager);
        logs = new ReviewLogRepository(databaseManager);
        goals = new GoalService(new GoalRepository(databaseManager), logs, clock);
        AchievementService achievements = new AchievementService(new AchievementRepository(databaseManager), goals, clock);
        stats = new StatsService(words, logs, clock);
        service = new ReviewService(words, logs, new SimilarityService(), new ReviewScheduler(), goals, achievements,
            clock, null, new Random(1));
    }

    @Test
    void aCappedRatingShowsAndSchedulesWhatItCountsAs() throws SQLException {
        // Finding C4: the synonym 清楚 for lucid, rated Good on a mature card, became a silent lapse.
        WordCard lucid = words.insert(dueReview("lucid", LUCID));
        CardScheduler.Outcome again = CARDS.outcome(lucid, ReviewRating.AGAIN, NOW);

        ReviewAnswer answer = service.submitAnswer(lucid.getId(), "清楚", ReviewMode.EN_TO_ZH);
        Map<ReviewRating, IntervalPreview> previews = service.previewRatings(lucid.getId());
        service.rateCurrent(lucid.getId(), ReviewRating.GOOD);

        assertTrue(answer.canOverride());
        assertEquals(ReviewRating.AGAIN, answer.suggestedRating(false));
        assertEquals(ReviewRating.AGAIN, answer.countsAs(ReviewRating.GOOD, false));
        assertEquals(previews.get(ReviewRating.AGAIN), previews.get(ReviewRating.GOOD));
        ReviewLog log = onlyLog(lucid);
        assertEquals(ReviewRating.GOOD, log.getRating(), "the log keeps what the user chose");
        assertEquals(ReviewRating.AGAIN, log.getEffectiveRating());
        assertEquals(ReviewRating.AGAIN, log.getRecordedEffectiveRating());
        assertFalse(log.isOverridden());
        assertFalse(log.isCorrect());
        WordCard after = words.findById(lucid.getId()).orElseThrow();
        assertEquals(lucid.getLapses() + 1, after.getLapses());
        assertEquals(again.stability(), after.getStability(), 1e-9);
    }

    @Test
    void iWasRightSchedulesTheChosenRatingAndTheLogSaysSo() throws SQLException {
        WordCard lucid = words.insert(dueReview("lucid", LUCID));
        CardScheduler.Outcome good = CARDS.outcome(lucid, ReviewRating.GOOD, NOW);

        ReviewAnswer answer = service.submitAnswer(lucid.getId(), "清楚", ReviewMode.EN_TO_ZH);
        Map<ReviewRating, IntervalPreview> previews = service.previewRatings(lucid.getId(), true);
        service.rateCurrent(lucid.getId(), ReviewRating.GOOD, true);

        assertEquals(ReviewRating.GOOD, answer.suggestedRating(true));
        assertEquals(IntervalPreview.days(good.intervalDays()), previews.get(ReviewRating.GOOD));
        ReviewLog log = onlyLog(lucid);
        assertEquals(ReviewRating.GOOD, log.getRating());
        assertEquals(ReviewRating.GOOD, log.getEffectiveRating());
        assertTrue(log.isOverridden());
        assertTrue(log.isCorrect());
        assertEquals(0.25, log.getSimilarity(), 1e-9, "the measured similarity is kept");
        WordCard after = words.findById(lucid.getId()).orElseThrow();
        assertEquals(lucid.getLapses(), after.getLapses());
        assertEquals(CardState.REVIEW, after.getState());
        assertEquals(good.stability(), after.getStability(), 1e-9);
        assertEquals(good.intervalDays(), after.getIntervalDays());
    }

    @Test
    void anOverrideOfAnAnswerTheCheckDidNotCapIsNotRecorded() throws SQLException {
        WordCard lucid = words.insert(dueReview("lucid", LUCID));

        ReviewAnswer answer = service.submitAnswer(lucid.getId(), "清晰", ReviewMode.EN_TO_ZH);
        service.rateCurrent(lucid.getId(), ReviewRating.EASY, true);

        assertFalse(answer.canOverride());
        assertEquals(ReviewRating.GOOD, answer.suggestedRating(false));
        ReviewLog log = onlyLog(lucid);
        assertFalse(log.isOverridden());
        assertEquals(ReviewRating.EASY, log.getEffectiveRating());
    }

    @Test
    void anOverrideIsRecordedOnlyWhenItChangesWhatTheRatingCountsAs() throws SQLException {
        WordCard lucid = words.insert(dueReview("lucid", LUCID));
        WordCard abate = words.insert(dueReview("abate", "减弱; 减少"));

        // "I was right", then Again: an ordinary Again, not a claim that the answer was right.
        service.submitAnswer(lucid.getId(), "清楚", ReviewMode.EN_TO_ZH);
        service.rateCurrent(lucid.getId(), ReviewRating.AGAIN, true);
        // A typo caps at Hard: overriding it and choosing Hard changes nothing either.
        ReviewAnswer typo = service.submitAnswer(abate.getId(), "abat", ReviewMode.ZH_TO_EN);
        service.rateCurrent(abate.getId(), ReviewRating.HARD, true);

        assertEquals(ReviewRating.HARD, typo.grade().maxRating());
        assertFalse(typo.overrideApplies(ReviewRating.HARD, true));
        assertTrue(typo.overrideApplies(ReviewRating.GOOD, true));
        assertFalse(typo.overrideApplies(ReviewRating.GOOD, false));
        ReviewLog again = onlyLog(lucid);
        assertEquals(ReviewRating.AGAIN, again.getEffectiveRating());
        assertFalse(again.isOverridden());
        ReviewLog hard = onlyLog(abate);
        assertEquals(ReviewRating.HARD, hard.getEffectiveRating());
        assertFalse(hard.isOverridden());
    }

    @Test
    void aTypoCountsAtMostAsHard() throws SQLException {
        WordCard lucid = words.insert(dueReview("lucid", LUCID));
        CardScheduler.Outcome hard = CARDS.outcome(lucid, ReviewRating.HARD, NOW);

        ReviewAnswer answer = service.submitAnswer(lucid.getId(), "lucud", ReviewMode.ZH_TO_EN);
        Map<ReviewRating, IntervalPreview> previews = service.previewRatings(lucid.getId());
        service.rateCurrent(lucid.getId(), ReviewRating.GOOD);

        assertEquals(AnswerGrade.Verdict.MISSPELLED, answer.grade().verdict());
        assertEquals(ReviewRating.HARD, answer.suggestedRating(false));
        assertEquals(IntervalPreview.days(hard.intervalDays()), previews.get(ReviewRating.GOOD));
        ReviewLog log = onlyLog(lucid);
        assertEquals(0.8, log.getSimilarity(), 1e-9);
        assertEquals(ReviewRating.HARD, log.getEffectiveRating());
        assertTrue(log.isCorrect());
        assertEquals(hard.intervalDays(), words.findById(lucid.getId()).orElseThrow().getIntervalDays());
    }

    @Test
    void aSynonymFromTheDeckThatFitsThePromptCountsAsRight() throws SQLException {
        // Finding E12: 反复无常的 is a meaning of both, so the prompt allows either word.
        WordCard capricious = words.insert(dueReview("capricious", "反复无常的; 任性的"));
        words.insert(dueReview("mercurial", "善变的; 反复无常的"));

        ReviewAnswer answer = service.submitAnswer(capricious.getId(), "Mercurial", ReviewMode.ZH_TO_EN);
        service.rateCurrent(capricious.getId(), ReviewRating.GOOD);

        assertEquals(AnswerGrade.Verdict.SYNONYM, answer.grade().verdict());
        assertEquals("mercurial", answer.grade().otherWord());
        assertFalse(answer.canOverride());
        ReviewLog log = onlyLog(capricious);
        assertEquals(1.0, log.getSimilarity());
        assertEquals("Mercurial", log.getUserAnswer());
        assertEquals("capricious", log.getCorrectAnswer());
        assertEquals(ReviewRating.GOOD, log.getEffectiveRating());
        assertEquals(0, words.findById(capricious.getId()).orElseThrow().getLapses());
    }

    @Test
    void anotherWordOfTheDeckCountsAsAgainHoweverCloseItsSpelling() throws SQLException {
        WordCard lucid = words.insert(dueReview("lucid", LUCID));
        words.insert(dueReview("lurid", "耸人听闻的; 可怕的"));

        ReviewAnswer answer = service.submitAnswer(lucid.getId(), "lurid", ReviewMode.ZH_TO_EN);
        service.rateCurrent(lucid.getId(), ReviewRating.GOOD);

        assertEquals(AnswerGrade.Verdict.CONFUSABLE, answer.grade().verdict());
        assertEquals(0.0, onlyLog(lucid).getSimilarity());
        assertEquals(ReviewRating.AGAIN, onlyLog(lucid).getEffectiveRating());
        assertEquals(lucid.getLapses() + 1, words.findById(lucid.getId()).orElseThrow().getLapses());
    }

    @Test
    void sessionGoalsStatisticsAndTheReportCountTheSameAnswersAsCorrect() throws SQLException {
        // Before, a Good capped to Again was wrong for the session and the goal but right for the
        // dashboard, the daily chart and the report, and an overridden one was wrong everywhere.
        WordCard capped = words.insert(dueReview("lucid", LUCID));
        WordCard overridden = words.insert(dueReview("limpid", "清澈的; 明晰的"));
        WordCard right = words.insert(dueReview("abate", "减弱; 减少"));
        WordCard again = words.insert(dueReview("laud", "赞扬"));
        rate(capped, "清楚", ReviewRating.GOOD, false);
        rate(overridden, "透明", ReviewRating.GOOD, true);
        rate(right, "减少", ReviewRating.GOOD, false);
        rate(again, "赞扬", ReviewRating.AGAIN, false);

        List<ReviewLog> history = logs.findByDeck(deck.getId());
        assertEquals(List.of(false, true, true, false), history.stream().map(ReviewLog::isCorrect).toList());
        ReviewSessionSummary session = service.sessionSummary();
        assertEquals(4, session.reviewedCount());
        assertEquals(2, session.correctCount());
        assertEquals(2, goals.getTodayProgress(deck.getId()).correctCount());
        assertEquals(0.5, stats.dashboardStats(deck.getId()).accuracyToday(), 1e-9);
        assertEquals(0.5, stats.dailyReviewStats(deck.getId(), 1).get(0).accuracy(), 1e-9);
        assertEquals(0.5, stats.dailyReviewStats(1).get(0).accuracy(), 1e-9);
        String report = stats.buildMarkdownReport(deck.getId());
        assertTrue(report.contains("- Accuracy today: 50%"), report);
        assertTrue(report.contains("- lucid: avg similarity 25%, Again 1"), report);
        assertTrue(report.contains("- limpid: avg similarity 0%, Again 0"), report);
        assertTrue(report.contains("- laud: avg similarity 100%, Again 1"), report);
    }

    @Test
    void replayingAHistoryUsesTheRecordedEffectiveRating() throws SQLException {
        WordCard overridden = words.insert(WordCard.createNew(deck.getId(), "lucid", LUCID, clock.now()));
        WordCard legacy = words.insert(WordCard.createNew(deck.getId(), "limpid", "清澈的", clock.now()));
        LocalDateTime first = NOW.minusDays(20);
        logs.insert(new ReviewLog(0, overridden.getId(), first, "清楚", LUCID, 0.25, ReviewRating.GOOD, 0,
            ReviewKind.LEARN, ReviewMode.EN_TO_ZH, ReviewRating.GOOD, true));
        logs.insert(new ReviewLog(0, overridden.getId(), first.plusDays(5), "清楚", LUCID, 0.25, ReviewRating.GOOD, 0,
            ReviewKind.REVIEW, ReviewMode.EN_TO_ZH, ReviewRating.GOOD, true));
        logs.insert(new ReviewLog(0, legacy.getId(), first, "清澈的", "清澈的", 1.0, ReviewRating.GOOD, 0));
        logs.insert(new ReviewLog(0, legacy.getId(), first.plusDays(5), "清楚", "清澈的", 0.25, ReviewRating.GOOD, 0));
        ReviewScheduler scheduler = new ReviewScheduler();

        scheduler.replay(overridden, logs.findByWord(overridden.getId()));
        scheduler.replay(legacy, logs.findByWord(legacy.getId()));

        assertEquals(0, overridden.getLapses(), "an overridden Good is a Good");
        assertEquals(1, legacy.getLapses(), "an old log of a Good at 25% similarity counted as Again");
        assertNull(logs.findByWord(legacy.getId()).get(1).getRecordedEffectiveRating());
        assertEquals(ReviewRating.AGAIN, logs.findByWord(legacy.getId()).get(1).getEffectiveRating());
    }

    private void rate(WordCard word, String answer, ReviewRating rating, boolean overridden) {
        service.submitAnswer(word.getId(), answer, ReviewMode.EN_TO_ZH);
        service.rateCurrent(word.getId(), rating, overridden);
    }

    private ReviewLog onlyLog(WordCard word) throws SQLException {
        List<ReviewLog> history = logs.findByWord(word.getId());
        assertEquals(1, history.size());
        return history.get(0);
    }

    /** A mature card, due today: last reviewed 45 days ago at a stability of 45 days. */
    private WordCard dueReview(String english, String chinese) {
        WordCard card = WordCard.createNew(deck.getId(), english, chinese, clock.now());
        card.setState(CardState.REVIEW);
        card.setStability(45);
        card.setDifficulty(5);
        card.setRepetitions(6);
        card.setConsecutiveCorrect(6);
        card.setIntervalDays(45);
        card.setLastReviewedAt(NOW.minusDays(45));
        card.setNextReviewAt(NOW.minusHours(5));
        return card;
    }
}
