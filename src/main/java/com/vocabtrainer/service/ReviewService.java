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
import com.vocabtrainer.service.cloze.Cloze;
import com.vocabtrainer.service.cloze.ClozeMaker;
import com.vocabtrainer.service.cloze.SentenceSpan;
import com.vocabtrainer.service.cloze.WordForms;
import com.vocabtrainer.service.scheduling.IntervalPreview;
import com.vocabtrainer.service.scheduling.StudyDay;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.vocabtrainer.util.Messages.tr;

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
 * <p><b>Cloze mode</b> asks each card with its example sentence, the word and its inflected forms
 * blanked out ({@link ClozeMaker#make}), and its Chinese meaning as a hint; the learner types the
 * English word, and the word as the sentence has it ("admonished") counts too. A card whose example
 * makes no cloze (there is none, or the word is not in it) is skipped for the rest of the session
 * and counted ({@link #clozeSkippedCount()}); the queues otherwise stay as they are. Like Mixed mode,
 * a cloze shares the card's one schedule; typing the word is production, so its ratings count as
 * they are, as Chinese to English ones do.
 *
 * <p><b>Answers</b> are graded by {@link AnswerGrader}, which caps the rating an answer can count as.
 * The user may override the cap ("I was right"): the chosen rating then counts as it is. Each log
 * keeps the chosen rating, the rating the schedule used and whether the user overrode the check;
 * an answer is correct when it did not count as Again ({@link ReviewLog#isCorrect()}).
 *
 * <p><b>Already known</b> ({@link #markKnown}) puts a new card straight into review with
 * {@value ReviewScheduler#KNOWN_STABILITY_DAYS} days of stability and logs it as
 * {@link ReviewKind#KNOWN}, which is no review: no XP, goal, accuracy, streak or new-card allowance.
 * <b>Suspending</b> a card ({@link #suspendWord}) keeps it and its schedule but takes it out of every
 * queue and count until it is unsuspended.
 *
 * <p><b>Undo.</b> Each rating, Already known and suspension of the session can be taken back, the
 * last one first, up to {@value #UNDO_LIMIT} of them ({@link #undoLast}): in one transaction the card
 * gets back its exact review state (the FSRS and SM-2 fields, unsuspended, and the tags it had, such
 * as before a rating tagged it as a leech), the review log is deleted, and the XP, the daily goal's
 * completion and the badges the rating earned are taken back. The session's counts go back too, and
 * the submitted answer is kept again, so the card can be rated again without typing the answer. A new
 * session (another deck or mode, or a reset) starts without anything to undo.
 */
public class ReviewService {
    /** How many weak words the next one is chosen from, besides those the session already showed. */
    private static final int CANDIDATES = 250;
    /** One new card is introduced after this many review cards. */
    static final int REVIEWS_PER_NEW_CARD = 4;
    /** In an All Due session, new cards wait while more review cards than this are due. */
    static final int LARGE_BACKLOG = 100;
    /** How many of the session's last actions can be undone. */
    public static final int UNDO_LIMIT = 100;

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
    private final ClozeMaker clozeMaker;
    private final Map<Long, ReviewAnswer> pendingAnswers = new HashMap<>();
    /** The different cards rated in this session; the session target counts them and none is shown twice. */
    private final Set<Long> sessionWords = new HashSet<>();
    private final List<Achievement> sessionAchievements = new ArrayList<>();
    private boolean sessionStarted;
    private long activeSessionDeckId;
    private ReviewMode activeSessionMode;
    private ReviewMode currentQuestionMode = ReviewMode.EN_TO_ZH;
    /** The cloze of the card shown last in Cloze mode; null otherwise. */
    private Cloze currentCloze;
    /** In Cloze mode, the cards of the session whose example makes no cloze; they are not shown. */
    private final Set<Long> clozeSkipped = new HashSet<>();
    private int sessionTarget;
    private int sessionReviewed;
    private int sessionCorrect;
    private int sessionXp;
    /** Review cards rated since the session last introduced a new card. */
    private int reviewsSinceNewCard;
    /** What {@link #undoLast} can take back, the latest first. */
    private final Deque<UndoEntry> undoStack = new ArrayDeque<>();

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
        this(wordRepository, reviewLogRepository, similarityService, scheduler, goalService, achievementService,
            clock, settings, random, new ClozeMaker(WordForms.NONE));
    }

    /**
     * @param settings   the saved new-cards-per-day limits and the session size and mode to start with;
     *                   null keeps them in memory, with the defaults
     * @param random     picks the question direction in Mixed mode
     * @param clozeMaker finds the word and its inflected forms in its example sentence
     */
    public ReviewService(WordRepository wordRepository, ReviewLogRepository reviewLogRepository,
                         SimilarityService similarityService, ReviewScheduler scheduler,
                         GoalService goalService, AchievementService achievementService, Clock clock,
                         ReviewSettings settings, Random random, ClozeMaker clozeMaker) {
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
        this.clozeMaker = clozeMaker;
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
            currentCloze = null;
            selected.ifPresent(word -> {
                currentQuestionMode = questionModeFor(activeSessionMode);
                if (currentQuestionMode == ReviewMode.CLOZE) {
                    currentCloze = clozeMaker.make(word).orElse(null);
                }
            });
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
        Optional<WordCard> learning = first(limit -> wordRepository.findLearningDueBy(deckId, now, limit),
            word -> true, 0);
        if (learning.isPresent()) {
            return learning;
        }
        Optional<WordCard> review = first(limit -> wordRepository.findDueReviews(deckId, now, dayEnd, limit),
            this::notShownYet, sessionWords.size());
        Optional<WordCard> newCard = newCardsLeftToday(deckId, now) > 0
            ? first(limit -> wordRepository.findNewCards(deckId, dayEnd, limit), this::notShownYet, sessionWords.size())
            : Optional.empty();
        if (review.isPresent() && newCard.isPresent()) {
            return introducesNewCard(deckId, now, dayEnd) ? newCard : review;
        }
        Optional<WordCard> either = review.or(() -> newCard);
        if (either.isPresent()) {
            return either;
        }
        return first(limit -> wordRepository.findLearningDueBy(deckId, learnAheadLimit(now), limit), word -> true, 0);
    }

    /** Reads the first {@code limit} cards of a queue, in its order. */
    @FunctionalInterface
    private interface Queue {
        List<WordCard> read(int limit) throws SQLException;
    }

    /**
     * The first card of {@code queue} that {@code wanted} accepts and that can be asked in the
     * session's mode ({@link #canAsk}). Outside Cloze mode one read is enough; in Cloze mode the
     * queue is read further, twice as far each time, while its head holds only cards that cannot be
     * asked, so a deck where most cards have no example takes a few reads, not one per card.
     *
     * @param rejected at most how many cards {@code wanted} rejects at the head of the queue
     */
    private Optional<WordCard> first(Queue queue, Predicate<WordCard> wanted, int rejected) throws SQLException {
        int limit = rejected + clozeSkipped.size() + 1;
        while (true) {
            List<WordCard> cards = queue.read(limit);
            for (WordCard card : cards) {
                if (wanted.test(card) && canAsk(card)) {
                    return Optional.of(card);
                }
            }
            if (cards.size() < limit || limit > Integer.MAX_VALUE / 2) {
                return Optional.empty();
            }
            limit *= 2;
        }
    }

    /**
     * Whether the card can be asked in the session's mode: in Cloze mode only when its example makes
     * a cloze. A card that cannot is skipped for the rest of the session and counted.
     */
    private boolean canAsk(WordCard word) {
        if (activeSessionMode != ReviewMode.CLOZE) {
            return true;
        }
        if (clozeSkipped.contains(word.getId())) {
            return false;
        }
        if (clozeMaker.make(word).isPresent()) {
            return true;
        }
        clozeSkipped.add(word.getId());
        return false;
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

    private boolean notShownYet(WordCard word) {
        return !sessionWords.contains(word.getId());
    }

    /**
     * After the target, or when nothing else is left: a card this session rated that is still being
     * learned and due within the learn-ahead window.
     */
    private Optional<WordCard> sessionCardStillLearning(long deckId, LocalDateTime now) throws SQLException {
        return wordRepository.findLearningDueBy(deckId, learnAheadLimit(now), CANDIDATES + sessionWords.size()).stream()
            .filter(word -> sessionWords.contains(word.getId()))
            .filter(this::canAsk)
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
        clozeSkipped.clear();
        undoStack.clear();
        currentCloze = null;
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

    /** The cloze of the card {@link #nextWord} returned last, in Cloze mode; empty otherwise. */
    public Optional<Cloze> currentCloze() {
        return Optional.ofNullable(currentCloze);
    }

    /**
     * How many cards this Cloze session skipped so far because their example makes no cloze: there
     * is none, or the word is not in it. 0 in other modes.
     */
    public int clozeSkippedCount() {
        return clozeSkipped.size();
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
                throw new IllegalArgumentException(tr("validation.newCardsPerDay", ReviewSettings.MAX_NEW_CARDS_PER_DAY));
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
                .orElseThrow(() -> new IllegalArgumentException(tr("review.error.noWord", String.valueOf(wordId))));
            ReviewMode askedIn = mode == null ? ReviewMode.EN_TO_ZH : mode;
            ReviewMode direction = askedIn == ReviewMode.MIXED ? currentQuestionMode : questionModeFor(askedIn);
            boolean english = direction == ReviewMode.ZH_TO_EN || direction == ReviewMode.CLOZE;
            String correctAnswer = english ? word.getEnglish() : word.getChinese();
            AnswerGrader.DeckWords deckWords = typed -> wordRepository.findByEnglish(word.getDeckId(), typed);
            // A cloze accepts the word as its sentence has it; the sentence is read again in case it was edited.
            AnswerGrade grade = direction == ReviewMode.CLOZE
                ? grader.gradeCloze(word, clozeMaker.make(word).map(Cloze::blankedForms).orElse(List.of()), userAnswer,
                    deckWords)
                : grader.grade(word, direction, userAnswer, deckWords);
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

    /**
     * The word's example sentence split into the word itself, also inflected ("abated" for abate),
     * and the text around it; empty without an example.
     */
    public List<SentenceSpan> exampleSpans(WordCard word) {
        return clozeMaker.highlight(word.getExampleSentence(), word.getEnglish());
    }

    /** Whether the word still exists and is not suspended; false once it was deleted or suspended elsewhere. */
    public boolean isReviewable(long wordId) {
        try {
            return wordRepository.findById(wordId).filter(word -> !word.isSuspended()).isPresent();
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
                .orElseThrow(() -> new IllegalArgumentException(tr("review.error.noWord", String.valueOf(wordId))));
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
     *                   right"); ignored, and not logged, unless it changes what {@code rating}
     *                   counts as ({@link ReviewAnswer#overrideApplies})
     */
    public ReviewOutcome rateCurrent(long wordId, ReviewRating rating, boolean overridden) {
        ReviewAnswer answer = pendingAnswers.get(wordId);
        if (answer == null) {
            throw new IllegalStateException("No answer was submitted for word " + wordId + "; submit an answer before rating it");
        }
        boolean override = answer.overrideApplies(rating, overridden);
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
        boolean firstInSession = sessionWords.add(wordId);
        pushUndo(new UndoEntry(
            new UndoAction(UndoAction.Kind.RATING, saved.before(), answer, answer.direction(), rating,
                saved.earnedXp(), saved.unlocked(), true),
            saved.word().getTags(), saved.log(), saved.word().getDeckId(), saved.completedDailyGoal(),
            true, firstInSession, reviewsSinceNewCard));
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
            .orElseThrow(() -> new IllegalArgumentException(tr("review.error.noWord", String.valueOf(wordId))));
        WordCard before = word.copy();
        LocalDateTime now = LocalDateTime.now(clock);
        ReviewKind kind = kindOf(word, now);
        ReviewLog log = log(wordId, answer, rating, overridden, now, kind);
        if (kind == ReviewKind.PRACTICE) {
            return savePractice(before, word, log, now);
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
        if (progress != null) {
            // The badges' rewards are the only change since the goal update; it is not read again.
            progress = goalService.withAwardedXp(progress, achievementXp);
        }
        return new SavedReview(before, updated, progress, earnedXp, unlocked, becameLeech, kind, reviewCard, log,
            goalUpdate != null && goalUpdate.dailyGoalCompleted());
    }

    /** Logs the practice of a word that is not due; only a failed answer can bring its due date forward. */
    private SavedReview savePractice(WordCard before, WordCard word, ReviewLog log, LocalDateTime now)
        throws SQLException {
        LocalDateTime due = practiceDue(word, log.getEffectiveRating(), now);
        if (!Objects.equals(due, word.getNextReviewAt())) {
            word.setNextReviewAt(due);
            wordRepository.save(word);
        }
        reviewLogRepository.insert(log);
        GoalUpdate goalUpdate = goalService == null
            ? null
            : goalService.recordPractice(word.getDeckId(), log);
        return new SavedReview(before, word, goalUpdate == null ? null : goalUpdate.progress(),
            goalUpdate == null ? 0 : goalUpdate.xpEarned(), List.of(), false, ReviewKind.PRACTICE, false, log, false);
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
     * @param before             the card before the rating, which undoing it restores
     * @param reviewCard         whether the card was in review before; the interleaving of new cards counts these
     * @param log                the saved review log
     * @param completedDailyGoal whether this review completed the day's goal
     */
    private record SavedReview(
        WordCard before,
        WordCard word,
        DailyGoalProgress progress,
        int earnedXp,
        List<Achievement> unlocked,
        boolean becameLeech,
        ReviewKind kind,
        boolean reviewCard,
        ReviewLog log,
        boolean completedDailyGoal
    ) {
    }

    /**
     * Marks the new card {@code wordId} as already known: it goes straight into review with
     * {@value ReviewScheduler#KNOWN_STABILITY_DAYS} days of stability, without a learning session, and
     * a {@link ReviewKind#KNOWN} log records it. That is no review: it earns no XP and counts towards
     * no goal, accuracy, streak, badge or new-cards-per-day limit, nor towards the session. An answer
     * submitted for the card is dropped. Can be undone ({@link #undoLast}). Returns the card as saved.
     *
     * @throws IllegalArgumentException if the card is not new (it was reviewed before)
     */
    public WordCard markKnown(long wordId) {
        ReviewAnswer answer = pendingAnswers.get(wordId);
        ReviewMode questionMode = answer != null ? answer.direction() : currentQuestionMode;
        KnownSaved saved;
        try {
            saved = transactions.inTransaction(() -> {
                WordCard word = wordRepository.findById(wordId)
                    .orElseThrow(() -> new IllegalArgumentException(tr("review.error.noWord", String.valueOf(wordId))));
                if (word.getState() != CardState.NEW) {
                    throw new IllegalArgumentException(tr("review.error.notNew", word.getEnglish()));
                }
                WordCard before = word.copy();
                LocalDateTime now = LocalDateTime.now(clock);
                scheduler.markKnown(word, now);
                wordRepository.update(word);
                ReviewLog log = reviewLogRepository.insert(new ReviewLog(0, wordId, now, "", word.getChinese(),
                    1.0, ReviewRating.EASY, 0, ReviewKind.KNOWN, null, ReviewRating.EASY, false));
                return new KnownSaved(before, word, log);
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot mark the word as known", e);
        }
        pendingAnswers.remove(wordId);
        WordCard after = saved.after();
        pushUndo(new UndoEntry(
            new UndoAction(UndoAction.Kind.KNOWN, saved.before(), answer, questionMode, null, 0, List.of(), true),
            after.getTags(), saved.log(), after.getDeckId(), false, false, false, reviewsSinceNewCard));
        return after;
    }

    private record KnownSaved(WordCard before, WordCard after, ReviewLog log) {
    }

    /**
     * Suspends {@code wordId} from the Review tab: it keeps its schedule but is in no queue or count
     * until it is unsuspended (in the Word List), and an answer submitted for it is dropped. Can be
     * undone ({@link #undoLast}); nothing happens if it is suspended already.
     *
     * @param shown whether it is the card on screen, which undoing shows again; false for a leech
     *              suspended from the notice about it while the next card is shown
     */
    public void suspendWord(long wordId, boolean shown) {
        ReviewAnswer answer = shown ? pendingAnswers.get(wordId) : null;
        ReviewMode questionMode = answer != null ? answer.direction() : currentQuestionMode;
        WordCard before;
        try {
            before = transactions.inTransaction(() -> {
                WordCard word = wordRepository.findById(wordId)
                    .orElseThrow(() -> new IllegalArgumentException(tr("review.error.noWord", String.valueOf(wordId))));
                if (word.isSuspended()) {
                    return null;
                }
                wordRepository.setSuspended(List.of(wordId), true);
                return word;
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot suspend the word", e);
        }
        if (before == null) {
            return;
        }
        if (shown) {
            pendingAnswers.remove(wordId);
        }
        pushUndo(new UndoEntry(
            new UndoAction(UndoAction.Kind.SUSPEND, before, answer, questionMode, null, 0, List.of(), shown),
            before.getTags(), null, before.getDeckId(), false, false, false, reviewsSinceNewCard));
    }

    /** Whether the session has something to undo. */
    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    /** How many of the session's actions can be undone, one after the other. */
    public int undoDepth() {
        return undoStack.size();
    }

    /** What {@link #undoLast} would take back, with the card as it would be again; empty if nothing. */
    public Optional<UndoAction> nextUndo() {
        return Optional.ofNullable(undoStack.peekFirst()).map(UndoEntry::action);
    }

    /**
     * Takes back the session's last rating, Already known or suspension, see the class comment. A
     * rating's answer is kept again, so {@link #rateCurrent} can rate the card again; when the card
     * was on screen, it is again the session's current card, asked the same way.
     *
     * @return what was undone, with the card as it is now
     * @throws IllegalStateException if there is nothing to undo, the card was deleted since (that
     *                               action is dropped, so the one before can be undone next) or
     *                               the database cannot be written (nothing changes)
     */
    public UndoAction undoLast() {
        UndoEntry entry = undoStack.peekFirst();
        if (entry == null) {
            throw new IllegalStateException(tr("review.error.nothingToUndo"));
        }
        UndoAction action = entry.action();
        WordCard restored;
        try {
            restored = transactions.inTransaction(() -> restore(entry));
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot undo", e);
        }
        undoStack.pollFirst();
        if (restored == null) {
            throw new IllegalStateException(tr("review.error.undoDeleted", action.word().getEnglish()));
        }
        // Committed: only in-memory session state changes from here on.
        long wordId = restored.getId();
        if (entry.countedInSession()) {
            sessionReviewed--;
            if (entry.log().isCorrect()) {
                sessionCorrect--;
            }
            sessionXp -= action.xp();
            action.unlocked().forEach(sessionAchievements::remove);
            if (entry.firstInSession()) {
                sessionWords.remove(wordId);
            }
            reviewsSinceNewCard = entry.reviewsSinceNewCard();
        }
        if (action.answer() != null) {
            pendingAnswers.put(wordId, action.answer());
        }
        if (action.wasShown()) {
            currentQuestionMode = action.questionMode();
            currentCloze = action.questionMode() == ReviewMode.CLOZE ? clozeMaker.make(restored).orElse(null) : null;
        }
        return action.withWord(restored);
    }

    /**
     * Restores the card of {@code entry} and deletes what its action wrote; null when the card was
     * deleted since. Runs in the undo's transaction.
     */
    private WordCard restore(UndoEntry entry) throws SQLException {
        UndoAction action = entry.action();
        WordCard before = action.word();
        Optional<WordCard> stored = wordRepository.findById(before.getId());
        if (stored.isEmpty()) {
            return null;
        }
        WordCard card = stored.get();
        card.copyReviewStateFrom(before);
        // The tags the action left (e.g. a new "leech" tag) go back; tags edited since stay.
        if (Objects.equals(card.getTags(), entry.tagsAfter())) {
            card.setTags(before.getTags());
        }
        wordRepository.update(card);
        if (entry.log() != null) {
            reviewLogRepository.deleteById(entry.log().getId());
        }
        if (action.kind() == UndoAction.Kind.RATING) {
            if (goalService != null) {
                goalService.revertReview(entry.deckId(), entry.log(), action.xp(), entry.completedDailyGoal());
            }
            if (achievementService != null && !action.unlocked().isEmpty()) {
                achievementService.revoke(entry.deckId(), action.unlocked());
            }
        }
        return card;
    }

    private void pushUndo(UndoEntry entry) {
        undoStack.addFirst(entry);
        while (undoStack.size() > UNDO_LIMIT) {
            undoStack.removeLast();
        }
    }

    /**
     * One action {@link #undoLast} can take back.
     *
     * @param tagsAfter           the card's tags right after the action
     * @param log                 the log the action wrote; null for a suspension
     * @param completedDailyGoal  whether the rating completed the day's goal
     * @param countedInSession    whether the action is a rating the session counts
     * @param firstInSession      whether the rating was the session's first of this card
     * @param reviewsSinceNewCard the session's review cards since its last new card, before the action
     */
    private record UndoEntry(
        UndoAction action,
        String tagsAfter,
        ReviewLog log,
        long deckId,
        boolean completedDailyGoal,
        boolean countedInSession,
        boolean firstInSession,
        int reviewsSinceNewCard
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
        if (mode == ReviewMode.ZH_TO_EN || mode == ReviewMode.CLOZE) {
            return mode;
        }
        if (mode == ReviewMode.MIXED) {
            return random.nextBoolean() ? ReviewMode.EN_TO_ZH : ReviewMode.ZH_TO_EN;
        }
        return ReviewMode.EN_TO_ZH;
    }
}
