package com.vocabtrainer.service;

import com.vocabtrainer.domain.DailyGoalProgress;
import com.vocabtrainer.domain.GoalTargets;
import com.vocabtrainer.domain.GoalUpdate;
import com.vocabtrainer.domain.ReviewKind;
import com.vocabtrainer.domain.ReviewLog;
import com.vocabtrainer.repository.GoalRepository;
import com.vocabtrainer.repository.ReviewLogRepository;
import com.vocabtrainer.repository.ReviewLogRepository.DailyCount;
import com.vocabtrainer.service.scheduling.StudyDay;

import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Daily goals, XP and the study streak.
 *
 * <p>A day is a study day, which starts at the rollover hour (see {@link StudyDay}). The day's
 * reviews, correct answers and new words are read from the review logs, like every other daily
 * number of the app (see {@link DailyReviews}): a review is any rating except the practice of a word
 * that was not due, and a new word is one reviewed for the first time ({@link ReviewKind#LEARN}).
 * Adding, importing or restoring words earns no XP and counts no new words. The {@code daily_goals}
 * row of a deck's day keeps what the logs cannot tell: the goals of that day, the XP earned and
 * whether the goal was completed (its counters are still written, for older versions).
 *
 * <p>The goals come from {@link GoalSettings}: today's goals are always the current ones, a past
 * day's those of its row. The streak counts the study days with a review in any deck: a user
 * studies, not a deck.
 */
public class GoalService {
    public static final int DEFAULT_REVIEW_GOAL = 20;
    public static final int DEFAULT_NEW_WORD_GOAL = 5;
    /** The session size until the user chooses one. */
    public static final int DEFAULT_SESSION_GOAL = ReviewSettings.DEFAULT_SESSION_SIZE;

    private final GoalRepository goalRepository;
    private final ReviewLogRepository reviewLogRepository;
    private final GoalSettings settings;
    private final StudyDay studyDay;
    private final Clock clock;

    /** Goals kept in memory, starting with the defaults, and study days starting at 4 am. */
    public GoalService(GoalRepository goalRepository, ReviewLogRepository reviewLogRepository, Clock clock) {
        this(goalRepository, reviewLogRepository, GoalSettings.inMemory(), new StudyDay(), clock);
    }

    /**
     * @param settings the goals the user set
     * @param studyDay when a study day starts; the scheduler's, so "today" is the same day everywhere
     */
    public GoalService(GoalRepository goalRepository, ReviewLogRepository reviewLogRepository, GoalSettings settings,
                       StudyDay studyDay, Clock clock) {
        this.goalRepository = goalRepository;
        this.reviewLogRepository = reviewLogRepository;
        this.settings = settings;
        this.studyDay = studyDay;
        this.clock = clock;
    }

    /** The goals the user set, which the Dashboard edits. */
    public GoalSettings settings() {
        return settings;
    }

    /** The study day it is now. */
    public LocalDate today() {
        return studyDay.of(LocalDateTime.now(clock));
    }

    public DailyGoalProgress getTodayProgress(long deckId) {
        return progressFor(deckId, today());
    }

    /** The deck's progress on the study day {@code day}. Only reads: a day without reviews has no row yet. */
    public DailyGoalProgress progressFor(long deckId, LocalDate day) {
        try {
            Optional<GoalRepository.GoalRow> row = goalRepository.find(deckId, day);
            DailyCount reviews = DailyReviews.on(reviewLogRepository, studyDay, deckId, day);
            return progress(deckId, day, row.orElse(null), reviews);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read goal progress", e);
        }
    }

    /**
     * Records the review saved as {@code log}, which must already be stored (in the same
     * transaction): its XP, and the daily goal if this review completed it.
     */
    public GoalUpdate recordReview(long deckId, ReviewLog log) {
        LocalDate day = studyDay.of(log.getReviewedAt());
        try {
            int xp = reviewXp(log);
            GoalTargets goals = settings.goalsFor(deckId);
            GoalRepository.GoalRow row = addProgress(deckId, day, goals, 1, log.isCorrect() ? 1 : 0,
                log.getKind() == ReviewKind.LEARN ? 1 : 0, xp);
            DailyCount reviews = DailyReviews.on(reviewLogRepository, studyDay, deckId, day);
            boolean completedNow = !row.completed() && reviews.reviews() >= goals.reviewGoal()
                && reviews.newWords() >= goals.newWordGoal();
            if (completedNow) {
                goalRepository.markCompleted(deckId, day);
                row = goalRepository.find(deckId, day).orElse(row);
            }
            return new GoalUpdate(progress(deckId, day, row, reviews), xp, completedNow);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot update review goal progress", e);
        }
    }

    /**
     * Records the practice of a word that was not due (see {@code ReviewKind.PRACTICE}), saved as
     * {@code log}: it earns half the XP of a review and does not count towards the review goal, the
     * accuracy or the streak.
     */
    public GoalUpdate recordPractice(long deckId, ReviewLog log) {
        LocalDate day = studyDay.of(log.getReviewedAt());
        try {
            int xp = practiceXp(log);
            GoalRepository.GoalRow row = addProgress(deckId, day, settings.goalsFor(deckId), 0, 0, 0, xp);
            return new GoalUpdate(progress(deckId, day, row, DailyReviews.on(reviewLogRepository, studyDay, deckId, day)),
                xp, false);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot record practice XP", e);
        }
    }

    /** Adds XP to the deck's day, such as an achievement's reward. */
    public void awardXp(long deckId, int xp) {
        if (xp <= 0) {
            return;
        }
        try {
            addProgress(deckId, today(), settings.goalsFor(deckId), 0, 0, 0, xp);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot award XP", e);
        }
    }

    /** The deck's reviews so far, practice not included, counting at most {@code atMost}. */
    public int reviewCount(long deckId, int atMost) {
        try {
            return reviewLogRepository.countReviews(deckId, atMost);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot count the deck's reviews", e);
        }
    }

    public int totalXp(long deckId) {
        try {
            return goalRepository.totalXp(deckId);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read deck XP", e);
        }
    }

    /** XP for a review: more for a correct one, by the rating it counted as and the answer similarity. */
    private static int reviewXp(ReviewLog log) {
        int base = log.isCorrect() ? 5 : 2;
        double similarity = Math.max(0.0, Math.min(1.0, log.getSimilarity()));
        return base + log.getEffectiveRating().getQuality() + (int) Math.round(similarity * 8.0);
    }

    /** Half the XP the same answer earns in a review. */
    static int practiceXp(ReviewLog log) {
        return reviewXp(log) / 2;
    }

    /** Adds to the deck's row of {@code day}, giving it the current {@code goals} and session goal. */
    private GoalRepository.GoalRow addProgress(long deckId, LocalDate day, GoalTargets goals, int reviews, int correct,
                                               int newWords, int xp) throws SQLException {
        return goalRepository.recordProgress(deckId, day, goals.reviewGoal(), goals.newWordGoal(),
            settings.sessionGoal(), reviews, correct, newWords, xp);
    }

    /** {@code row} is the day's stored row, or null if it has none. */
    private DailyGoalProgress progress(long deckId, LocalDate day, GoalRepository.GoalRow row, DailyCount reviews)
        throws SQLException {
        boolean current = row == null || day.equals(today());
        GoalTargets goals = current ? settings.goalsFor(deckId) : new GoalTargets(row.reviewGoal(), row.newWordGoal());
        return new DailyGoalProgress(
            day,
            goals.reviewGoal(),
            goals.newWordGoal(),
            current ? settings.sessionGoal() : row.sessionGoal(),
            reviews.reviews(),
            reviews.correct(),
            reviews.newWords(),
            row == null ? 0 : row.xpEarned(),
            row != null && row.completed(),
            streak(day),
            goalRepository.totalXp(deckId)
        );
    }

    /**
     * Study days in a row, up to {@code day}, with a review in any deck; practice does not count.
     * A day still in progress does not break the streak: until its first review, the run that ended
     * the day before counts. Steps back with one indexed lookup per day of the streak.
     */
    private int streak(LocalDate day) throws SQLException {
        int days = 0;
        LocalDate expected = day;
        LocalDateTime before = studyDay.start(day.plusDays(1));
        while (true) {
            Optional<LocalDateTime> latest = reviewLogRepository.latestReviewBefore(before);
            if (latest.isEmpty()) {
                return days;
            }
            LocalDate reviewed = studyDay.of(latest.get());
            if (days == 0 && reviewed.equals(day.minusDays(1))) {
                expected = reviewed;
            }
            if (!reviewed.equals(expected)) {
                return days;
            }
            days++;
            expected = reviewed.minusDays(1);
            before = studyDay.start(reviewed);
        }
    }
}
