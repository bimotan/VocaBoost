package com.vocabtrainer.service;

import com.vocabtrainer.domain.Achievement;
import com.vocabtrainer.domain.DailyGoalProgress;
import com.vocabtrainer.domain.GoalUpdate;
import com.vocabtrainer.domain.ReviewLog;
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

public class ReviewService {
    private final WordRepository wordRepository;
    private final ReviewLogRepository reviewLogRepository;
    private final SimilarityService similarityService;
    private final ReviewScheduler scheduler;
    private final GoalService goalService;
    private final AchievementService achievementService;
    private final Clock clock;
    private final Map<Long, ReviewAnswer> pendingAnswers = new HashMap<>();
    private final List<Achievement> sessionAchievements = new ArrayList<>();
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
        try {
            LocalDateTime now = LocalDateTime.now(clock);
            List<WordCard> dueWords = wordRepository.findDue(deckId, now, 50);
            return scheduler.selectNext(dueWords, now);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read due words", e);
        }
    }

    public ReviewAnswer submitAnswer(long wordId, String userAnswer) {
        try {
            WordCard word = wordRepository.findById(wordId)
                .orElseThrow(() -> new IllegalArgumentException("Word does not exist: " + wordId));
            double similarity = similarityService.calculate(userAnswer, word.getChinese());
            ReviewAnswer answer = new ReviewAnswer(
                wordId,
                word.getEnglish(),
                userAnswer == null ? "" : userAnswer.trim(),
                word.getChinese(),
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

            GoalUpdate goalUpdate = goalService == null
                ? null
                : goalService.recordReview(rating, answer.similarity());
            DailyGoalProgress progress = goalUpdate == null ? null : goalUpdate.progress();
            List<Achievement> unlocked = achievementService == null || progress == null
                ? List.of()
                : achievementService.evaluate(progress, overdueRescued, goalUpdate.dailyGoalCompleted());
            int achievementXp = unlocked.stream().mapToInt(Achievement::xpReward).sum();
            int earnedXp = (goalUpdate == null ? 0 : goalUpdate.xpEarned()) + achievementXp;
            if (goalService != null) {
                progress = goalService.getTodayProgress();
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
        int sessionGoal = goalService == null
            ? GoalService.DEFAULT_SESSION_GOAL
            : goalService.getTodayProgress().sessionGoal();
        return new ReviewSessionSummary(
            sessionReviewed,
            sessionCorrect,
            sessionXp,
            sessionGoal,
            List.copyOf(sessionAchievements)
        );
    }
}
