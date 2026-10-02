package com.vocabtrainer.ui.review;

import com.vocabtrainer.domain.CardState;
import com.vocabtrainer.domain.DailyGoalProgress;
import com.vocabtrainer.domain.ReviewMode;
import com.vocabtrainer.domain.ReviewOutcome;
import com.vocabtrainer.domain.ReviewRating;
import com.vocabtrainer.domain.ReviewSessionSummary;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.service.AiService;
import com.vocabtrainer.service.GoalService;
import com.vocabtrainer.service.ReviewAnswer;
import com.vocabtrainer.service.ReviewScheduler;
import com.vocabtrainer.service.ReviewService;
import com.vocabtrainer.service.scheduling.IntervalPreview;
import com.vocabtrainer.ui.DataChange;
import com.vocabtrainer.ui.DataChanges;
import com.vocabtrainer.ui.Formats;
import com.vocabtrainer.ui.LatestRequest;
import com.vocabtrainer.ui.TaskRunner;
import com.vocabtrainer.util.ErrorMessages;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The Review tab without JavaFX: which card is shown, what the user can do next and every text the
 * tab displays. {@link ReviewView} forwards input here and renders the state after each change, so
 * the review flow can be unit-tested without a display.
 *
 * <pre>
 * IDLE --showDeck--&gt; AWAITING_ANSWER --submit--&gt; ANSWERED --rate--&gt; SAVING --saved--&gt; AWAITING_ANSWER (next card)
 *                          |                                                 \--saved--&gt; COMPLETE (no card left)
 *                          |                                                 \--failed--&gt; RATING_FAILED --rate--&gt; SAVING
 *                          \--no card left--&gt; COMPLETE
 * </pre>
 *
 * Call it on the UI thread only.
 */
public final class ReviewSessionPresenter {
    public enum State {
        /** No card is loaded yet, or loading one failed. */
        IDLE,
        /** A card is shown and waits for a typed answer. */
        AWAITING_ANSWER,
        /** The answer was checked and the result is shown; the card waits for a rating. */
        ANSWERED,
        /** The rating is being saved. */
        SAVING,
        /** Saving the rating failed; the answer is kept, so rating again retries it. */
        RATING_FAILED,
        /** No card is left: the session target was reached or nothing is due. */
        COMPLETE
    }

    /** Shows a failure to the user, e.g. in an error dialog. */
    @FunctionalInterface
    public interface FailureReporter {
        void report(String title, Throwable error);
    }

    private static final Logger LOGGER = Logger.getLogger(ReviewSessionPresenter.class.getName());
    static final String LOADING = "Loading...";
    static final int DEFAULT_SESSION_SIZE = 20;
    static final int MAX_CUSTOM_SESSION_SIZE = 500;

    private final ReviewService reviewService;
    private final GoalService goalService;
    private final Supplier<AiService> aiServices;
    private final TaskRunner tasks;
    private final DataChanges changes;
    private final FailureReporter failures;
    private final Clock clock;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final LatestRequest explanations = new LatestRequest();
    private final Map<ReviewRating, IntervalPreview> ratingPreviews = new EnumMap<>(ReviewRating.class);

    private long deckId;
    private ReviewMode mode = ReviewMode.EN_TO_ZH;
    private ReviewMode questionMode = ReviewMode.EN_TO_ZH;
    private State state = State.IDLE;
    private WordCard card;
    private long cardNumber;
    /** When the card on screen was shown; the response time runs from here to the submit. */
    private LocalDateTime shownAt;
    private String answer = "";
    private String question = LOADING;
    private String details = "";
    private String answerPrompt = ReviewMode.EN_TO_ZH.getPrompt();
    private String result = "";
    private String sessionProgress = "";
    private String completionTitle = "Review complete";
    private String completionMetrics = "";

    /**
     * @param aiServices the AI service to explain answers with, asked again for every answer so
     *                   changed AI settings apply at once
     * @param tasks      runs the AI explanation off the UI thread
     * @param changes    told about every saved review
     * @param failures   shows ratings that could not be saved
     */
    public ReviewSessionPresenter(ReviewService reviewService, GoalService goalService, Supplier<AiService> aiServices,
                                  TaskRunner tasks, DataChanges changes, FailureReporter failures) {
        this(reviewService, goalService, aiServices, tasks, changes, failures, Clock.systemDefaultZone());
    }

    /** @param clock times how long the user takes to answer a card, and dates the card details */
    public ReviewSessionPresenter(ReviewService reviewService, GoalService goalService, Supplier<AiService> aiServices,
                                  TaskRunner tasks, DataChanges changes, FailureReporter failures, Clock clock) {
        this.reviewService = reviewService;
        this.goalService = goalService;
        this.aiServices = aiServices;
        this.tasks = tasks;
        this.changes = changes;
        this.failures = failures;
        this.clock = clock;
    }

    /** Called after every change of the state or of a displayed text. */
    public void addListener(Runnable listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    // ---- Input ----

    /** Starts a new session on {@code deckId} with the default size and shows its first card. */
    public void showDeck(long deckId) {
        this.deckId = deckId;
        resetSession();
    }

    /** Starts a new session in {@code mode} with the default size. */
    public void changeMode(ReviewMode mode) {
        this.mode = mode == null ? ReviewMode.EN_TO_ZH : mode;
        resetSession();
    }

    /** Starts a new session with the default size. */
    public void resetSession() {
        try {
            reviewService.resetSession(deckId);
            loadNextCard();
        } finally {
            fireChanged();
        }
    }

    /**
     * Starts a session of the chosen size, see {@link #parseSessionSize}.
     *
     * @throws IllegalArgumentException if the custom size is not a number from 1 to 500
     */
    public void startSession(String sizeChoice, String customSize) {
        int target = parseSessionSize(sizeChoice, customSize);
        try {
            reviewService.startSession(deckId, mode, target);
            loadNextCard();
        } finally {
            fireChanged();
        }
    }

    /** The text in the answer field; the user may edit it until the answer is submitted. */
    public void setAnswer(String text) {
        answer = text == null ? "" : text;
    }

    /**
     * Checks the typed answer, shows the correct one and the interval each rating would give, and
     * asks the AI service for an explanation.
     */
    public void submit() {
        if (state != State.AWAITING_ANSWER) {
            return;
        }
        WordCard answered = card;
        ReviewAnswer checked = reviewService.submitAnswer(answered.getId(), answer, questionMode, shownAt);
        ratingPreviews.clear();
        try {
            ratingPreviews.putAll(reviewService.previewRatings(answered.getId()));
        } catch (RuntimeException e) {
            // Only the hints on the buttons are missing; rating still works.
            LOGGER.log(Level.WARNING, "Cannot preview the intervals of " + answered.getEnglish(), e);
        }
        String checkedText = "Correct answer: " + checked.correctAnswer()
            + System.lineSeparator() + "Your answer: " + checked.userAnswer()
            + System.lineSeparator() + "Answer similarity: " + Formats.percent(checked.similarity());
        String separator = System.lineSeparator() + System.lineSeparator();
        result = checkedText + separator + "AI explanation: loading...";
        state = State.ANSWERED;
        long ticket = explanations.next();
        AiService ai = aiServices.get();
        fireChanged();
        tasks.run(
            () -> ai.explain(answered),
            explanation -> showExplanation(ticket, checkedText + separator + explanation),
            error -> showExplanation(ticket, checkedText + separator
                + "AI explanation unavailable: " + ErrorMessages.rootMessage(error))
        );
    }

    /** Saves the rating of the answered card and shows the next card; does nothing before an answer was submitted. */
    public void rate(ReviewRating rating) {
        if (!canRate()) {
            return;
        }
        long wordId = card.getId();
        state = State.SAVING;
        fireChanged();
        ReviewOutcome outcome;
        try {
            outcome = reviewService.rateCurrent(wordId, rating);
        } catch (RuntimeException e) {
            // Nothing was saved. The service normally keeps the submitted answer, so the same card
            // and answer stay on screen and rating again retries.
            if (reviewService.hasPendingAnswer(wordId)) {
                failures.report("Rating not saved - choose a rating again to retry", e);
                state = State.RATING_FAILED;
            } else {
                failures.report("Rating not saved - submit your answer again", e);
                explanations.invalidate();
                state = State.AWAITING_ANSWER;
                result = "The rating was not saved. Submit your answer again to retry.";
            }
            fireChanged();
            return;
        }
        RuntimeException failure = null;
        try {
            loadNextCard();
            if (card != null) {
                result = "Saved. XP +" + outcome.xpEarned() + Formats.unlockedSuffix(outcome.unlockedAchievements())
                    + leechNotice(outcome);
            }
        } catch (RuntimeException e) {
            failure = e;
        } finally {
            fireChanged();
        }
        try {
            changes.publish(DataChange.REVIEWS);
        } catch (RuntimeException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        if (failure != null) {
            failures.report("Rating saved, but refreshing the review failed", failure);
        }
    }

    /**
     * Words were added, edited, deleted, imported or restored, possibly in another deck. A card the
     * user is looking at stays, with whatever was typed or submitted for it, unless it was deleted;
     * without a card (nothing was due) the next card is looked for again.
     */
    public void wordsChanged() {
        boolean cardGone = card != null && !reviewService.isReviewable(card.getId());
        if (!cardGone && state != State.IDLE && state != State.COMPLETE) {
            return;
        }
        try {
            loadNextCard();
        } finally {
            fireChanged();
        }
    }

    // ---- State ----

    public State state() {
        return state;
    }

    /** The card on screen; empty when the session is complete or nothing is loaded. */
    public Optional<WordCard> card() {
        return Optional.ofNullable(card);
    }

    /** Counts the cards shown so far; it changes with every new card, even if the same word comes again. */
    public long cardNumber() {
        return cardNumber;
    }

    public boolean canSubmit() {
        return state == State.AWAITING_ANSWER;
    }

    public boolean canRate() {
        return state == State.ANSWERED || state == State.RATING_FAILED;
    }

    /**
     * When {@code rating} would bring the answered card back, e.g. "10m" or "4d"; empty while no
     * answer is checked.
     */
    public String ratingPreview(ReviewRating rating) {
        IntervalPreview preview = canRate() || state == State.SAVING ? ratingPreviews.get(rating) : null;
        return preview == null ? "" : Formats.interval(preview);
    }

    public boolean isComplete() {
        return state == State.COMPLETE;
    }

    public String answer() {
        return answer;
    }

    /** The word to translate, or "Review complete". */
    public String question() {
        return question;
    }

    /** The question direction and the card's schedule, or why the session is complete. */
    public String details() {
        return details;
    }

    public String answerPrompt() {
        return answerPrompt;
    }

    /** The checked answer with its explanation, the saved rating, or a hint. */
    public String result() {
        return result;
    }

    public String sessionProgress() {
        return sessionProgress;
    }

    public String completionTitle() {
        return completionTitle;
    }

    public String completionMetrics() {
        return completionMetrics;
    }

    /**
     * The session target for a choice of the session-size selector: a number of cards, 0 for
     * "All Due", or the custom size for "Custom".
     *
     * @throws IllegalArgumentException if the custom size is not a number from 1 to 500
     */
    public static int parseSessionSize(String choice, String customSize) {
        if ("All Due".equals(choice)) {
            return 0;
        }
        if ("Custom".equals(choice)) {
            int custom = Integer.parseInt(customSize == null ? "" : customSize.trim());
            if (custom <= 0 || custom > MAX_CUSTOM_SESSION_SIZE) {
                throw new IllegalArgumentException("Custom session size must be between 1 and 500.");
            }
            return custom;
        }
        return choice == null ? DEFAULT_SESSION_SIZE : Integer.parseInt(choice);
    }

    // ---- Internals ----

    private void loadNextCard() {
        explanations.invalidate();
        card = null;
        answer = "";
        result = "";
        ratingPreviews.clear();
        cardNumber++;
        Optional<WordCard> next;
        try {
            next = reviewService.nextWord(deckId, mode);
        } catch (RuntimeException e) {
            state = State.IDLE;
            throw e;
        }
        card = next.orElse(null);
        shownAt = LocalDateTime.now(clock);
        questionMode = reviewService.currentQuestionMode();
        answerPrompt = questionMode.getPrompt();
        ReviewSessionSummary session = reviewService.sessionSummary();
        String target = session.sessionGoal() > 0 ? String.valueOf(session.sessionGoal()) : "All Due";
        sessionProgress = "Session " + session.cardsReviewed() + "/" + target
            + " | Accuracy " + Formats.percent(session.accuracy())
            + " | XP " + session.xpEarned();
        if (card == null) {
            showCompletion(session);
            return;
        }
        state = State.AWAITING_ANSWER;
        question = questionMode == ReviewMode.ZH_TO_EN ? card.getChinese() : card.getEnglish();
        details = questionMode.getLabel() + " | " + cardDetails(card, shownAt);
    }

    /**
     * The card's state and memory: "New", or e.g. "Review | Recall 87% | Stability 12.3d |
     * Difficulty 5.3 | Lapses 1", with "Leech" for a leech.
     */
    static String cardDetails(WordCard card, LocalDateTime now) {
        StringBuilder text = new StringBuilder(stateName(card.getState()));
        OptionalDouble recall = ReviewScheduler.retrievability(card, now);
        if (recall.isPresent()) {
            text.append(" | Recall ").append(Formats.percent(recall.getAsDouble()))
                .append(" | Stability ").append(String.format(Locale.ROOT, "%.1fd", card.getStability()))
                .append(" | Difficulty ").append(String.format(Locale.ROOT, "%.1f", card.getDifficulty()));
        }
        text.append(" | Lapses ").append(card.getLapses());
        if (card.isLeech()) {
            text.append(" | Leech");
        }
        return text.toString();
    }

    private static String stateName(CardState state) {
        return switch (state) {
            case NEW -> "New";
            case LEARNING -> "Learning";
            case REVIEW -> "Review";
            case RELEARNING -> "Relearning";
        };
    }

    private static String leechNotice(ReviewOutcome outcome) {
        return outcome.becameLeech()
            ? System.lineSeparator() + "\"" + outcome.word().getEnglish() + "\" lapsed " + outcome.word().getLapses()
                + " times and is now tagged as a leech: try a mnemonic, an example of your own, or edit the card."
            : "";
    }

    private void showCompletion(ReviewSessionSummary session) {
        DailyGoalProgress progress = goalService.getTodayProgress(deckId);
        state = State.COMPLETE;
        question = "Review complete";
        boolean targetReached = session.sessionGoal() > 0 && session.cardsReviewed() >= session.sessionGoal();
        details = targetReached ? "Session target reached." : "No due words right now.";
        completionTitle = "Session Complete";
        completionMetrics = "Completed: " + session.cardsReviewed()
            + (session.sessionGoal() > 0 ? "/" + session.sessionGoal() : " / All Due")
            + " | Accuracy: " + Formats.percent(session.accuracy())
            + " | XP: " + session.xpEarned()
            + System.lineSeparator() + "Today review goal: " + progress.reviewedCount() + "/" + progress.reviewGoal()
            + " | New words: " + progress.newWordsCount() + "/" + progress.newWordGoal()
            + System.lineSeparator() + "Unlocked: " + Formats.achievementNames(session.unlockedAchievements());
        result = "Use Weak Words mode to keep working on your most fragile cards, or switch deck from the header.";
    }

    /** Shows an explanation only for the answer it was requested for, never on a later card. */
    private void showExplanation(long ticket, String text) {
        if (explanations.isLatest(ticket) && (state == State.ANSWERED || state == State.SAVING
            || state == State.RATING_FAILED)) {
            result = text;
            fireChanged();
        }
    }

    private void fireChanged() {
        listeners.forEach(Runnable::run);
    }
}
