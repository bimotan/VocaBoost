package com.vocabtrainer.service;

import com.vocabtrainer.domain.Achievement;
import com.vocabtrainer.domain.DailyGoalProgress;
import com.vocabtrainer.domain.GoalUpdate;
import com.vocabtrainer.domain.ReviewLog;
import com.vocabtrainer.domain.ReviewMode;
import com.vocabtrainer.domain.ReviewOutcome;
import com.vocabtrainer.domain.ReviewRating;
import com.vocabtrainer.domain.ReviewSessionSummary;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.ReviewLogRepository;
import com.vocabtrainer.repository.TransactionRunner;
import com.vocabtrainer.repository.WordRepository;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

public class ReviewService {
    private final WordRepository wordRepository;
    private final ReviewLogRepository reviewLogRepository;
    private final SimilarityService similarityService;
    private final ReviewScheduler scheduler;
    private final GoalService goalService;
    private final AchievementService achievementService;
    private final TransactionRunner transactions;
    private final Clock clock;
    private final Random random = new Random();
    private final Map<Long, ReviewAnswer> pendingAnswers = new HashMap<>();
    private final List<Achievement> sessionAchievements = new ArrayList<>();
    private long activeSessionDeckId;
    private ReviewMode activeSessionMode = ReviewMode.EN_TO_ZH;
    private ReviewMode currentQuestionMode = ReviewMode.EN_TO_ZH;
    private int sessionTarget = -1;
    private int sessionReviewed;
    private int sessionCorrect;
    private int sessionXp;

    public ReviewService(WordRepository wordRepository, ReviewLogRepository reviewLogRepository,
                         SimilarityService similarityService, ReviewScheduler scheduler) {
        this(wordRepository, reviewLogRepository, similarityService, scheduler, null, null, Clock.systemDefaultZone());
    }

    public ReviewService(WordRepository wordRepository, ReviewLogRepository reviewLogRepository,
                         SimilarityService similarityService, ReviewScheduler scheduler, Clock clock) {
        this(wordRepository, reviewLogRepository, similarityService, scheduler, null, null, clock);
    }

    public ReviewService(WordRepository wordRepository, ReviewLogRepository reviewLogRepository,
                         SimilarityService similarityService, ReviewScheduler scheduler,
                         GoalService goalService, AchievementService achievementService) {
        this(wordRepository, reviewLogRepository, similarityService, scheduler, goalService,
            achievementService, Clock.systemDefaultZone());
    }

    public ReviewService(WordRepository wordRepository, ReviewLogRepository reviewLogRepository,
                         SimilarityService similarityService, ReviewScheduler scheduler,
                         GoalService goalService, AchievementService achievementService, Clock clock) {
        this.wordRepository = wordRepository;
        this.reviewLogRepository = reviewLogRepository;
        this.similarityService = similarityService;
        this.scheduler = scheduler;
        this.goalService = goalService;
        this.achievementService = achievementService;
        this.transactions = wordRepository.transactions();
        this.clock = clock;
    }

    public Optional<WordCard> nextDueWord(long deckId) {
        return nextWord(deckId, ReviewMode.EN_TO_ZH);
    }

    public Optional<WordCard> nextWord(long deckId, ReviewMode mode) {
        try {
            ensureSession(deckId, mode);
            if (isSessionTargetReached()) {
                return Optional.empty();
            }
            LocalDateTime now = LocalDateTime.now(clock);
            List<WordCard> candidates = mode == ReviewMode.WEAK_WORDS
                ? wordRepository.findWeak(deckId, 250)
                : wordRepository.findDue(deckId, now, 250);
            Optional<WordCard> selected = scheduler.selectNext(candidates, now);
            selected.ifPresent(word -> currentQuestionMode = questionModeFor(mode));
            return selected;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read review words", e);
        }
    }

    public void startSession(long deckId, ReviewMode mode, int target) {
        activeSessionDeckId = deckId;
        activeSessionMode = mode == null ? ReviewMode.EN_TO_ZH : mode;
        sessionTarget = Math.max(0, target);
        sessionReviewed = 0;
        sessionCorrect = 0;
        sessionXp = 0;
        sessionAchievements.clear();
        pendingAnswers.clear();
        currentQuestionMode = questionModeFor(activeSessionMode);
    }

    public void resetSession(long deckId) {
        ReviewMode mode = activeSessionMode == null ? ReviewMode.EN_TO_ZH : activeSessionMode;
        startSession(deckId, mode, defaultSessionTarget(deckId));
    }

    public ReviewMode currentQuestionMode() {
        return currentQuestionMode;
    }

    public boolean isSessionTargetReached() {
        return sessionTarget > 0 && sessionReviewed >= sessionTarget;
    }

    public ReviewAnswer submitAnswer(long wordId, String userAnswer) {
        return submitAnswer(wordId, userAnswer, ReviewMode.EN_TO_ZH);
    }

    public ReviewAnswer submitAnswer(long wordId, String userAnswer, ReviewMode mode) {
        try {
            WordCard word = wordRepository.findById(wordId)
                .orElseThrow(() -> new IllegalArgumentException("Word does not exist: " + wordId));
            ReviewMode answerMode = mode == ReviewMode.MIXED ? currentQuestionMode : questionModeFor(mode);
            String correctAnswer = answerMode == ReviewMode.ZH_TO_EN ? word.getEnglish() : word.getChinese();
            double similarity = similarityService.calculate(userAnswer, correctAnswer);
            ReviewAnswer answer = new ReviewAnswer(
                wordId,
                word.getEnglish(),
                userAnswer == null ? "" : userAnswer.trim(),
                correctAnswer,
                similarity,
                LocalDateTime.now(clock)
            );
            pendingAnswers.put(wordId, answer);
            return answer;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot submit answer", e);
        }
    }

    /** Whether the word still exists and is not archived; false once it was deleted from the Word List. */
    public boolean isReviewable(long wordId) {
        try {
            return wordRepository.findById(wordId).filter(word -> !word.isArchived()).isPresent();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read word " + wordId, e);
        }
    }

    public boolean hasPendingAnswer(long wordId) {
        return pendingAnswers.containsKey(wordId);
    }

    /**
     * Saves the rating for an answer submitted with {@link #submitAnswer}. The schedule, review log,
     * goal progress and achievements are written in one transaction. If saving fails nothing is
     * written and the submitted answer is kept, so calling this again retries with the same answer.
     */
    public ReviewOutcome rateCurrent(long wordId, ReviewRating rating) {
        ReviewAnswer answer = pendingAnswers.get(wordId);
        if (answer == null) {
            throw new IllegalStateException("No answer was submitted for word " + wordId + "; submit an answer before rating it");
        }
        SavedReview saved;
        try {
            saved = transactions.inTransaction(() -> saveRating(wordId, rating, answer));
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot save review result", e);
        }

        // Committed: only in-memory session state is updated from here on, so nothing below can fail.
        pendingAnswers.remove(wordId);
        activeSessionDeckId = saved.word().getDeckId();
        sessionReviewed++;
        if (rating != ReviewRating.AGAIN && answer.similarity() >= 0.5) {
            sessionCorrect++;
        }
        sessionXp += saved.earnedXp();
        sessionAchievements.addAll(saved.unlocked());
        return new ReviewOutcome(saved.word(), saved.progress(), saved.earnedXp(), saved.unlocked(),
            sessionSummary(saved.sessionGoal()));
    }

    private SavedReview saveRating(long wordId, ReviewRating rating, ReviewAnswer answer) throws SQLException {
        // Read inside the transaction so a retry starts from the stored card, not a half-updated copy.
        WordCard word = wordRepository.findById(wordId)
            .orElseThrow(() -> new IllegalArgumentException("Word does not exist: " + wordId));
        LocalDateTime now = LocalDateTime.now(clock);
        long elapsed = Math.max(0L, Duration.between(answer.submittedAt(), now).toMillis());
        boolean overdueRescued = word.getNextReviewAt() != null
            && word.getNextReviewAt().toLocalDate().isBefore(now.toLocalDate());

        scheduler.applyRating(word, rating, answer.similarity(), now);
        WordCard updated = wordRepository.save(word);
        reviewLogRepository.insert(new ReviewLog(
            0,
            wordId,
            now,
            answer.userAnswer(),
            answer.correctAnswer(),
            answer.similarity(),
            rating,
            elapsed
        ));

        long deckId = word.getDeckId();
        GoalUpdate goalUpdate = goalService == null
            ? null
            : goalService.recordReview(deckId, rating, answer.similarity());
        DailyGoalProgress progress = goalUpdate == null ? null : goalUpdate.progress();
        List<Achievement> unlocked = achievementService == null || progress == null
            ? List.of()
            : achievementService.evaluate(deckId, progress, overdueRescued, goalUpdate.dailyGoalCompleted());
        int achievementXp = unlocked.stream().mapToInt(Achievement::xpReward).sum();
        int earnedXp = (goalUpdate == null ? 0 : goalUpdate.xpEarned()) + achievementXp;
        if (goalService != null) {
            progress = goalService.getTodayProgress(deckId);
        }
        int sessionGoal = sessionTarget < 0 ? defaultSessionTarget(deckId) : sessionTarget;
        return new SavedReview(updated, progress, earnedXp, unlocked, sessionGoal);
    }

    private record SavedReview(
        WordCard word,
        DailyGoalProgress progress,
        int earnedXp,
        List<Achievement> unlocked,
        int sessionGoal
    ) {
    }

    public ReviewSessionSummary sessionSummary() {
        return sessionSummary(sessionTarget < 0 ? defaultSessionTarget(activeSessionDeckId) : sessionTarget);
    }

    private ReviewSessionSummary sessionSummary(int sessionGoal) {
        return new ReviewSessionSummary(
            sessionReviewed,
            sessionCorrect,
            sessionXp,
            sessionGoal,
            List.copyOf(sessionAchievements)
        );
    }

    private void ensureSession(long deckId, ReviewMode mode) {
        ReviewMode requested = mode == null ? ReviewMode.EN_TO_ZH : mode;
        if (activeSessionDeckId != deckId || activeSessionMode != requested || sessionTarget < 0) {
            startSession(deckId, requested, defaultSessionTarget(deckId));
        }
        activeSessionDeckId = deckId;
        activeSessionMode = requested;
    }

    private int defaultSessionTarget(long deckId) {
        if (goalService == null || deckId <= 0) {
            return GoalService.DEFAULT_SESSION_GOAL;
        }
        return goalService.getTodayProgress(deckId).sessionGoal();
    }

    private ReviewMode questionModeFor(ReviewMode mode) {
        if (mode == ReviewMode.ZH_TO_EN) {
            return ReviewMode.ZH_TO_EN;
        }
        if (mode == ReviewMode.MIXED) {
            return random.nextBoolean() ? ReviewMode.EN_TO_ZH : ReviewMode.ZH_TO_EN;
        }
        return ReviewMode.EN_TO_ZH;
    }
}
