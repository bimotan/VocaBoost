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

    public ReviewOutcome rateCurrent(long wordId, ReviewRating rating) {
        try {
            WordCard word = wordRepository.findById(wordId)
                .orElseThrow(() -> new IllegalArgumentException("Word does not exist: " + wordId));
            ReviewAnswer answer = pendingAnswers.remove(wordId);
            LocalDateTime now = LocalDateTime.now(clock);
            if (answer == null) {
                answer = new ReviewAnswer(wordId, word.getEnglish(), "", word.getChinese(), 0.0, now);
            }
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
            activeSessionDeckId = deckId;
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

            sessionReviewed++;
            if (rating != ReviewRating.AGAIN && answer.similarity() >= 0.5) {
                sessionCorrect++;
            }
            sessionXp += earnedXp;
            sessionAchievements.addAll(unlocked);
            return new ReviewOutcome(updated, progress, earnedXp, unlocked, sessionSummary());
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot save review result", e);
        }
    }

    public ReviewSessionSummary sessionSummary() {
        int sessionGoal = sessionTarget < 0 ? defaultSessionTarget(activeSessionDeckId) : sessionTarget;
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
