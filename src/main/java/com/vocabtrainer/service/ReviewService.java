package com.vocabtrainer.service;

import com.vocabtrainer.domain.Achievement;
import com.vocabtrainer.domain.CardState;
import com.vocabtrainer.domain.DailyGoalProgress;
import com.vocabtrainer.domain.GoalUpdate;
import com.vocabtrainer.domain.ReviewKind;
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
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs review sessions on the scheduler: which card comes next, checking a typed answer and saving
 * its rating.
 *
 * <p><b>Queues.</b> A session shows learning and relearning cards whose step time has come first.
 * Then review cards due today, the ones most likely forgotten first (lowest retrievability), and new
 * cards in the order they were added, up to the deck's new-cards-per-day limit (counted from the new
 * cards introduced this study day, see {@link ReviewKind#LEARN}). The two are interleaved: one new
 * card after every {@value #REVIEWS_PER_NEW_CARD} review cards, but new cards wait while the due
 * reviews alone fill the rest of the session (in an All Due session: while more than
 * {@value #LARGE_BACKLOG} are due). When nothing is due, a learning card due within the learn-ahead
 * window (20 minutes) is shown early, so a failed or new card is tested again in the same session.
 *
 * <p><b>Session.</b> A card is shown once per session; only a learning or relearning step brings it
 * back. The session target counts different cards; 0 means All Due: every due review and the new
 * cards still allowed today. Once the target is reached, only the session's own cards that are still
 * being learned come back, until none is due within the window. Changing the mode or the deck, or
 * resetting, starts a new session with the same target; the target and mode are saved in the
 * settings and the next start uses them.
 *
 * <p><b>Weak Words</b> shows the deck's weak words, due ones first. A weak word that is not due yet
 * is <i>practiced</i> ({@link ReviewKind#PRACTICE}): the review is logged, but the card's memory and
 * schedule stay as they are, because reviewing it early would tell FSRS little and inflate its
 * interval. Only a failed practice changes something: the card becomes due at the start of the next
 * study day if it was due later. Practice earns half the XP and does not count towards the review
 * goal, the streak or review achievements. The session ends when every weak word was shown.
 *
 * <p><b>Mixed mode</b> asks each card English to Chinese (recognition) or Chinese to English
 * (production) and logs the direction. Both directions share one schedule. Producing the word is the
 * harder skill, so a failure counts fully in either direction; a recognition success counts at most
 * as Good, so an easy recognition never grows the shared interval more than a normal Good review.
 *
 * <p><b>Answers</b> are graded by {@link AnswerGrader}, which caps the rating an answer can count as.
 * The user may override the cap ("I was right"): the chosen rating then counts as it is. Each log
 * keeps the chosen rating, the rating the schedule used and whether the user overrode the check;
 * an answer is correct when it did not count as Again ({@link ReviewLog#isCorrect()}).
 */
public class ReviewService {
    /** How many weak words the next one is chosen from, besides those the session already showed. */
    private static final int CANDIDATES = 250;
    /** One new card is introduced after this many review cards. */
    static final int REVIEWS_PER_NEW_CARD = 4;
    /** In an All Due session, new cards wait while more review cards than this are due. */
    static final int LARGE_BACKLOG = 100;

    private static final Logger LOGGER = Logger.getLogger(ReviewService.class.getName());

    private final WordRepository wordRepository;
    private final ReviewLogRepository reviewLogRepository;
    private final AnswerGrader grader;
    private final ReviewScheduler scheduler;
    private final GoalService goalService;
    private final AchievementService achievementService;
    private final TransactionRunner transactions;
    private final Clock clock;
    /** Null when the settings are not saved; then {@link #newCardLimits} holds the changed limits. */
    private final ReviewSettings settings;
    private final Map<Long, Integer> newCardLimits = new HashMap<>();
    private final Random random;
    private final Map<Long, ReviewAnswer> pendingAnswers = new HashMap<>();
    /** The different cards rated in this session; the session target counts them and none is shown twice. */
    private final Set<Long> sessionWords = new HashSet<>();
    private final List<Achievement> sessionAchievements = new ArrayList<>();
    private boolean sessionStarted;
    private long activeSessionDeckId;
    private ReviewMode activeSessionMode;
    private ReviewMode currentQuestionMode = ReviewMode.EN_TO_ZH;
    private int sessionTarget;
    private int sessionReviewed;
    private int sessionCorrect;
    private int sessionXp;
    /** Review cards rated since the session last introduced a new card. */
    private int reviewsSinceNewCard;

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
        this(wordRepository, reviewLogRepository, similarityService, scheduler, goalService, achievementService,
            clock, null, new Random());
    }

    /**
     * @param settings the saved new-cards-per-day limits and the session size and mode to start with;
     *                 null keeps them in memory, with the defaults
     * @param random   picks the question direction in Mixed mode
     */
    public ReviewService(WordRepository wordRepository, ReviewLogRepository reviewLogRepository,
                         SimilarityService similarityService, ReviewScheduler scheduler,
                         GoalService goalService, AchievementService achievementService, Clock clock,
                         ReviewSettings settings, Random random) {
        this.wordRepository = wordRepository;
        this.reviewLogRepository = reviewLogRepository;
        this.grader = new AnswerGrader(similarityService);
        this.scheduler = scheduler;
        this.goalService = goalService;
        this.achievementService = achievementService;
        this.transactions = wordRepository.transactions();
        this.clock = clock;
        this.settings = settings;
        this.random = random;
        this.sessionTarget = settings == null ? ReviewSettings.DEFAULT_SESSION_SIZE : settings.sessionSize();
        this.activeSessionMode = settings == null ? ReviewMode.EN_TO_ZH : settings.mode();
    }

    public Optional<WordCard> nextDueWord(long deckId) {
        return nextWord(deckId, ReviewMode.EN_TO_ZH);
    }

    /**
     * The next card of the session, or empty when the session is complete; see the class comment.
     * A different deck or mode than the session's starts a new session with the same target.
     */
    public Optional<WordCard> nextWord(long deckId, ReviewMode mode) {
        try {
            ensureSession(deckId, mode);
            LocalDateTime now = LocalDateTime.now(clock);
            Optional<WordCard> selected = isSessionTargetReached()
                ? sessionCardStillLearning(deckId, now)
                : nextCandidate(deckId, now);
            selected.ifPresent(word -> currentQuestionMode = questionModeFor(activeSessionMode));
            return selected;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read review words", e);
        }
    }

    private Optional<WordCard> nextCandidate(long deckId, LocalDateTime now) throws SQLException {
        LocalDateTime dayEnd = scheduler.studyDay().end(now);
        if (activeSessionMode == ReviewMode.WEAK_WORDS) {
            Optional<WordCard> weak = nextWeakWord(deckId, now, dayEnd);
            return weak.isPresent() ? weak : sessionCardStillLearning(deckId, now);
        }
        // Learning cards come first and soonest first, so their steps keep their length.
        Optional<WordCard> learning = wordRepository.findLearningDueBy(deckId, now, 1).stream().findFirst();
        if (learning.isPresent()) {
            return learning;
        }
        Optional<WordCard> review =
            notShownYet(wordRepository.findDueReviews(deckId, now, dayEnd, sessionWords.size() + 1));
        Optional<WordCard> newCard = newCardsLeftToday(deckId, now) > 0
            ? notShownYet(wordRepository.findNewCards(deckId, dayEnd, sessionWords.size() + 1))
            : Optional.empty();
        if (review.isPresent() && newCard.isPresent()) {
            return introducesNewCard(deckId, now, dayEnd) ? newCard : review;
        }
        Optional<WordCard> either = review.or(() -> newCard);
        if (either.isPresent()) {
            return either;
        }
        return wordRepository.findLearningDueBy(deckId, learnAheadLimit(now), 1).stream().findFirst();
    }

    /**
     * Whether the next card is a new one rather than a review: after {@value #REVIEWS_PER_NEW_CARD}
     * reviews, unless the due reviews fill the rest of the session (more than {@value #LARGE_BACKLOG}
     * of them in an All Due session).
     */
    private boolean introducesNewCard(long deckId, LocalDateTime now, LocalDateTime dayEnd) throws SQLException {
        if (reviewsSinceNewCard < REVIEWS_PER_NEW_CARD) {
            return false;
        }
        int reviewsDue = wordRepository.countDueByState(deckId, now, dayEnd).review();
        return sessionTarget > 0 ? reviewsDue < sessionTarget - sessionWords.size() : reviewsDue <= LARGE_BACKLOG;
    }

    /**
     * Weak Words: a learning step whose time has come, then a due weak word, then a weak word to
     * practice that is not due yet; each shown once. Learning cards that are not due wait for their
     * step instead of being practiced.
     */
    private Optional<WordCard> nextWeakWord(long deckId, LocalDateTime now, LocalDateTime dayEnd) throws SQLException {
        List<WordCard> weak = wordRepository.findWeak(deckId, CANDIDATES + sessionWords.size());
        Optional<WordCard> step = weak.stream()
            .filter(word -> word.getState().isLearning() && word.isDue(now, dayEnd))
            .min(Comparator.comparing(WordCard::getNextReviewAt).thenComparingLong(WordCard::getId));
        if (step.isPresent()) {
            return step;
        }
        List<WordCard> fresh = weak.stream().filter(word -> !sessionWords.contains(word.getId())).toList();
        List<WordCard> due = fresh.stream().filter(word -> word.isDue(now, dayEnd)).toList();
        if (!due.isEmpty()) {
            return scheduler.selectNext(due, now);
        }
        return scheduler.selectNext(fresh.stream().filter(word -> word.getState() == CardState.REVIEW).toList(), now);
    }

    private Optional<WordCard> notShownYet(List<WordCard> words) {
        return words.stream().filter(word -> !sessionWords.contains(word.getId())).findFirst();
    }

    /**
     * After the target, or when nothing else is left: a card this session rated that is still being
     * learned and due within the learn-ahead window.
     */
    private Optional<WordCard> sessionCardStillLearning(long deckId, LocalDateTime now) throws SQLException {
        return wordRepository.findLearningDueBy(deckId, learnAheadLimit(now), CANDIDATES + sessionWords.size()).stream()
            .filter(word -> sessionWords.contains(word.getId()))
            .findFirst();
    }

    private LocalDateTime learnAheadLimit(LocalDateTime now) {
        return now.plus(scheduler.options().learnAhead());
    }

    /**
     * Starts a new session and saves {@code mode} and {@code target} as the ones the next session
     * starts with.
     *
     * @param target the number of different cards, from 1 to {@value ReviewSettings#MAX_SESSION_SIZE};
     *               0 for All Due
     */
    public void startSession(long deckId, ReviewMode mode, int target) {
        sessionStarted = true;
        activeSessionDeckId = deckId;
        activeSessionMode = mode == null ? ReviewMode.EN_TO_ZH : mode;
        sessionTarget = boundedTarget(target);
        sessionReviewed = 0;
        sessionCorrect = 0;
        sessionXp = 0;
        reviewsSinceNewCard = 0;
        sessionWords.clear();
        sessionAchievements.clear();
        pendingAnswers.clear();
        currentQuestionMode = questionModeFor(activeSessionMode);
        remember(() -> {
            settings.saveMode(activeSessionMode);
            settings.saveSessionSize(sessionTarget);
        });
    }

    /** Starts a new session on {@code deckId} with the current mode and target. */
    public void resetSession(long deckId) {
        startSession(deckId, activeSessionMode, sessionTarget);
    }

    /**
     * Changes the target of the running session, keeping what it has done so far, and saves it for
     * the next session; see {@link #startSession}.
     */
    public void setSessionTarget(int target) {
        sessionTarget = boundedTarget(target);
        remember(() -> settings.saveSessionSize(sessionTarget));
    }

    /** The session's target: a number of different cards, or 0 for All Due. */
    public int sessionTarget() {
        return sessionTarget;
    }

    /** The session's mode, or before the first session the mode it will start with. */
    public ReviewMode sessionMode() {
        return activeSessionMode;
    }

    public ReviewMode currentQuestionMode() {
        return currentQuestionMode;
    }

    /** Whether the session rated as many different cards as its target; never for All Due. */
    public boolean isSessionTargetReached() {
        return sessionTarget > 0 && sessionWords.size() >= sessionTarget;
    }

    /** The most new cards {@code deckId} introduces per study day. */
    public int newCardsPerDay(long deckId) {
        if (settings == null) {
            return newCardLimits.getOrDefault(deckId, ReviewSettings.DEFAULT_NEW_CARDS_PER_DAY);
        }
        return settings.newCardsPerDay(deckId);
    }

    /**
     * Sets and saves the deck's new-cards-per-day limit; the running session uses it for its next card.
     *
     * @throws IllegalArgumentException if {@code limit} is not from 0 to {@value ReviewSettings#MAX_NEW_CARDS_PER_DAY}
     */
    public void setNewCardsPerDay(long deckId, int limit) {
        if (settings == null) {
            if (limit < 0 || limit > ReviewSettings.MAX_NEW_CARDS_PER_DAY) {
                throw new IllegalArgumentException("New cards per day must be between 0 and "
                    + ReviewSettings.MAX_NEW_CARDS_PER_DAY + ".");
            }
            newCardLimits.put(deckId, limit);
        } else {
            settings.saveNewCardsPerDay(deckId, limit);
        }
    }

    /** What the deck has to study today: due reviews and the new cards the daily limit still allows. */
    public ReviewQueueCounts queueCounts(long deckId) {
        try {
            return ReviewQueueCounts.read(wordRepository, reviewLogRepository, scheduler.studyDay(), deckId,
                LocalDateTime.now(clock), newCardsPerDay(deckId));
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot count the due words", e);
        }
    }

    private int newCardsLeftToday(long deckId, LocalDateTime now) throws SQLException {
        StudyDay studyDay = scheduler.studyDay();
        int introduced = reviewLogRepository.countNewCardsIntroducedSince(deckId, studyDay.start(studyDay.of(now)));
        return Math.max(0, newCardsPerDay(deckId) - introduced);
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
     * @param mode    the mode the card was asked in; for Mixed mode, the direction of the current card
     *                ({@link #currentQuestionMode()}) is used
     * @param shownAt when the card was shown; the time from then to now is logged as the response
     *                time. Null if unknown, which logs 0.
     */
    public ReviewAnswer submitAnswer(long wordId, String userAnswer, ReviewMode mode, LocalDateTime shownAt) {
        try {
            WordCard word = wordRepository.findById(wordId)
                .orElseThrow(() -> new IllegalArgumentException("Word does not exist: " + wordId));
            ReviewMode askedIn = mode == null ? ReviewMode.EN_TO_ZH : mode;
            ReviewMode direction = askedIn == ReviewMode.MIXED ? currentQuestionMode : questionModeFor(askedIn);
            String correctAnswer = direction == ReviewMode.ZH_TO_EN ? word.getEnglish() : word.getChinese();
            AnswerGrade grade = grader.grade(word, direction, userAnswer,
                english -> wordRepository.findByEnglish(word.getDeckId(), english));
            LocalDateTime submittedAt = LocalDateTime.now(clock);
            long responseMillis = shownAt == null ? 0L : Math.max(0L, Duration.between(shownAt, submittedAt).toMillis());
            ReviewAnswer answer = new ReviewAnswer(
                wordId,
                word.getEnglish(),
                userAnswer == null ? "" : userAnswer.trim(),
                correctAnswer,
                grade,
                submittedAt,
                responseMillis,
                direction,
                askedIn
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
     * {@link #submitAnswer}, as the rating buttons show it; nothing is saved. See
     * {@link #previewRatings(long, boolean)}.
     */
    public Map<ReviewRating, IntervalPreview> previewRatings(long wordId) {
        return previewRatings(wordId, false);
    }

    /**
     * The interval each rating would give the word for the answer submitted with
     * {@link #submitAnswer}, as the rating buttons show it; nothing is saved. Each rating gives the
     * interval of the rating it counts as ({@link ReviewAnswer#countsAs}), so a capped rating shows
     * the interval of the cap unless {@code overridden}. A practice keeps the due date: every rating
     * shows it, except that a failed answer shows the next day when the card was due later.
     *
     * @param overridden whether the user overrides the answer check, see {@link #rateCurrent(long, ReviewRating, boolean)}
     */
    public Map<ReviewRating, IntervalPreview> previewRatings(long wordId, boolean overridden) {
        ReviewAnswer answer = pendingAnswers.get(wordId);
        if (answer == null) {
            throw new IllegalStateException("No answer was submitted for word " + wordId);
        }
        try {
            WordCard word = wordRepository.findById(wordId)
                .orElseThrow(() -> new IllegalArgumentException("Word does not exist: " + wordId));
            LocalDateTime now = LocalDateTime.now(clock);
            if (kindOf(word, now) == ReviewKind.PRACTICE) {
                return practicePreviews(word, answer, overridden, now);
            }
            Map<ReviewRating, IntervalPreview> intervals = scheduler.intervals(word, now);
            Map<ReviewRating, IntervalPreview> previews = new EnumMap<>(ReviewRating.class);
            for (ReviewRating rating : ReviewRating.values()) {
                previews.put(rating, intervals.get(answer.countsAs(rating, overridden)));
            }
            return previews;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read word " + wordId, e);
        }
    }

    private Map<ReviewRating, IntervalPreview> practicePreviews(WordCard word, ReviewAnswer answer, boolean overridden,
                                                                LocalDateTime now) {
        Map<ReviewRating, IntervalPreview> previews = new EnumMap<>(ReviewRating.class);
        for (ReviewRating rating : ReviewRating.values()) {
            LocalDateTime due = practiceDue(word, answer.countsAs(rating, overridden), now);
            previews.put(rating, IntervalPreview.days((int) Math.max(1L, scheduler.studyDay().daysBetween(now, due))));
        }
        return previews;
    }

    /** The study day rules the scheduler counts days and due words with. */
    public StudyDay studyDay() {
        return scheduler.studyDay();
    }

    /**
     * Saves the rating for an answer submitted with {@link #submitAnswer}; see
     * {@link #rateCurrent(long, ReviewRating, boolean)}.
     */
    public ReviewOutcome rateCurrent(long wordId, ReviewRating rating) {
        return rateCurrent(wordId, rating, false);
    }

    /**
     * Saves the rating for an answer submitted with {@link #submitAnswer}. The rating counts as
     * {@link ReviewAnswer#countsAs}: capped by the answer check unless {@code overridden}, which the
     * log records. The schedule, review log, goal progress and achievements are written in one
     * transaction. If saving fails nothing is written and the submitted answer is kept, so calling
     * this again retries with the same answer.
     *
     * @param overridden the user says the answer was right although the check capped it ("I was
     *                   right"); ignored for an answer the check did not cap
     */
    public ReviewOutcome rateCurrent(long wordId, ReviewRating rating, boolean overridden) {
        ReviewAnswer answer = pendingAnswers.get(wordId);
        if (answer == null) {
            throw new IllegalStateException("No answer was submitted for word " + wordId + "; submit an answer before rating it");
        }
        boolean override = overridden && answer.canOverride();
        SavedReview saved;
        try {
            saved = transactions.inTransaction(() -> saveRating(wordId, rating, override, answer));
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot save review result", e);
        }

        // Committed: only in-memory session state is updated from here on, so nothing below can fail.
        pendingAnswers.remove(wordId);
        activeSessionDeckId = saved.word().getDeckId();
        sessionReviewed++;
        sessionWords.add(wordId);
        if (saved.kind() == ReviewKind.LEARN) {
            reviewsSinceNewCard = 0;
        } else if (saved.reviewCard()) {
            reviewsSinceNewCard++;
        }
        if (saved.log().isCorrect()) {
            sessionCorrect++;
        }
        sessionXp += saved.earnedXp();
        sessionAchievements.addAll(saved.unlocked());
        return new ReviewOutcome(saved.word(), saved.progress(), saved.earnedXp(), saved.unlocked(),
            sessionSummary(), saved.becameLeech(), saved.kind());
    }

    private SavedReview saveRating(long wordId, ReviewRating rating, boolean overridden, ReviewAnswer answer)
        throws SQLException {
        // Read inside the transaction so a retry starts from the stored card, not a half-updated copy.
        WordCard word = wordRepository.findById(wordId)
            .orElseThrow(() -> new IllegalArgumentException("Word does not exist: " + wordId));
        LocalDateTime now = LocalDateTime.now(clock);
        ReviewKind kind = kindOf(word, now);
        ReviewLog log = log(wordId, answer, rating, overridden, now, kind);
        if (kind == ReviewKind.PRACTICE) {
            return savePractice(word, log, now);
        }
        StudyDay studyDay = scheduler.studyDay();
        boolean reviewCard = word.getState() == CardState.REVIEW;
        boolean overdueRescued = word.getState() != CardState.NEW && word.getNextReviewAt() != null
            && studyDay.of(word.getNextReviewAt()).isBefore(studyDay.of(now));

        boolean becameLeech = scheduler.applyRating(word, log.getEffectiveRating(), now);
        WordCard updated = wordRepository.save(word);
        reviewLogRepository.insert(log);

        long deckId = word.getDeckId();
        GoalUpdate goalUpdate = goalService == null
            ? null
            : goalService.recordReview(deckId, log);
        DailyGoalProgress progress = goalUpdate == null ? null : goalUpdate.progress();
        List<Achievement> unlocked = achievementService == null || progress == null
            ? List.of()
            : achievementService.evaluate(deckId, progress, overdueRescued, goalUpdate.dailyGoalCompleted());
        int achievementXp = unlocked.stream().mapToInt(Achievement::xpReward).sum();
        int earnedXp = (goalUpdate == null ? 0 : goalUpdate.xpEarned()) + achievementXp;
        if (goalService != null) {
            progress = goalService.getTodayProgress(deckId);
        }
        return new SavedReview(updated, progress, earnedXp, unlocked, becameLeech, kind, reviewCard, log);
    }

    /** Logs the practice of a word that is not due; only a failed answer can bring its due date forward. */
    private SavedReview savePractice(WordCard word, ReviewLog log, LocalDateTime now) throws SQLException {
        LocalDateTime due = practiceDue(word, log.getEffectiveRating(), now);
        if (!Objects.equals(due, word.getNextReviewAt())) {
            word.setNextReviewAt(due);
            wordRepository.save(word);
        }
        reviewLogRepository.insert(log);
        GoalUpdate goalUpdate = goalService == null
            ? null
            : goalService.recordPractice(word.getDeckId(), log);
        return new SavedReview(word, goalUpdate == null ? null : goalUpdate.progress(),
            goalUpdate == null ? 0 : goalUpdate.xpEarned(), List.of(), false, ReviewKind.PRACTICE, false, log);
    }

    /**
     * When a practiced word is due afterwards: as before, or, after a failed answer (one that counts
     * as Again), at the start of the next study day if it was due later.
     */
    private LocalDateTime practiceDue(WordCard word, ReviewRating effectiveRating, LocalDateTime now) {
        LocalDateTime due = word.getNextReviewAt();
        if (effectiveRating != ReviewRating.AGAIN) {
            return due;
        }
        LocalDateTime tomorrow = scheduler.studyDay().end(now);
        return due == null || due.isAfter(tomorrow) ? tomorrow : due;
    }

    /**
     * What rating {@code word} at {@code now} is: the first review of a new card, a practice of a
     * review card that is not due, or a review. Learning cards shown early (learn-ahead) are reviews.
     */
    private ReviewKind kindOf(WordCard word, LocalDateTime now) {
        if (word.getState() == CardState.NEW) {
            return ReviewKind.LEARN;
        }
        boolean notDue = word.getState() == CardState.REVIEW && !word.isDue(now, scheduler.studyDay().end(now));
        return notDue ? ReviewKind.PRACTICE : ReviewKind.REVIEW;
    }

    private static ReviewLog log(long wordId, ReviewAnswer answer, ReviewRating rating, boolean overridden,
                                 LocalDateTime now, ReviewKind kind) {
        return new ReviewLog(
            0,
            wordId,
            now,
            answer.userAnswer(),
            answer.correctAnswer(),
            answer.similarity(),
            rating,
            answer.responseMillis(),
            kind,
            answer.direction(),
            answer.countsAs(rating, overridden),
            overridden
        );
    }

    /**
     * @param reviewCard whether the card was in review before; the interleaving of new cards counts these
     * @param log        the saved review log
     */
    private record SavedReview(
        WordCard word,
        DailyGoalProgress progress,
        int earnedXp,
        List<Achievement> unlocked,
        boolean becameLeech,
        ReviewKind kind,
        boolean reviewCard,
        ReviewLog log
    ) {
    }

    public ReviewSessionSummary sessionSummary() {
        return new ReviewSessionSummary(
            sessionReviewed,
            sessionCorrect,
            sessionXp,
            sessionTarget,
            List.copyOf(sessionAchievements),
            sessionWords.size()
        );
    }

    private void ensureSession(long deckId, ReviewMode mode) {
        ReviewMode requested = mode == null ? ReviewMode.EN_TO_ZH : mode;
        if (!sessionStarted || activeSessionDeckId != deckId || activeSessionMode != requested) {
            startSession(deckId, requested, sessionTarget);
        }
    }

    private static int boundedTarget(int target) {
        return Math.min(ReviewSettings.MAX_SESSION_SIZE, Math.max(0, target));
    }

    /** Saves a session choice; failing to save it must not stop the review. */
    private void remember(Runnable save) {
        if (settings == null) {
            return;
        }
        try {
            save.run();
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Cannot save the review session settings", e);
        }
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
