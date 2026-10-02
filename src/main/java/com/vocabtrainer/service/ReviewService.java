package com.vocabtrainer.service;

import com.vocabtrainer.domain.Achievement;
import com.vocabtrainer.domain.CardState;
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
import com.vocabtrainer.service.scheduling.IntervalPreview;
import com.vocabtrainer.service.scheduling.StudyDay;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/**
 * Runs review sessions on the scheduler: which card comes next, checking a typed answer and saving
 * its rating.
 *
 * <p>A session shows the deck's due cards: learning and relearning cards whose step time has come
 * first, then the others weighted by {@link WordSelector}. When nothing is due, a learning card due
 * within the learn-ahead window (20 minutes) is shown early, so a failed or new card is tested again
 * in the same session. The session target counts different cards; once it is reached, only the
 * session's own cards that are still being learned come back, until none is due within the window.
 */
public class ReviewService {
    /** How many due cards the next card is chosen from. */
    private static final int CANDIDATES = 250;

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
    /** The different cards rated in this session; the session target counts them. */
    private final Set<Long> sessionWords = new HashSet<>();
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

    /** The next card of the session, or empty when the session is complete; see the class comment. */
    public Optional<WordCard> nextWord(long deckId, ReviewMode mode) {
        try {
            ensureSession(deckId, mode);
            LocalDateTime now = LocalDateTime.now(clock);
            Optional<WordCard> selected = isSessionTargetReached()
                ? sessionCardStillLearning(deckId, now)
                : nextCandidate(deckId, mode, now);
            selected.ifPresent(word -> currentQuestionMode = questionModeFor(mode));
            return selected;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read review words", e);
        }
    }

    private Optional<WordCard> nextCandidate(long deckId, ReviewMode mode, LocalDateTime now) throws SQLException {
        if (mode == ReviewMode.WEAK_WORDS) {
            return scheduler.selectNext(wordRepository.findWeak(deckId, CANDIDATES), now);
        }
        List<WordCard> due = wordRepository.findDue(deckId, now, scheduler.studyDay().end(now), CANDIDATES);
        // Learning cards come first and soonest first, so their steps keep their length.
        Optional<WordCard> learning = due.stream().filter(word -> word.getState().isLearning()).findFirst();
        if (learning.isPresent()) {
            return learning;
        }
        Optional<WordCard> selected = scheduler.selectNext(due, now);
        if (selected.isPresent()) {
            return selected;
        }
        return wordRepository.findLearningDueBy(deckId, learnAheadLimit(now), 1).stream().findFirst();
    }

    /** After the target: a card this session rated that is still being learned and due within the learn-ahead window. */
    private Optional<WordCard> sessionCardStillLearning(long deckId, LocalDateTime now) throws SQLException {
        return wordRepository.findLearningDueBy(deckId, learnAheadLimit(now), CANDIDATES).stream()
            .filter(word -> sessionWords.contains(word.getId()))
            .findFirst();
    }

    private LocalDateTime learnAheadLimit(LocalDateTime now) {
        return now.plus(scheduler.options().learnAhead());
    }

    public void startSession(long deckId, ReviewMode mode, int target) {
        activeSessionDeckId = deckId;
        activeSessionMode = mode == null ? ReviewMode.EN_TO_ZH : mode;
        sessionTarget = Math.max(0, target);
        sessionReviewed = 0;
        sessionCorrect = 0;
        sessionXp = 0;
        sessionWords.clear();
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

    /** Whether the session rated as many different cards as its target; never for All Due. */
    public boolean isSessionTargetReached() {
        return sessionTarget > 0 && sessionWords.size() >= sessionTarget;
    }

    public ReviewAnswer submitAnswer(long wordId, String userAnswer) {
        return submitAnswer(wordId, userAnswer, ReviewMode.EN_TO_ZH);
    }

    /** Checks an answer whose response time is unknown; it is logged as 0. */
    public ReviewAnswer submitAnswer(long wordId, String userAnswer, ReviewMode mode) {
        return submitAnswer(wordId, userAnswer, mode, null);
    }

    /**
     * Checks the typed answer and keeps it until it is rated.
     *
     * @param shownAt when the card was shown; the time from then to now is logged as the response
     *                time. Null if unknown, which logs 0.
     */
    public ReviewAnswer submitAnswer(long wordId, String userAnswer, ReviewMode mode, LocalDateTime shownAt) {
        try {
            WordCard word = wordRepository.findById(wordId)
                .orElseThrow(() -> new IllegalArgumentException("Word does not exist: " + wordId));
            ReviewMode answerMode = mode == ReviewMode.MIXED ? currentQuestionMode : questionModeFor(mode);
            String correctAnswer = answerMode == ReviewMode.ZH_TO_EN ? word.getEnglish() : word.getChinese();
            double similarity = similarityService.calculate(userAnswer, correctAnswer);
            LocalDateTime submittedAt = LocalDateTime.now(clock);
            long responseMillis = shownAt == null ? 0L : Math.max(0L, Duration.between(shownAt, submittedAt).toMillis());
            ReviewAnswer answer = new ReviewAnswer(
                wordId,
                word.getEnglish(),
                userAnswer == null ? "" : userAnswer.trim(),
                correctAnswer,
                similarity,
                submittedAt,
                responseMillis
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
     * The interval each rating would give the word for the answer submitted with
     * {@link #submitAnswer}, as the rating buttons show it; nothing is saved.
     */
    public Map<ReviewRating, IntervalPreview> previewRatings(long wordId) {
        ReviewAnswer answer = pendingAnswers.get(wordId);
        if (answer == null) {
            throw new IllegalStateException("No answer was submitted for word " + wordId);
        }
        try {
            WordCard word = wordRepository.findById(wordId)
                .orElseThrow(() -> new IllegalArgumentException("Word does not exist: " + wordId));
            return scheduler.preview(word, answer.similarity(), LocalDateTime.now(clock));
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read word " + wordId, e);
        }
    }

    /** The study day rules the scheduler counts days and due words with. */
    public StudyDay studyDay() {
        return scheduler.studyDay();
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
        sessionWords.add(wordId);
        if (rating != ReviewRating.AGAIN && answer.similarity() >= 0.5) {
            sessionCorrect++;
        }
        sessionXp += saved.earnedXp();
        sessionAchievements.addAll(saved.unlocked());
        return new ReviewOutcome(saved.word(), saved.progress(), saved.earnedXp(), saved.unlocked(),
            sessionSummary(saved.sessionGoal()), saved.becameLeech());
    }

    private SavedReview saveRating(long wordId, ReviewRating rating, ReviewAnswer answer) throws SQLException {
        // Read inside the transaction so a retry starts from the stored card, not a half-updated copy.
        WordCard word = wordRepository.findById(wordId)
            .orElseThrow(() -> new IllegalArgumentException("Word does not exist: " + wordId));
        LocalDateTime now = LocalDateTime.now(clock);
        StudyDay studyDay = scheduler.studyDay();
        boolean overdueRescued = word.getState() != CardState.NEW && word.getNextReviewAt() != null
            && studyDay.of(word.getNextReviewAt()).isBefore(studyDay.of(now));

        boolean becameLeech = scheduler.applyRating(word, rating, answer.similarity(), now);
        WordCard updated = wordRepository.save(word);
        reviewLogRepository.insert(new ReviewLog(
            0,
            wordId,
            now,
            answer.userAnswer(),
            answer.correctAnswer(),
            answer.similarity(),
            rating,
            answer.responseMillis()
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
        return new SavedReview(updated, progress, earnedXp, unlocked, sessionGoal, becameLeech);
    }

    private record SavedReview(
        WordCard word,
        DailyGoalProgress progress,
        int earnedXp,
        List<Achievement> unlocked,
        int sessionGoal,
        boolean becameLeech
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
            List.copyOf(sessionAchievements),
            sessionWords.size()
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
