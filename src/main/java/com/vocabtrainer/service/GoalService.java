package com.vocabtrainer.service;

import com.vocabtrainer.domain.DailyGoalProgress;
import com.vocabtrainer.domain.GoalUpdate;
import com.vocabtrainer.domain.ReviewRating;
import com.vocabtrainer.repository.GoalRepository;

import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;

public class GoalService {
    public static final int DEFAULT_REVIEW_GOAL = 20;
    public static final int DEFAULT_NEW_WORD_GOAL = 5;
    public static final int DEFAULT_SESSION_GOAL = 10;

    private final GoalRepository goalRepository;
    private final Clock clock;

    public GoalService(GoalRepository goalRepository) {
        this(goalRepository, Clock.systemDefaultZone());
    }

    public GoalService(GoalRepository goalRepository, Clock clock) {
        this.goalRepository = goalRepository;
        this.clock = clock;
    }

    public DailyGoalProgress getTodayProgress() {
        return getTodayProgress(0L);
    }

    public DailyGoalProgress getTodayProgress(long deckId) {
        return progressFor(deckId, LocalDate.now(clock));
    }

    public DailyGoalProgress progressFor(LocalDate date) {
        return progressFor(0L, date);
    }

    public DailyGoalProgress progressFor(long deckId, LocalDate date) {
        try {
            GoalRepository.GoalRow row = goalRepository.ensure(
                deckId,
                date,
                DEFAULT_REVIEW_GOAL,
                DEFAULT_NEW_WORD_GOAL,
                DEFAULT_SESSION_GOAL
            );
            return toProgress(row);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read goal progress", e);
        }
    }

    public GoalUpdate recordReview(ReviewRating rating, double similarity) {
        return recordReview(0L, rating, similarity);
    }

    public GoalUpdate recordReview(long deckId, ReviewRating rating, double similarity) {
        LocalDate today = LocalDate.now(clock);
        try {
            GoalRepository.GoalRow before = goalRepository.ensure(
                deckId,
                today,
                DEFAULT_REVIEW_GOAL,
                DEFAULT_NEW_WORD_GOAL,
                DEFAULT_SESSION_GOAL
            );
            boolean correct = rating != ReviewRating.AGAIN && similarity >= 0.5;
            int xp = reviewXp(rating, similarity);
            GoalRepository.GoalRow after = goalRepository.addProgress(deckId, today, 1, correct ? 1 : 0, 0, xp);
            boolean completedNow = !before.completed() && isComplete(after);
            if (completedNow) {
                goalRepository.markCompleted(deckId, today);
                after = goalRepository.find(deckId, today).orElse(after);
            }
            return new GoalUpdate(toProgress(after), xp, completedNow);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot update review goal progress", e);
        }
    }

    public GoalUpdate recordNewWords(int count) {
        return recordNewWords(0L, count);
    }

    public GoalUpdate recordNewWords(long deckId, int count) {
        if (count <= 0) {
            return new GoalUpdate(getTodayProgress(deckId), 0, false);
        }
        LocalDate today = LocalDate.now(clock);
        try {
            GoalRepository.GoalRow before = goalRepository.ensure(
                deckId,
                today,
                DEFAULT_REVIEW_GOAL,
                DEFAULT_NEW_WORD_GOAL,
                DEFAULT_SESSION_GOAL
            );
            int xp = count * 2;
            GoalRepository.GoalRow after = goalRepository.addProgress(deckId, today, 0, 0, count, xp);
            boolean completedNow = !before.completed() && isComplete(after);
            if (completedNow) {
                goalRepository.markCompleted(deckId, today);
                after = goalRepository.find(deckId, today).orElse(after);
            }
            return new GoalUpdate(toProgress(after), xp, completedNow);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot update new-word goal progress", e);
        }
    }

    public void awardXp(int xp) {
        awardXp(0L, xp);
    }

    public void awardXp(long deckId, int xp) {
        if (xp <= 0) {
            return;
        }
        LocalDate today = LocalDate.now(clock);
        try {
            goalRepository.ensure(deckId, today, DEFAULT_REVIEW_GOAL, DEFAULT_NEW_WORD_GOAL, DEFAULT_SESSION_GOAL);
            goalRepository.addProgress(deckId, today, 0, 0, 0, xp);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot award XP", e);
        }
    }

    public int totalReviews() {
        try {
            return goalRepository.totalReviews();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read total reviews", e);
        }
    }

    public int totalReviews(long deckId) {
        try {
            return goalRepository.totalReviews(deckId);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read deck total reviews", e);
        }
    }

    public int totalXp(long deckId) {
        try {
            return goalRepository.totalXp(deckId);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read deck XP", e);
        }
    }

    private int reviewXp(ReviewRating rating, double similarity) {
        int base = rating == ReviewRating.AGAIN ? 2 : 5;
        return base + rating.getQuality() + (int) Math.round(Math.max(0.0, Math.min(1.0, similarity)) * 8.0);
    }

    private boolean isComplete(GoalRepository.GoalRow row) {
        return row.reviewedCount() >= row.reviewGoal() && row.newWordsCount() >= row.newWordGoal();
    }

    private DailyGoalProgress toProgress(GoalRepository.GoalRow row) throws SQLException {
        return new DailyGoalProgress(
            row.date(),
            row.reviewGoal(),
            row.newWordGoal(),
            row.sessionGoal(),
            row.reviewedCount(),
            row.correctCount(),
            row.newWordsCount(),
            row.xpEarned(),
            row.completed(),
            calculateStreak(row.deckId(), row.date()),
            goalRepository.totalXp(row.deckId())
        );
    }

    private int calculateStreak(LocalDate date) throws SQLException {
        return calculateStreak(0L, date);
    }

    private int calculateStreak(long deckId, LocalDate date) throws SQLException {
        LocalDate cursor = goalRepository.hasReviewedOn(deckId, date) ? date : date.minusDays(1);
        int streak = 0;
        while (goalRepository.hasReviewedOn(deckId, cursor)) {
            streak++;
            cursor = cursor.minusDays(1);
        }
        return streak;
    }
}
