package com.vocabtrainer.ui.review;

import com.vocabtrainer.domain.CardState;
import com.vocabtrainer.domain.DailyGoalProgress;
import com.vocabtrainer.domain.ReviewMode;
import com.vocabtrainer.domain.ReviewOutcome;
import com.vocabtrainer.domain.ReviewRating;
import com.vocabtrainer.domain.ReviewSessionSummary;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.service.AiService;
import com.vocabtrainer.service.AnswerGrade;
import com.vocabtrainer.service.ExplanationRequest;
import com.vocabtrainer.service.GoalService;
import com.vocabtrainer.service.ReviewAnswer;
import com.vocabtrainer.service.ReviewQueueCounts;
import com.vocabtrainer.service.ReviewScheduler;
import com.vocabtrainer.service.ReviewService;
import com.vocabtrainer.service.ReviewSettings;
import com.vocabtrainer.service.cloze.Cloze;
import com.vocabtrainer.service.scheduling.IntervalPreview;
import com.vocabtrainer.ui.DataChange;
import com.vocabtrainer.ui.DataChanges;
import com.vocabtrainer.ui.Formats;
import com.vocabtrainer.ui.LatestRequest;
import com.vocabtrainer.ui.TaskRunner;
import com.vocabtrainer.ui.WordDetails;
import com.vocabtrainer.util.ErrorMessages;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
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
import java.util.regex.Pattern;

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
 * Once an answer is checked, each rating shows the interval it would give and, when the answer
 * check caps it, what it counts as instead ("Hard (53%)"); the suggested rating is the best the
 * answer counts as, at most Good. When the check capped the ratings the user may override it ("I was
 * right"): the rating they choose then counts as it is, and the log says so.
 *
 * Before the answer is submitted, only what cannot give the answer away is shown with the question
 * ({@link #hint()}); once it is checked, the card's phonetic, part of speech, example (the word in
 * bold), note and tags are shown too ({@link #revealedDetails()}).
 *
 * In Cloze mode the question is the card's example with the word blanked out and the hint its
 * Chinese meaning; the session progress counts the cards skipped for want of a usable example.
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
    static final int DEFAULT_SESSION_SIZE = ReviewSettings.DEFAULT_SESSION_SIZE;
    static final int MAX_CUSTOM_SESSION_SIZE = ReviewSettings.MAX_SESSION_SIZE;
    static final String ALL_DUE = "All Due";
    static final String CUSTOM = "Custom";
    /** The session sizes the selector offers, besides All Due and Custom. */
    static final List<Integer> PRESET_SESSION_SIZES = List.of(10, 20, 50);
    /** What the result area says after practicing a weak word that was not due. */
    static final String PRACTICE_SAVED = "Practice saved: the word was not due, so its schedule did not change.";
    /** What the result area says after a failed practice brought the word's due date forward. */
    static final String PRACTICE_MISSED =
        "Practice saved: the word was not due, but after this miss it is due again from the next study day.";
    /** Chinese text, which in an example sentence would give an English-to-Chinese answer away. */
    private static final Pattern HAN = Pattern.compile("\\p{IsHan}");

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
    /** The checked answer of the card on screen; null before it is submitted. */
    private ReviewAnswer checked;
    /** Whether the user overrides the answer check of the card on screen ("I was right"). */
    private boolean overridden;
    /** What the AI explains for the answered card on screen, and the checked answer shown above it. */
    private ExplanationRequest explanationRequest;
    private String checkedText = "";
    private boolean explanationLoading;

    private long deckId;
    private ReviewMode mode;
    /** Whether the user chose Custom, so a custom size of 10, 20 or 50 still shows as Custom. */
    private boolean customSizeChosen;
    /** The deck's new-cards-per-day limit, read when the deck is shown. */
    private int newCardsPerDay = ReviewSettings.DEFAULT_NEW_CARDS_PER_DAY;
    private ReviewMode questionMode = ReviewMode.EN_TO_ZH;
    private State state = State.IDLE;
    private WordCard card;
    private long cardNumber;
    /**
     * Whether the Review tab is on screen. The response time only runs while it is: a card loaded
     * while the user is on another tab (at startup, or after a deck switch) is not being answered.
     */
    private boolean onScreen = true;
    /** Since when the card has been on screen without a break; null while the tab is hidden. */
    private LocalDateTime shownAt;
    /** How long the card was on screen before the tab was last hidden. */
    private Duration shownBefore = Duration.ZERO;
    private String answer = "";
    private String question = LOADING;
    private String details = "";
    private String answerPrompt = ReviewMode.EN_TO_ZH.getPrompt();
    private String hint = "";
    /** The cloze of the card on screen in Cloze mode; null otherwise. */
    private Cloze cloze;
    /** The answered card's details; null until its answer is checked. */
    private WordDetails revealed;
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
        this.mode = reviewService.sessionMode();
    }

    /** Called after every change of the state or of a displayed text. */
    public void addListener(Runnable listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    // ---- Input ----

    /** Starts a new session on {@code deckId}, with the current mode and size, and shows its first card. */
    public void showDeck(long deckId) {
        this.deckId = deckId;
        resetSession();
    }

    /** Starts a new session in {@code mode}, with the current size; the next launch starts in this mode. */
    public void changeMode(ReviewMode mode) {
        ReviewMode chosen = mode == null ? ReviewMode.EN_TO_ZH : mode;
        if (chosen == this.mode) {
            return;
        }
        this.mode = chosen;
        resetSession();
    }

    /** Starts a new session with the current mode and size. */
    public void resetSession() {
        try {
            newCardsPerDay = reviewService.newCardsPerDay(deckId);
            reviewService.startSession(deckId, mode, reviewService.sessionTarget());
            loadNextCard();
        } finally {
            fireChanged();
        }
    }

    /**
     * Starts a new session of the chosen size, see {@link #parseSessionSize}.
     *
     * @throws IllegalArgumentException if the custom size is not a number from 1 to 500
     */
    public void startSession(String sizeChoice, String customSize) {
        int target = parseSessionSize(sizeChoice, customSize);
        try {
            customSizeChosen = CUSTOM.equals(sizeChoice);
            reviewService.startSession(deckId, mode, target);
            loadNextCard();
        } finally {
            fireChanged();
        }
    }

    /**
     * The user chose a session size: the running session takes it as its target at once and keeps
     * what it has done, and the next launch starts with it. A custom size that is not a number from
     * 1 to 500 yet (the user is still typing) changes nothing. A session that had reached its target
     * goes on when the new one is larger.
     */
    public void selectSessionSize(String sizeChoice, String customText) {
        int target;
        try {
            target = parseSessionSize(sizeChoice, customText);
        } catch (IllegalArgumentException e) {
            return;
        }
        boolean custom = CUSTOM.equals(sizeChoice);
        if (target == reviewService.sessionTarget() && custom == customSizeChosen) {
            return;
        }
        customSizeChosen = custom;
        try {
            reviewService.setSessionTarget(target);
            if (state == State.COMPLETE) {
                loadNextCard();
            } else {
                updateSessionProgress();
            }
        } finally {
            fireChanged();
        }
    }

    /**
     * Sets the deck's new-cards-per-day limit. A session that ran out of cards goes on when the new
     * limit lets more new cards in today.
     *
     * @throws IllegalArgumentException if {@code limit} is not from 0 to 9999
     */
    public void setNewCardsPerDay(int limit) {
        if (limit == newCardsPerDay) {
            return;
        }
        reviewService.setNewCardsPerDay(deckId, limit);
        newCardsPerDay = limit;
        try {
            if (state == State.COMPLETE) {
                loadNextCard();
            }
        } finally {
            fireChanged();
        }
        changes.publish(DataChange.REVIEW_SETTINGS);
    }

    /**
     * The goals were edited on the Dashboard: the session takes the session goal as its target, as if
     * it had been chosen here, and a completed session shows the new daily goals.
     */
    public void goalsChanged() {
        try {
            int target = goalService.settings().sessionGoal();
            if (target != reviewService.sessionTarget()) {
                customSizeChosen = false;
                reviewService.setSessionTarget(target);
            }
            if (state == State.COMPLETE) {
                loadNextCard();
            } else {
                updateSessionProgress();
            }
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
        // The service times the answer from "shown at" to now: count only the time it was on screen.
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime effectivelyShownAt = now.minus(timeOnScreen(now));
        // The session's mode, not the card's direction: in Mixed mode the service checks the answer in
        // the direction it chose for the card, and an Easy recognition must count as Mixed mode's.
        checked = reviewService.submitAnswer(answered.getId(), answer, mode, effectivelyShownAt);
        overridden = false;
        revealed = WordDetails.of(answered, reviewService.exampleSpans(answered));
        previewRatings();
        checkedText = "Correct answer: " + checked.correctAnswer() + formsInSentence(checked, cloze)
            + System.lineSeparator() + "Your answer: " + checked.userAnswer()
            + System.lineSeparator() + "Answer similarity: " + Formats.percent(checked.similarity())
            + verdict(checked);
        state = State.ANSWERED;
        explanationRequest = new ExplanationRequest(answered, checked.userAnswer(), checked.direction());
        requestExplanation(false);
    }

    /**
     * Asks the AI provider again for the answered card's explanation, bypassing the cache. Does
     * nothing unless {@link #canRegenerateExplanation()}.
     */
    public void regenerateExplanation() {
        if (canRegenerateExplanation()) {
            requestExplanation(true);
        }
    }

    /**
     * The user says the checked answer was right after all ("I was right"), or takes that back: while
     * overridden, the rating they choose counts as it is instead of being capped by the check. Does
     * nothing unless the check capped the ratings ({@link #canOverride()}).
     */
    public void setOverridden(boolean override) {
        if (!canOverride() || override == overridden) {
            return;
        }
        overridden = override;
        previewRatings();
        fireChanged();
    }

    /** Saves the rating of the answered card and shows the next card; does nothing before an answer was submitted. */
    public void rate(ReviewRating rating) {
        if (!canRate()) {
            return;
        }
        long wordId = card.getId();
        LocalDateTime dueBefore = card.getNextReviewAt();
        ReviewRating countsAs = checked == null ? rating : checked.countsAs(rating, overridden);
        boolean override = checked != null && checked.overrideApplies(rating, overridden);
        state = State.SAVING;
        fireChanged();
        ReviewOutcome outcome;
        try {
            outcome = reviewService.rateCurrent(wordId, rating, overridden);
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
                result = savedMessage(outcome, dueBefore, rating, countsAs, override) + " XP +" + outcome.xpEarned()
                    + Formats.unlockedSuffix(outcome.unlockedAchievements()) + leechNotice(outcome);
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

    /**
     * The Review tab was shown or hidden. The response time of the card on screen only counts the
     * time the tab was shown.
     */
    public void setOnScreen(boolean visible) {
        if (visible == onScreen) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (visible) {
            shownAt = now;
        } else {
            shownBefore = timeOnScreen(now);
            shownAt = null;
        }
        onScreen = visible;
    }

    // ---- State ----

    public State state() {
        return state;
    }

    /** The review mode of the session. */
    public ReviewMode mode() {
        return mode;
    }

    /** What the session-size selector shows for the session's target: "10", "20", "50", "All Due" or "Custom". */
    public String sessionSizeChoice() {
        int target = reviewService.sessionTarget();
        if (target == 0) {
            return ALL_DUE;
        }
        return customSizeChosen || !PRESET_SESSION_SIZES.contains(target) ? CUSTOM : String.valueOf(target);
    }

    /** The custom session size, when the selector shows Custom; otherwise empty. */
    public String customSessionSize() {
        return CUSTOM.equals(sessionSizeChoice()) ? String.valueOf(reviewService.sessionTarget()) : "";
    }

    /** The session's target: a number of different cards, or 0 for All Due. */
    public int sessionTarget() {
        return reviewService.sessionTarget();
    }

    /** The current deck's new-cards-per-day limit. */
    public int newCardsPerDay() {
        return newCardsPerDay;
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

    /** Whether the answer check capped the ratings, so the user may say "I was right"; see {@link #setOverridden}. */
    public boolean canOverride() {
        return canRate() && checked != null && checked.canOverride();
    }

    /** Whether the user overrides the answer check of the card on screen. */
    public boolean isOverridden() {
        return overridden;
    }

    /**
     * The rating to suggest once the answer is checked: the best the answer counts as, at most Good
     * (Good when the user overrides the check); empty while nothing can be rated.
     */
    public Optional<ReviewRating> suggestedRating() {
        return canRate() && checked != null ? Optional.of(checked.suggestedRating(overridden)) : Optional.empty();
    }

    /**
     * What choosing {@code rating} counts as when that is not the rating itself, e.g. "Hard (53%)"
     * when the answer check caps it (with the answer similarity), or "Good" for an Easy recognition
     * in Mixed mode; empty when it counts as itself or no answer is checked.
     */
    public String ratingCountsAs(ReviewRating rating) {
        if (checked == null || !(canRate() || state == State.SAVING)) {
            return "";
        }
        ReviewRating counted = checked.countsAs(rating, overridden);
        if (counted == rating) {
            return "";
        }
        boolean cappedByCheck = checked.countsAs(rating, true) != counted;
        return cappedByCheck ? counted.getLabel() + " (" + Formats.percent(checked.similarity()) + ")" : counted.getLabel();
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

    /**
     * Whether the explanation of the answered card can be asked for again: it has arrived, and an AI
     * provider is configured and may be used.
     */
    public boolean canRegenerateExplanation() {
        return canRate() && explanationRequest != null && !explanationLoading && usesAiProvider();
    }

    /** Whether explanations come from an AI provider, rather than the offline mock text. */
    public boolean usesAiProvider() {
        return aiServices.get().isAvailable();
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

    /**
     * What is shown with the question before it is answered, as long as it cannot give the answer
     * away (see {@link #hint(WordCard, ReviewMode)}); empty without a card.
     */
    public String hint() {
        return hint;
    }

    /**
     * The answered card's phonetic, part of speech, example (with the word marked), note and tags;
     * empty until the answer is checked, so nothing there gives the answer away.
     */
    public Optional<WordDetails> revealedDetails() {
        boolean answered = canRate() || state == State.SAVING;
        return answered ? Optional.ofNullable(revealed) : Optional.empty();
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
        if (ALL_DUE.equals(choice)) {
            return 0;
        }
        if (CUSTOM.equals(choice)) {
            int custom;
            try {
                custom = Integer.parseInt(customSize == null ? "" : customSize.trim());
            } catch (NumberFormatException e) {
                custom = -1;
            }
            if (custom <= 0 || custom > MAX_CUSTOM_SESSION_SIZE) {
                throw new IllegalArgumentException("Custom session size must be between 1 and 500.");
            }
            return custom;
        }
        return choice == null ? DEFAULT_SESSION_SIZE : Integer.parseInt(choice);
    }

    // ---- Internals ----

    /** How long the card has been on screen by {@code now}. */
    private Duration timeOnScreen(LocalDateTime now) {
        if (shownAt == null) {
            return shownBefore;
        }
        Duration since = Duration.between(shownAt, now);
        return since.isNegative() ? shownBefore : shownBefore.plus(since);
    }

    private void loadNextCard() {
        explanations.invalidate();
        explanationRequest = null;
        explanationLoading = false;
        card = null;
        answer = "";
        result = "";
        ratingPreviews.clear();
        checked = null;
        revealed = null;
        hint = "";
        cloze = null;
        overridden = false;
        cardNumber++;
        Optional<WordCard> next;
        try {
            next = reviewService.nextWord(deckId, mode);
        } catch (RuntimeException e) {
            state = State.IDLE;
            throw e;
        }
        card = next.orElse(null);
        LocalDateTime now = LocalDateTime.now(clock);
        shownAt = onScreen ? now : null;
        shownBefore = Duration.ZERO;
        questionMode = reviewService.currentQuestionMode();
        answerPrompt = questionMode.getPrompt();
        ReviewSessionSummary session = updateSessionProgress();
        if (card == null) {
            showCompletion(session);
            return;
        }
        state = State.AWAITING_ANSWER;
        cloze = questionMode == ReviewMode.CLOZE ? reviewService.currentCloze().orElse(null) : null;
        question = switch (questionMode) {
            case ZH_TO_EN -> card.getChinese();
            // The service only shows cards whose example makes a cloze; never show the English word.
            case CLOZE -> cloze == null ? card.getChinese() : cloze.masked();
            default -> card.getEnglish();
        };
        hint = hint(card, questionMode);
        details = questionMode.getLabel() + " | " + cardDetails(card, now);
    }

    /**
     * What may be shown with a question asked in {@code direction} before it is answered. English to
     * Chinese: the phonetic and part of speech and, on a line of its own, the example sentence unless
     * it has Chinese in it (a translation would give the meaning away). Chinese to English: only the
     * part of speech, since the phonetic and the example give the word away. Cloze: the Chinese
     * meaning and the part of speech (the question is the example, blanked). Never the note or tags,
     * which often hold the meaning or a definition.
     */
    static String hint(WordCard card, ReviewMode direction) {
        String partOfSpeech = clean(card.getPartOfSpeech());
        if (direction == ReviewMode.ZH_TO_EN) {
            return partOfSpeech;
        }
        if (direction == ReviewMode.CLOZE) {
            return String.join(" · ", nonEmpty("Hint: " + clean(card.getChinese()), partOfSpeech));
        }
        String line = String.join(" · ", nonEmpty(clean(card.getPhonetic()), partOfSpeech));
        String example = clean(card.getExampleSentence());
        if (example.isEmpty() || HAN.matcher(example).find()) {
            return line;
        }
        return line.isEmpty() ? example : line + System.lineSeparator() + example;
    }

    private static List<String> nonEmpty(String... values) {
        return Arrays.stream(values).filter(value -> !value.isEmpty()).toList();
    }

    private static String clean(String value) {
        return value == null ? "" : value.strip();
    }

    private ReviewSessionSummary updateSessionProgress() {
        ReviewSessionSummary session = reviewService.sessionSummary();
        String target = session.sessionGoal() > 0 ? String.valueOf(session.sessionGoal()) : ALL_DUE;
        int skipped = reviewService.clozeSkippedCount();
        sessionProgress = "Session " + session.cardsReviewed() + "/" + target
            + " | Accuracy " + Formats.percent(session.accuracy())
            + " | XP " + session.xpEarned()
            + (skipped > 0 ? " | " + skippedWithoutExamples(skipped) : "");
        return session;
    }

    /** "12 cards without examples skipped", for the cards a Cloze session could not ask. */
    static String skippedWithoutExamples(int count) {
        return count == 1 ? "1 card without an example skipped" : count + " cards without examples skipped";
    }

    /** Whether the question is a sentence (a cloze) rather than a word or a meaning. */
    public boolean isSentenceQuestion() {
        return cloze != null && card != null;
    }

    /**
     * For a cloze whose blank held another form of the word, " (in the sentence: admonished)";
     * otherwise nothing.
     */
    private static String formsInSentence(ReviewAnswer checked, Cloze cloze) {
        if (cloze == null || checked.direction() != ReviewMode.CLOZE) {
            return "";
        }
        List<String> others = cloze.blankedForms().stream()
            .filter(form -> !form.equalsIgnoreCase(checked.correctAnswer()))
            .toList();
        return others.isEmpty() ? "" : " (in the sentence: " + String.join(", ", others) + ")";
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

    /** Asks the service for the interval each rating would give the checked answer. */
    private void previewRatings() {
        ratingPreviews.clear();
        try {
            ratingPreviews.putAll(reviewService.previewRatings(card.getId(), overridden));
        } catch (RuntimeException e) {
            // Only the hints on the buttons are missing; rating still works.
            LOGGER.log(Level.WARNING, "Cannot preview the intervals of " + card.getEnglish(), e);
        }
    }

    /**
     * Why the answer counts as less than a match, on a line of its own; nothing for a match. Says
     * what a synonym, another word or a typo counts as, and that the user may override a cap.
     */
    static String verdict(ReviewAnswer checked) {
        AnswerGrade grade = checked.grade();
        String cap = grade.maxRating().getLabel();
        String text = switch (grade.verdict()) {
            case MATCH -> "";
            case SYNONYM -> "Accepted as a synonym: \"" + grade.otherWord() + "\" (" + grade.otherGloss()
                + ") also fits this prompt. Counts as right.";
            case MISSPELLED -> "Misspelled: counts at most as " + cap + ".";
            case CONFUSABLE -> "\"" + grade.otherWord() + "\" is a different word"
                + (grade.otherGloss() == null ? ", easily confused with \"" + checked.english() + "\"" : " (" + grade.otherGloss() + ")")
                + ": counts as " + cap + ".";
            case PARTIAL -> "Close, but not a listed meaning: counts at most as " + cap + ".";
            case WRONG -> "Does not match: counts as " + cap + ".";
        };
        if (checked.canOverride()) {
            text += " If your answer was right, choose \"I was right\" and rate it yourself.";
        }
        return text.isEmpty() ? "" : System.lineSeparator() + text;
    }

    /**
     * "Saved.", "Saved as Hard." when the answer check lowered the rating, a note when the user
     * overrode the check, or for a practice whether it left the due date as it was.
     */
    private static String savedMessage(ReviewOutcome outcome, LocalDateTime dueBefore, ReviewRating rating,
                                       ReviewRating countsAs, boolean overridden) {
        if (outcome.isPractice()) {
            return Objects.equals(dueBefore, outcome.word().getNextReviewAt()) ? PRACTICE_SAVED : PRACTICE_MISSED;
        }
        if (overridden) {
            return "Saved as " + countsAs.getLabel() + ": you overrode the answer check.";
        }
        return countsAs == rating ? "Saved." : "Saved as " + countsAs.getLabel() + ".";
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
        details = targetReached ? "Session target reached." : nothingLeft();
        completionTitle = "Session Complete";
        completionMetrics = "Completed: " + session.cardsReviewed()
            + (session.sessionGoal() > 0 ? "/" + session.sessionGoal() : " / All Due")
            + " | Accuracy: " + Formats.percent(session.accuracy())
            + " | XP: " + session.xpEarned()
            + System.lineSeparator() + "Today review goal: " + progress.reviewedCount() + "/" + progress.reviewGoal()
            + " | New words: " + progress.newWordsCount() + "/" + progress.newWordGoal()
            + System.lineSeparator() + "Unlocked: " + Formats.achievementNames(session.unlockedAchievements());
        int skipped = reviewService.clozeSkippedCount();
        if (skipped > 0) {
            completionMetrics += System.lineSeparator() + skippedWithoutExamples(skipped)
                + ": an example sentence that contains the word lets Cloze mode ask them.";
        }
        result = mode == ReviewMode.WEAK_WORDS
            ? "Reset Session to go through the weak words again, or switch deck from the header."
            : "Use Weak Words mode to keep working on your most fragile cards, or switch deck from the header.";
    }

    /** Why a session that did not reach its target has no card left. */
    private String nothingLeft() {
        if (mode == ReviewMode.WEAK_WORDS) {
            return "Every weak word was shown in this session.";
        }
        int skipped = reviewService.clozeSkippedCount();
        if (skipped > 0) {
            return "No due words with a usable example right now; " + skippedWithoutExamples(skipped) + ".";
        }
        ReviewQueueCounts queue = reviewService.queueCounts(deckId);
        if (queue.newCardsDue() > 0 && queue.newAvailableToday() == 0) {
            return "No due words right now; today's limit of " + queue.newCardsPerDay() + " new words is reached.";
        }
        return "No due words right now.";
    }

    /** Shows the checked answer with "loading" and asks the AI service for its explanation. */
    private void requestExplanation(boolean regenerate) {
        String separator = System.lineSeparator() + System.lineSeparator();
        String shownAnswer = checkedText;
        ExplanationRequest request = explanationRequest;
        result = shownAnswer + separator + "AI explanation: loading...";
        explanationLoading = true;
        long ticket = explanations.next();
        AiService ai = aiServices.get();
        fireChanged();
        tasks.run(
            () -> regenerate ? ai.regenerate(request) : ai.explain(request),
            explanation -> showExplanation(ticket, shownAnswer + separator + explanation),
            error -> showExplanation(ticket, shownAnswer + separator
                + "AI explanation unavailable: " + ErrorMessages.rootMessage(error))
        );
    }

    /** Shows an explanation only for the answer it was requested for, never on a later card. */
    private void showExplanation(long ticket, String text) {
        if (explanations.isLatest(ticket) && (state == State.ANSWERED || state == State.SAVING
            || state == State.RATING_FAILED)) {
            result = text;
            explanationLoading = false;
            fireChanged();
        }
    }

    private void fireChanged() {
        listeners.forEach(Runnable::run);
    }
}
