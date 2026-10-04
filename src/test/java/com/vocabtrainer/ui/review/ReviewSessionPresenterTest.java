package com.vocabtrainer.ui.review;

import com.vocabtrainer.TestClock;
import com.vocabtrainer.app.AppServices;
import com.vocabtrainer.domain.CardState;
import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.GoalTargets;
import com.vocabtrainer.domain.ReviewLog;
import com.vocabtrainer.domain.ReviewMode;
import com.vocabtrainer.domain.ReviewRating;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.ReviewLogRepository;
import com.vocabtrainer.repository.TestDatabases;
import com.vocabtrainer.service.AiService;
import com.vocabtrainer.service.ReviewService;
import com.vocabtrainer.service.SimilarityService;
import com.vocabtrainer.service.cloze.SentenceSpan;
import com.vocabtrainer.ui.DataChange;
import com.vocabtrainer.ui.DataChanges;
import com.vocabtrainer.ui.TaskRunner;
import com.vocabtrainer.ui.WordDetails;
import com.vocabtrainer.ui.review.ReviewSessionPresenter.State;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The review flow without JavaFX: a real database, AI explanations that finish when the test says so. */
class ReviewSessionPresenterTest {
    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    /** The clock of the services and the presenters: a weekday at 10:00, far from midnight and the 4 am rollover. */
    private final TestClock clock = new TestClock(LocalDateTime.of(2026, 3, 10, 10, 0));
    private final ManualTasks tasks = new ManualTasks();
    private final FakeAi ai = new FakeAi();
    private final DataChanges changes = new DataChanges();
    private final List<Set<DataChange>> published = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();
    private AppServices services;
    private FailingReviewLogs reviewLogs;
    private ReviewSessionPresenter presenter;
    private int notifications;
    private long deckId;

    @BeforeEach
    void openDatabase() throws SQLException {
        services = AppServices.builder(tempDir.resolve("vocab.db"))
            .clock(clock)
            .reviewLogRepository(databaseManager -> reviewLogs = new FailingReviewLogs(databaseManager))
            .open();
        databases.track(services.databaseManager());
        changes.subscribe(published::add);
        presenter = new ReviewSessionPresenter(services.reviewService(), services.goalService(), () -> ai, tasks,
            changes, (title, error) -> failures.add(title), clock);
        presenter.addListener(() -> notifications++);
        deckId = services.startupDeck().getId();
    }

    @Test
    void beforeADeckIsShownNothingCanBeAnsweredOrRated() {
        assertEquals(State.IDLE, presenter.state());
        assertEquals("Loading...", presenter.question());
        assertFalse(presenter.canSubmit());
        assertFalse(presenter.canRate());
    }

    @Test
    void showingADeckStartsASessionOfTheDefaultSizeOnADueCard() throws SQLException {
        presenter.showDeck(deckId);

        assertEquals(State.AWAITING_ANSWER, presenter.state());
        WordCard card = presenter.card().orElseThrow();
        assertEquals(deckId, card.getDeckId());
        assertEquals(card.getEnglish(), presenter.question());
        assertEquals("English → Chinese | New | Lapses 0", presenter.details());
        assertEquals("Enter Chinese meaning", presenter.answerPrompt());
        assertEquals("Session 0/20 | Accuracy 0% | XP 0", presenter.sessionProgress());
        assertEquals("", presenter.result());
        assertTrue(presenter.canSubmit());
        assertFalse(presenter.canRate());
        assertTrue(notifications > 0);
        assertTrue(services.wordRepository().findById(card.getId()).isPresent());
    }

    @Test
    void submittingShowsTheCheckedAnswerAndThenTheExplanation() {
        presenter.showDeck(deckId);
        WordCard card = presenter.card().orElseThrow();
        presenter.setAnswer(firstMeaning(card));

        presenter.submit();

        assertEquals(State.ANSWERED, presenter.state());
        assertEquals("Correct answer: " + card.getChinese() + nl() + "Your answer: " + firstMeaning(card) + nl()
            + "Answer similarity: 100%" + nl() + nl() + "AI explanation: loading...", presenter.result());
        assertFalse(presenter.canSubmit());
        assertTrue(presenter.canRate());

        tasks.runAll();

        assertTrue(presenter.result().endsWith(nl() + nl() + "Explanation of " + card.getEnglish()), presenter.result());
        assertTrue(presenter.result().startsWith("Correct answer: " + card.getChinese()), presenter.result());
    }

    @Test
    void theCardsDetailsAreShownOnlyOnceTheAnswerIsChecked() throws SQLException {
        presenter.showDeck(deckId);
        WordCard card = presenter.card().orElseThrow();
        card.setPhonetic("/ˈtest/");
        card.setNote("English definition: a note that names the meaning, " + card.getChinese());
        services.wordRepository().update(card);
        presenter.resetSession();
        assertEquals(card.getId(), presenter.card().orElseThrow().getId());

        assertEquals("/ˈtest/ · " + card.getPartOfSpeech() + nl() + card.getExampleSentence(), presenter.hint());
        assertTrue(presenter.revealedDetails().isEmpty());

        presenter.setAnswer(firstMeaning(card));
        presenter.submit();

        WordDetails details = presenter.revealedDetails().orElseThrow();
        assertEquals("/ˈtest/", details.phonetic());
        assertEquals(card.getPartOfSpeech(), details.partOfSpeech());
        assertEquals(card.getExampleSentence(), details.exampleText());
        assertEquals(List.of(card.getEnglish().toLowerCase(Locale.ROOT)), details.example().stream()
            .filter(SentenceSpan::target).map(span -> span.text().toLowerCase(Locale.ROOT)).toList());
        assertEquals(card.getNote(), details.note());
        assertEquals(card.getTags(), details.tags());

        presenter.rate(ReviewRating.GOOD);

        assertTrue(presenter.revealedDetails().isEmpty(), "the next card's details wait for its answer");
        assertFalse(presenter.hint().contains(card.getExampleSentence()));
    }

    @Test
    void theHintBeforeAnAnswerNeverGivesItAway() {
        WordCard word = WordCard.createNew(deckId, "abate", "减弱; 减少", clock.now());
        word.setPhonetic("/əˈbeɪt/");
        word.setPartOfSpeech("verb");
        word.setExampleSentence("The storm began to abate.");
        word.setNote("减弱 (English: to lessen)");
        word.setTags("mine");

        assertEquals("/əˈbeɪt/ · verb" + nl() + "The storm began to abate.",
            ReviewSessionPresenter.hint(word, ReviewMode.EN_TO_ZH));
        assertEquals("verb", ReviewSessionPresenter.hint(word, ReviewMode.ZH_TO_EN),
            "the phonetic and the example would give the English word away");
        word.setExampleSentence("The storm began to abate. 暴风雨开始减弱。");
        assertEquals("/əˈbeɪt/ · verb", ReviewSessionPresenter.hint(word, ReviewMode.EN_TO_ZH),
            "an example with Chinese in it could give the meaning away");
        word.setExampleSentence("The storm began to abate.");
        word.setPhonetic(" ");
        word.setPartOfSpeech(null);
        assertEquals("The storm began to abate.", ReviewSessionPresenter.hint(word, ReviewMode.EN_TO_ZH));
        assertEquals("", ReviewSessionPresenter.hint(word, ReviewMode.ZH_TO_EN));
    }

    @Test
    void anExplanationThatFailsSaysWhy() {
        presenter.showDeck(deckId);
        presenter.setAnswer("完全错误");
        presenter.submit();
        ai.failure = new IllegalStateException("provider down");

        tasks.runAll();

        assertTrue(presenter.result().endsWith("AI explanation unavailable: provider down"), presenter.result());
    }

    @Test
    void ratingSavesTheReviewAndShowsTheNextCard() throws SQLException {
        presenter.showDeck(deckId);
        WordCard card = presenter.card().orElseThrow();
        long firstCardNumber = presenter.cardNumber();
        presenter.setAnswer(firstMeaning(card));
        presenter.submit();

        presenter.rate(ReviewRating.GOOD);

        List<ReviewLog> logs = services.reviewLogRepository().findByDeck(deckId);
        assertEquals(1, logs.size());
        assertEquals(card.getId(), logs.get(0).getWordId());
        assertEquals(ReviewRating.GOOD, logs.get(0).getRating());
        assertEquals(firstMeaning(card), logs.get(0).getUserAnswer());
        assertEquals(State.AWAITING_ANSWER, presenter.state());
        assertNotEquals(firstCardNumber, presenter.cardNumber());
        assertNotEquals(card.getId(), presenter.card().orElseThrow().getId());
        assertEquals("", presenter.answer());
        assertTrue(presenter.result().startsWith("Saved. XP +"), presenter.result());
        assertTrue(presenter.sessionProgress().startsWith("Session 1/20 | Accuracy 100% | XP "), presenter.sessionProgress());
        assertEquals(List.of(Set.of(DataChange.REVIEWS)), published);
        assertTrue(failures.isEmpty(), failures.toString());
    }

    @Test
    void ratingOrSubmittingAtTheWrongTimeIsIgnored() throws SQLException {
        presenter.showDeck(deckId);
        WordCard card = presenter.card().orElseThrow();

        presenter.rate(ReviewRating.EASY);
        assertEquals(State.AWAITING_ANSWER, presenter.state());

        presenter.setAnswer(firstMeaning(card));
        presenter.submit();
        String result = presenter.result();
        presenter.submit();
        assertEquals(result, presenter.result());

        presenter.rate(ReviewRating.GOOD);
        assertEquals(1, services.reviewLogRepository().findByDeck(deckId).size());
    }

    @Test
    void aRatingThatFailsToSaveKeepsTheAnswerAndCanBeRetried() throws SQLException {
        presenter.showDeck(deckId);
        WordCard card = presenter.card().orElseThrow();
        presenter.setAnswer(firstMeaning(card));
        presenter.submit();
        tasks.runAll();
        String result = presenter.result();

        reviewLogs.failNextInsert = true;
        presenter.rate(ReviewRating.GOOD);

        assertEquals(List.of("Rating not saved - choose a rating again to retry"), failures);
        assertEquals(State.RATING_FAILED, presenter.state());
        assertTrue(presenter.canRate());
        assertFalse(presenter.canSubmit());
        assertEquals(card.getId(), presenter.card().orElseThrow().getId());
        assertEquals(result, presenter.result());
        assertEquals(firstMeaning(card), presenter.answer());
        assertTrue(services.reviewLogRepository().findByDeck(deckId).isEmpty());
        assertTrue(published.isEmpty());

        presenter.rate(ReviewRating.GOOD);

        List<ReviewLog> logs = services.reviewLogRepository().findByDeck(deckId);
        assertEquals(1, logs.size());
        assertEquals(1.0, logs.get(0).getSimilarity());
        assertEquals(State.AWAITING_ANSWER, presenter.state());
        assertNotEquals(card.getId(), presenter.card().orElseThrow().getId());
    }

    @Test
    void whenTheSubmittedAnswerIsLostTheUserIsAskedToSubmitAgain() throws SQLException {
        presenter.showDeck(deckId);
        WordCard card = presenter.card().orElseThrow();
        presenter.setAnswer(firstMeaning(card));
        presenter.submit();
        // Something else restarted the service's session, which drops submitted answers.
        services.reviewService().startSession(deckId, ReviewMode.EN_TO_ZH, 10);

        presenter.rate(ReviewRating.GOOD);

        assertEquals(List.of("Rating not saved - submit your answer again"), failures);
        assertEquals(State.AWAITING_ANSWER, presenter.state());
        assertEquals("The rating was not saved. Submit your answer again to retry.", presenter.result());
        assertEquals(firstMeaning(card), presenter.answer());
        // The explanation of the lost answer must not replace that message.
        tasks.runAll();
        assertEquals("The rating was not saved. Submit your answer again to retry.", presenter.result());

        presenter.submit();
        presenter.rate(ReviewRating.GOOD);
        assertEquals(1, services.reviewLogRepository().findByDeck(deckId).size());
    }

    @Test
    void aLateExplanationOrErrorIsNotShownOnTheNextCard() {
        presenter.showDeck(deckId);
        presenter.setAnswer("完全错误");
        presenter.submit();
        presenter.rate(ReviewRating.AGAIN);
        presenter.setAnswer("完全错误");
        presenter.submit();
        ai.failure = new IllegalStateException("provider down");
        presenter.rate(ReviewRating.AGAIN);
        String saved = presenter.result();
        assertTrue(saved.startsWith("Saved. XP +"), saved);

        tasks.runAll();

        assertEquals(saved, presenter.result());
        assertEquals(State.AWAITING_ANSWER, presenter.state());
    }

    @Test
    void aLateExplanationDoesNotRevealTheAnswerWhenTheSameWordComesAgain() throws SQLException {
        Deck single = services.deckService().createDeck("Single");
        services.wordRepository().insert(WordCard.createNew(single.getId(), "lucid", "清晰的", clock.now()));
        presenter.showDeck(single.getId());
        assertEquals("lucid", presenter.question());
        long firstCardNumber = presenter.cardNumber();
        presenter.setAnswer("清晰的");
        presenter.submit();

        presenter.rate(ReviewRating.GOOD);

        // Its 10-minute learning step ends within the learn-ahead window and nothing else is due,
        // so the word is asked again right away.
        assertEquals("lucid", presenter.question());
        assertNotEquals(firstCardNumber, presenter.cardNumber());
        tasks.runAll();
        assertTrue(presenter.result().startsWith("Saved. XP +"), presenter.result());
        assertFalse(presenter.result().contains("清晰的"), presenter.result());
    }

    @Test
    void changedWordsKeepTheCardOnScreenWithItsAnswer() throws SQLException {
        presenter.showDeck(deckId);
        WordCard card = presenter.card().orElseThrow();
        long cardNumber = presenter.cardNumber();
        presenter.setAnswer("半个答案");

        services.wordRepository().insert(WordCard.createNew(deckId, "petrichor", "雨后泥土的气味", clock.now()));
        presenter.wordsChanged();

        assertEquals(cardNumber, presenter.cardNumber());
        assertEquals(card.getId(), presenter.card().orElseThrow().getId());
        assertEquals("半个答案", presenter.answer());

        presenter.submit();
        String result = presenter.result();
        presenter.wordsChanged();
        assertEquals(State.ANSWERED, presenter.state());
        assertEquals(result, presenter.result());
    }

    @Test
    void aDeletedCardIsReplacedByTheNextOne() throws SQLException {
        presenter.showDeck(deckId);
        WordCard card = presenter.card().orElseThrow();
        presenter.setAnswer("半个答案");
        presenter.submit();

        services.wordRepository().deleteById(card.getId());
        presenter.wordsChanged();

        assertEquals(State.AWAITING_ANSWER, presenter.state());
        assertNotEquals(card.getId(), presenter.card().orElseThrow().getId());
        assertEquals("", presenter.answer());
        assertEquals("", presenter.result());
    }

    @Test
    void newWordsEndAnEmptySession() throws SQLException {
        Deck deck = services.deckService().createDeck("Later");
        presenter.showDeck(deck.getId());
        assertEquals(State.COMPLETE, presenter.state());

        services.wordRepository().insert(WordCard.createNew(deck.getId(), "petrichor", "雨后泥土的气味", clock.now()));
        presenter.wordsChanged();

        assertEquals(State.AWAITING_ANSWER, presenter.state());
        assertEquals("petrichor", presenter.question());
    }

    @Test
    void reachingTheSessionTargetCompletesTheSession() {
        presenter.showDeck(deckId);
        presenter.startSession(ReviewSessionPresenter.CUSTOM, " 1 ");
        assertEquals("Session 0/1 | Accuracy 0% | XP 0", presenter.sessionProgress());
        presenter.setAnswer(firstMeaning(presenter.card().orElseThrow()));
        presenter.submit();

        presenter.rate(ReviewRating.EASY);

        assertEquals(State.COMPLETE, presenter.state());
        assertTrue(presenter.card().isEmpty());
        assertEquals("Review complete", presenter.question());
        assertEquals("Session target reached.", presenter.details());
        assertEquals("Session Complete", presenter.completionTitle());
        assertTrue(presenter.completionMetrics().startsWith("Completed: 1/1 | Accuracy: 100% | XP: "),
            presenter.completionMetrics());
        assertFalse(presenter.canSubmit());
        assertFalse(presenter.canRate());

        presenter.resetSession();
        assertEquals(State.AWAITING_ANSWER, presenter.state());
    }

    @Test
    void aDeckWithoutDueWordsIsCompleteRightAway() {
        Deck empty = services.deckService().createDeck("Empty");

        presenter.showDeck(empty.getId());

        assertEquals(State.COMPLETE, presenter.state());
        assertEquals("No due words right now.", presenter.details());
        assertTrue(presenter.completionMetrics().startsWith("Completed: 0/20 | Accuracy: 0% | XP: 0"),
            presenter.completionMetrics());
        assertTrue(presenter.result().startsWith("Use Weak Words mode"), presenter.result());
    }

    @Test
    void anInvalidCustomSessionSizeIsRejectedAndKeepsTheCard() {
        presenter.showDeck(deckId);
        long cardNumber = presenter.cardNumber();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> presenter.startSession(ReviewSessionPresenter.CUSTOM, "0"));

        assertEquals("Custom session size must be between 1 and 500.", error.getMessage());
        assertEquals(cardNumber, presenter.cardNumber());
        assertEquals(State.AWAITING_ANSWER, presenter.state());
    }

    @Test
    void aFailedWordIsAskedAgainInTheSameSession() throws SQLException {
        Deck pair = services.deckService().createDeck("Pair");
        services.wordRepository().insert(WordCard.createNew(pair.getId(), "lucid", "清晰的", clock.now()));
        services.wordRepository().insert(WordCard.createNew(pair.getId(), "abate", "减弱", clock.now()));
        presenter.showDeck(pair.getId());
        String failed = presenter.question();
        presenter.setAnswer("完全错误");
        presenter.submit();
        presenter.rate(ReviewRating.AGAIN);

        String other = presenter.question();
        assertNotEquals(failed, other);
        presenter.setAnswer(firstMeaning(presenter.card().orElseThrow()));
        presenter.submit();
        presenter.rate(ReviewRating.EASY);

        assertEquals(failed, presenter.question(), "the failed word comes back before the session ends");
        assertEquals(CardState.LEARNING, presenter.card().orElseThrow().getState());
        assertTrue(presenter.details().startsWith("English → Chinese | Learning | Recall "), presenter.details());
        assertTrue(presenter.sessionProgress().startsWith("Session 2/20 | Accuracy 50% | XP "), presenter.sessionProgress());
    }

    @Test
    void theRatingButtonsShowTheIntervalEachRatingGives() {
        presenter.showDeck(deckId);
        for (ReviewRating rating : ReviewRating.values()) {
            assertEquals("", presenter.ratingPreview(rating), "nothing before the answer is checked");
        }
        presenter.setAnswer(firstMeaning(presenter.card().orElseThrow()));
        presenter.submit();

        assertEquals("1m", presenter.ratingPreview(ReviewRating.AGAIN));
        assertEquals("6m", presenter.ratingPreview(ReviewRating.HARD));
        assertEquals("10m", presenter.ratingPreview(ReviewRating.GOOD));
        assertTrue(presenter.ratingPreview(ReviewRating.EASY).matches("1[3-9]d"), presenter.ratingPreview(ReviewRating.EASY));

        presenter.rate(ReviewRating.GOOD);
        assertEquals("", presenter.ratingPreview(ReviewRating.GOOD), "the next card has no answer yet");
    }

    @Test
    void aWrongAnswerShowsThatEveryRatingCountsAsAgain() {
        presenter.showDeck(deckId);
        presenter.setAnswer("完全错误");
        presenter.submit();

        for (ReviewRating rating : ReviewRating.values()) {
            assertEquals("1m", presenter.ratingPreview(rating), rating.name());
        }
        assertEquals("", presenter.ratingCountsAs(ReviewRating.AGAIN));
        assertEquals("Again (0%)", presenter.ratingCountsAs(ReviewRating.HARD));
        assertEquals("Again (0%)", presenter.ratingCountsAs(ReviewRating.GOOD));
        assertEquals("Again (0%)", presenter.ratingCountsAs(ReviewRating.EASY));
        assertEquals(Optional.of(ReviewRating.AGAIN), presenter.suggestedRating());
        assertTrue(presenter.canOverride());
        assertTrue(presenter.result().contains(nl() + "Does not match: counts as Again. If your answer was right,"
            + " choose \"I was right\" and rate it yourself."), presenter.result());
    }

    @Test
    void aMatchingAnswerCountsAsEveryRatingAndCannotBeOverridden() throws SQLException {
        presenter.showDeck(deckId);
        WordCard card = presenter.card().orElseThrow();
        presenter.setAnswer(firstMeaning(card));
        presenter.submit();
        int before = notifications;

        presenter.setOverridden(true);

        assertFalse(presenter.canOverride());
        assertFalse(presenter.isOverridden());
        assertEquals(before, notifications);
        for (ReviewRating rating : ReviewRating.values()) {
            assertEquals("", presenter.ratingCountsAs(rating), rating.name());
        }
        assertEquals(Optional.of(ReviewRating.GOOD), presenter.suggestedRating());
        presenter.rate(ReviewRating.GOOD);
        assertFalse(reviewLogs.findByWord(card.getId()).get(0).isOverridden());
        assertTrue(presenter.result().startsWith("Saved. XP +"), presenter.result());
    }

    @Test
    void iWasRightLetsTheChosenRatingCountAndIsLogged() throws SQLException {
        presenter.showDeck(deckId);
        WordCard card = presenter.card().orElseThrow();
        presenter.setAnswer("完全错误");
        presenter.submit();

        presenter.setOverridden(true);

        assertTrue(presenter.isOverridden());
        assertEquals(Optional.of(ReviewRating.GOOD), presenter.suggestedRating());
        for (ReviewRating rating : ReviewRating.values()) {
            assertEquals("", presenter.ratingCountsAs(rating), rating.name());
        }
        assertEquals("10m", presenter.ratingPreview(ReviewRating.GOOD));

        presenter.setOverridden(false);
        assertEquals("Again (0%)", presenter.ratingCountsAs(ReviewRating.GOOD));
        assertEquals("1m", presenter.ratingPreview(ReviewRating.GOOD));
        presenter.setOverridden(true);
        presenter.rate(ReviewRating.GOOD);

        ReviewLog log = reviewLogs.findByWord(card.getId()).get(0);
        assertEquals(ReviewRating.GOOD, log.getRating());
        assertEquals(ReviewRating.GOOD, log.getEffectiveRating());
        assertTrue(log.isOverridden());
        assertEquals(CardState.LEARNING, services.wordRepository().findById(card.getId()).orElseThrow().getState());
        assertTrue(presenter.result().startsWith("Saved as Good: you overrode the answer check. XP +"), presenter.result());
        assertFalse(presenter.isOverridden(), "the next card starts without an override");
        assertTrue(presenter.sessionProgress().startsWith("Session 1/20 | Accuracy 100% | XP "), presenter.sessionProgress());
    }

    @Test
    void aCappedRatingSaysWhatItWasSavedAs() throws SQLException {
        presenter.showDeck(deckId);
        WordCard card = presenter.card().orElseThrow();
        presenter.setAnswer("完全错误");
        presenter.submit();

        presenter.rate(ReviewRating.GOOD);

        assertEquals(ReviewRating.AGAIN, reviewLogs.findByWord(card.getId()).get(0).getEffectiveRating());
        assertTrue(presenter.result().startsWith("Saved as Again. XP +"), presenter.result());
        assertTrue(presenter.sessionProgress().startsWith("Session 1/20 | Accuracy 0% | XP "), presenter.sessionProgress());
    }

    @Test
    void theResponseTimeIsMeasuredFromShowingTheCardToSubmitting() throws SQLException {
        AppServices clocked = AppServices.builder(tempDir.resolve("clocked.db")).clock(clock).open();
        databases.track(clocked.databaseManager());
        ReviewSessionPresenter timed = new ReviewSessionPresenter(clocked.reviewService(), clocked.goalService(),
            () -> ai, tasks, changes, (title, error) -> failures.add(title), clock);
        timed.showDeck(clocked.startupDeck().getId());
        WordCard card = timed.card().orElseThrow();

        clock.advance(Duration.ofSeconds(12));
        timed.setAnswer(firstMeaning(card));
        timed.submit();
        // Reading the result and the explanation is not part of answering.
        clock.advance(Duration.ofSeconds(30));
        timed.rate(ReviewRating.GOOD);

        List<ReviewLog> logs = clocked.reviewLogRepository().findByWord(card.getId());
        assertEquals(1, logs.size());
        assertEquals(12_000, logs.get(0).getElapsedMillis());
    }

    @Test
    void theResponseTimeOnlyCountsTheTimeTheCardWasOnScreen() throws SQLException {
        AppServices clocked = AppServices.builder(tempDir.resolve("clocked.db")).clock(clock).open();
        databases.track(clocked.databaseManager());
        ReviewSessionPresenter timed = new ReviewSessionPresenter(clocked.reviewService(), clocked.goalService(),
            () -> ai, tasks, changes, (title, error) -> failures.add(title), clock);
        // The card is loaded while the user is on another tab, as at startup.
        timed.setOnScreen(false);
        timed.showDeck(clocked.startupDeck().getId());
        WordCard card = timed.card().orElseThrow();

        clock.advance(Duration.ofMinutes(10));
        timed.setOnScreen(true);
        clock.advance(Duration.ofSeconds(4));
        timed.setOnScreen(false);
        clock.advance(Duration.ofHours(1));
        timed.setOnScreen(true);
        clock.advance(Duration.ofSeconds(3));
        timed.setAnswer(firstMeaning(card));
        timed.submit();
        timed.rate(ReviewRating.GOOD);

        List<ReviewLog> logs = clocked.reviewLogRepository().findByWord(card.getId());
        assertEquals(1, logs.size());
        assertEquals(7_000, logs.get(0).getElapsedMillis());
    }

    @Test
    void theEighthLapseSaysTheWordIsNowALeech() throws SQLException {
        Deck deck = services.deckService().createDeck("Leech");
        WordCard card = WordCard.createNew(deck.getId(), "cavil", "挑剔", clock.now());
        card.setState(CardState.REVIEW);
        card.setStability(2);
        card.setDifficulty(9);
        card.setRepetitions(20);
        card.setLapses(WordCard.LEECH_LAPSES - 1);
        card.setLastReviewedAt(clock.now().minusDays(3));
        card.setNextReviewAt(clock.now().minusDays(1));
        services.wordRepository().insert(card);
        presenter.showDeck(deck.getId());

        presenter.setAnswer("完全错误");
        presenter.submit();
        presenter.rate(ReviewRating.AGAIN);

        assertTrue(presenter.result().contains("\"cavil\" lapsed 8 times and is now tagged as a leech"), presenter.result());
        assertTrue(services.wordRepository().findById(card.getId()).orElseThrow().isLeech());
        // Its relearning step comes right back, marked as a leech.
        assertEquals("cavil", presenter.question());
        assertTrue(presenter.details().endsWith(" | Lapses 8 | Leech"), presenter.details());
    }

    @Test
    void aChosenSessionSizeAppliesAtOnceAndOutlivesModeChangesResetsAndDeckSwitches() {
        Deck other = services.deckService().createDeck("Other");
        presenter.showDeck(deckId);
        assertEquals("20", presenter.sessionSizeChoice());
        assertEquals("Session 0/20 | Accuracy 0% | XP 0", presenter.sessionProgress());
        long cardNumber = presenter.cardNumber();

        presenter.selectSessionSize("50", "");
        assertEquals("50", presenter.sessionSizeChoice());
        assertEquals("Session 0/50 | Accuracy 0% | XP 0", presenter.sessionProgress());
        assertEquals(cardNumber, presenter.cardNumber(), "the card on screen stays");

        presenter.changeMode(ReviewMode.MIXED);
        assertEquals("50", presenter.sessionSizeChoice());
        assertEquals("Session 0/50 | Accuracy 0% | XP 0", presenter.sessionProgress());
        presenter.resetSession();
        assertEquals("Session 0/50 | Accuracy 0% | XP 0", presenter.sessionProgress());
        presenter.showDeck(other.getId());
        assertEquals("Session 0/50 | Accuracy 0% | XP 0", presenter.sessionProgress());
        presenter.showDeck(deckId);
        assertEquals(ReviewMode.MIXED, presenter.mode());

        presenter.selectSessionSize(ReviewSessionPresenter.ALL_DUE, "");
        assertEquals(ReviewSessionPresenter.ALL_DUE, presenter.sessionSizeChoice());
        assertEquals("Session 0/All Due | Accuracy 0% | XP 0", presenter.sessionProgress());
        presenter.selectSessionSize(ReviewSessionPresenter.CUSTOM, "7");
        assertEquals(ReviewSessionPresenter.CUSTOM, presenter.sessionSizeChoice());
        assertEquals("7", presenter.customSessionSize());
        assertEquals("Session 0/7 | Accuracy 0% | XP 0", presenter.sessionProgress());
        // While the user is still typing, nothing changes.
        presenter.selectSessionSize(ReviewSessionPresenter.CUSTOM, "");
        presenter.selectSessionSize(ReviewSessionPresenter.CUSTOM, "0");
        assertEquals("Session 0/7 | Accuracy 0% | XP 0", presenter.sessionProgress());
        // A custom 20 stays Custom.
        presenter.selectSessionSize(ReviewSessionPresenter.CUSTOM, "20");
        assertEquals(ReviewSessionPresenter.CUSTOM, presenter.sessionSizeChoice());
        assertEquals("Session 0/20 | Accuracy 0% | XP 0", presenter.sessionProgress());
        presenter.selectSessionSize("20", "20");
        assertEquals("20", presenter.sessionSizeChoice());
    }

    @Test
    void aLargerSessionSizeLetsACompletedSessionGoOn() {
        presenter.showDeck(deckId);
        presenter.startSession(ReviewSessionPresenter.CUSTOM, "1");
        presenter.setAnswer(firstMeaning(presenter.card().orElseThrow()));
        presenter.submit();
        presenter.rate(ReviewRating.EASY);
        assertEquals(State.COMPLETE, presenter.state());

        presenter.selectSessionSize("10", "");

        assertEquals(State.AWAITING_ANSWER, presenter.state());
        assertTrue(presenter.sessionProgress().startsWith("Session 1/10 | Accuracy 100% | XP "), presenter.sessionProgress());
    }

    @Test
    void editedGoalsSetTheSessionTargetAndLetACompletedSessionGoOn() {
        presenter.showDeck(deckId);
        presenter.startSession(ReviewSessionPresenter.CUSTOM, "1");
        presenter.setAnswer(firstMeaning(presenter.card().orElseThrow()));
        presenter.submit();
        presenter.rate(ReviewRating.EASY);
        assertEquals(State.COMPLETE, presenter.state());
        assertTrue(presenter.completionMetrics().contains("Today review goal: 1/20 | New words: 1/5"),
            presenter.completionMetrics());

        // As the Dashboard's "Edit goals" saves them.
        services.goalService().settings().saveSessionGoal(50);
        services.goalService().settings().saveDefaults(new GoalTargets(40, 5));
        presenter.goalsChanged();

        assertEquals(State.AWAITING_ANSWER, presenter.state());
        assertEquals("50", presenter.sessionSizeChoice());
        assertTrue(presenter.sessionProgress().startsWith("Session 1/50 | Accuracy 100% | XP "), presenter.sessionProgress());
        presenter.resetSession();
        assertEquals("Session 0/50 | Accuracy 0% | XP 0", presenter.sessionProgress(), "a new session keeps the goal");
        presenter.startSession(ReviewSessionPresenter.CUSTOM, "1");
        presenter.setAnswer(firstMeaning(presenter.card().orElseThrow()));
        presenter.submit();
        presenter.rate(ReviewRating.EASY);
        assertTrue(presenter.completionMetrics().contains("Today review goal: 2/40"), presenter.completionMetrics());
    }

    @Test
    void theNextLaunchStartsWithTheLastSessionSizeAndMode() throws SQLException {
        presenter.showDeck(deckId);
        presenter.selectSessionSize("50", "");
        presenter.changeMode(ReviewMode.ZH_TO_EN);

        AppServices restarted = AppServices.builder(tempDir.resolve("vocab.db")).clock(clock).open();
        databases.track(restarted.databaseManager());
        ReviewSessionPresenter next = new ReviewSessionPresenter(restarted.reviewService(), restarted.goalService(),
            () -> ai, tasks, changes, (title, error) -> failures.add(title), clock);
        assertEquals(ReviewMode.ZH_TO_EN, next.mode());
        next.showDeck(deckId);

        assertEquals("50", next.sessionSizeChoice());
        assertEquals("Session 0/50 | Accuracy 0% | XP 0", next.sessionProgress());
        assertEquals(next.card().orElseThrow().getChinese(), next.question());
    }

    @Test
    void practicingAWeakWordThatIsNotDueSaysItsScheduleDidNotChange() throws SQLException {
        Deck deck = services.deckService().createDeck("Weak");
        WordCard abate = services.wordRepository().insert(weakWord(deck, "abate", "减弱"));
        services.wordRepository().insert(weakWord(deck, "laud", "赞扬"));
        presenter.changeMode(ReviewMode.WEAK_WORDS);
        presenter.showDeck(deck.getId());
        WordCard first = presenter.card().orElseThrow();
        presenter.setAnswer(first.getChinese());
        presenter.submit();
        assertEquals("3d", presenter.ratingPreview(ReviewRating.GOOD), "when it is due anyway");

        presenter.rate(ReviewRating.GOOD);

        assertTrue(presenter.result().startsWith("Practice saved: the word was not due, so its schedule did not change. XP +"), presenter.result());
        WordCard second = presenter.card().orElseThrow();
        assertNotEquals(first.getId(), second.getId());
        presenter.setAnswer(second.getChinese());
        presenter.submit();
        presenter.rate(ReviewRating.GOOD);
        assertEquals(State.COMPLETE, presenter.state());
        assertEquals("Every weak word was shown in this session.", presenter.details());
        assertEquals(abate.getNextReviewAt(), services.wordRepository().findById(abate.getId()).orElseThrow().getNextReviewAt());
    }

    @Test
    void aMissedPracticeSaysTheWordIsDueAgainFromTheNextStudyDay() throws SQLException {
        Deck deck = services.deckService().createDeck("Weak");
        services.wordRepository().insert(weakWord(deck, "abate", "减弱"));
        services.wordRepository().insert(weakWord(deck, "laud", "赞扬"));
        presenter.changeMode(ReviewMode.WEAK_WORDS);
        presenter.showDeck(deck.getId());
        WordCard first = presenter.card().orElseThrow();
        presenter.setAnswer("完全错误");
        presenter.submit();
        assertEquals("1d", presenter.ratingPreview(ReviewRating.GOOD), "a miss counts as Again");

        presenter.rate(ReviewRating.GOOD);

        assertTrue(presenter.result().startsWith("Practice saved: the word was not due, but after this miss it is due again from the next study day. XP +"), presenter.result());
        WordCard practiced = services.wordRepository().findById(first.getId()).orElseThrow();
        assertTrue(practiced.getNextReviewAt().isBefore(first.getNextReviewAt()));
    }

    @Test
    void theNewWordLimitEndsTheSessionAndRaisingItLetsTheSessionGoOn() throws SQLException {
        Deck deck = services.deckService().createDeck("New words");
        for (String english : List.of("lucid", "abate", "laud", "cavil")) {
            services.wordRepository().insert(WordCard.createNew(deck.getId(), english, "释义" + english, clock.now()));
        }
        presenter.showDeck(deck.getId());
        presenter.setNewCardsPerDay(2);
        assertEquals(List.of(Set.of(DataChange.REVIEW_SETTINGS)), published);
        for (int i = 0; i < 2; i++) {
            presenter.setAnswer(presenter.card().orElseThrow().getChinese());
            presenter.submit();
            presenter.rate(ReviewRating.EASY);
        }
        assertEquals(State.COMPLETE, presenter.state());
        assertEquals("No due words right now; today's limit of 2 new words is reached.", presenter.details());

        presenter.setNewCardsPerDay(3);

        assertEquals(State.AWAITING_ANSWER, presenter.state());
        assertEquals(3, presenter.newCardsPerDay());
        assertEquals(3, services.reviewService().newCardsPerDay(deck.getId()));
        presenter.showDeck(deckId);
        assertEquals(20, presenter.newCardsPerDay(), "each deck has its own limit");
    }

    private WordCard weakWord(Deck deck, String english, String chinese) {
        WordCard card = WordCard.createNew(deck.getId(), english, chinese, clock.now());
        card.setState(CardState.REVIEW);
        card.setStability(3);
        card.setDifficulty(6);
        card.setRepetitions(4);
        card.setConsecutiveCorrect(1);
        card.setLapses(1);
        card.setLastReviewedAt(clock.now().minusDays(1));
        card.setNextReviewAt(clock.now().plusDays(3));
        return card;
    }

    @Test
    void sessionSizeChoicesAreParsed() {
        assertEquals(10, ReviewSessionPresenter.parseSessionSize("10", ""));
        assertEquals(50, ReviewSessionPresenter.parseSessionSize("50", "7"));
        assertEquals(0, ReviewSessionPresenter.parseSessionSize(ReviewSessionPresenter.ALL_DUE, ""));
        assertEquals(7, ReviewSessionPresenter.parseSessionSize(ReviewSessionPresenter.CUSTOM, " 7 "));
        assertEquals(500, ReviewSessionPresenter.parseSessionSize(ReviewSessionPresenter.CUSTOM, "500"));
        assertEquals(20, ReviewSessionPresenter.parseSessionSize(null, null));
        assertThrows(IllegalArgumentException.class, () -> ReviewSessionPresenter.parseSessionSize(ReviewSessionPresenter.CUSTOM, "501"));
        assertThrows(IllegalArgumentException.class, () -> ReviewSessionPresenter.parseSessionSize(ReviewSessionPresenter.CUSTOM, "-1"));
        assertThrows(IllegalArgumentException.class, () -> ReviewSessionPresenter.parseSessionSize(ReviewSessionPresenter.CUSTOM, "abc"));
        assertEquals("All Due", ReviewSessionPresenter.sessionSizeLabel(ReviewSessionPresenter.ALL_DUE));
        assertEquals("Custom", ReviewSessionPresenter.sessionSizeLabel(ReviewSessionPresenter.CUSTOM));
        assertEquals("20", ReviewSessionPresenter.sessionSizeLabel("20"));
    }

    @Test
    void zhToEnModeAsksForTheEnglishWord() {
        presenter.showDeck(deckId);

        presenter.changeMode(ReviewMode.ZH_TO_EN);

        WordCard card = presenter.card().orElseThrow();
        assertEquals(card.getChinese(), presenter.question());
        assertEquals("Enter English word", presenter.answerPrompt());
        presenter.setAnswer(card.getEnglish());
        presenter.submit();
        assertTrue(presenter.result().startsWith("Correct answer: " + card.getEnglish()), presenter.result());
        assertTrue(presenter.result().contains("Answer similarity: 100%"), presenter.result());
    }

    @Test
    void clozeModeAsksTheBlankedExampleWithTheMeaningAsAHintAndCountsSkippedCards() throws SQLException {
        List<WordCard> firstNew = services.wordRepository().findNewCards(deckId, clock.now().plusDays(1), 3);
        for (WordCard withoutExample : firstNew.subList(0, 2)) {
            withoutExample.setExampleSentence(withoutExample == firstNew.get(0) ? "" : "No such word here.");
            services.wordRepository().update(withoutExample);
        }
        WordCard card = firstNew.get(2);
        card.setExampleSentence("Yesterday the " + card.getEnglish() + "s were everywhere.");
        services.wordRepository().update(card);
        presenter.showDeck(deckId);

        presenter.changeMode(ReviewMode.CLOZE);

        assertEquals(card.getId(), presenter.card().orElseThrow().getId());
        assertEquals("Yesterday the _____ were everywhere.", presenter.question());
        assertTrue(presenter.isSentenceQuestion());
        assertEquals("Hint: " + card.getChinese() + " · " + card.getPartOfSpeech(), presenter.hint());
        assertEquals("Type the missing word", presenter.answerPrompt());
        assertEquals("Cloze | New | Lapses 0", presenter.details());
        assertEquals("Session 0/20 | Accuracy 0% | XP 0 | 2 cards without examples skipped", presenter.sessionProgress());

        presenter.setAnswer(card.getEnglish() + "s");
        presenter.submit();

        assertTrue(presenter.result().startsWith("Correct answer: " + card.getEnglish() + " (in the sentence: "
            + card.getEnglish() + "s)" + nl() + "Your answer: " + card.getEnglish() + "s" + nl()
            + "Answer similarity: 100%"), presenter.result());
        assertEquals(List.of(card.getEnglish() + "s"), presenter.revealedDetails().orElseThrow().example().stream()
            .filter(SentenceSpan::target).map(SentenceSpan::text).toList());
        presenter.rate(ReviewRating.GOOD);
        ReviewLog log = reviewLogs.findByWord(card.getId()).get(0);
        assertEquals(ReviewMode.CLOZE, log.getDirection());
        assertEquals(ReviewRating.GOOD, log.getEffectiveRating());
    }

    @Test
    void aClozeSessionWithoutUsableExamplesSaysWhyItIsComplete() throws SQLException {
        long empty = services.deckService().createDeck("No examples").getId();
        services.wordRepository().insert(WordCard.createNew(empty, "petrichor", "雨后泥土的气味", clock.now()));
        services.wordRepository().insert(WordCard.createNew(empty, "sonder", "旁人皆有故事之感", clock.now()));
        presenter.showDeck(empty);

        presenter.changeMode(ReviewMode.CLOZE);

        assertEquals(State.COMPLETE, presenter.state());
        assertFalse(presenter.isSentenceQuestion());
        assertEquals("", presenter.hint());
        assertEquals("No due words with a usable example right now; 2 cards without examples skipped.",
            presenter.details());
        assertTrue(presenter.completionMetrics().endsWith(nl() + "2 cards without examples skipped: an example"
            + " sentence that contains the word lets Cloze mode ask them."), presenter.completionMetrics());
        assertEquals("1 card without an example skipped", ReviewSessionPresenter.skippedWithoutExamples(1));
    }

    @Test
    void anEasyRecognitionInMixedModeShowsAndCountsAsGood() throws SQLException {
        ReviewSessionPresenter mixed = presenterAsking(ReviewMode.EN_TO_ZH);
        mixed.showDeck(deckId);
        mixed.changeMode(ReviewMode.MIXED);
        WordCard card = mixed.card().orElseThrow();
        assertEquals(card.getEnglish(), mixed.question());
        mixed.setAnswer(firstMeaning(card));
        mixed.submit();

        assertEquals("10m", mixed.ratingPreview(ReviewRating.GOOD));
        assertEquals("10m", mixed.ratingPreview(ReviewRating.EASY), "Easy shows Good's interval");
        assertEquals("Good", mixed.ratingCountsAs(ReviewRating.EASY));
        assertFalse(mixed.canOverride(), "the answer matched; only Mixed mode's rule applies");
        mixed.rate(ReviewRating.EASY);

        WordCard rated = services.wordRepository().findById(card.getId()).orElseThrow();
        assertEquals(CardState.LEARNING, rated.getState(), "counted as Good, which goes on to the next step");
        ReviewLog log = reviewLogs.findByWord(card.getId()).get(0);
        assertEquals(ReviewRating.EASY, log.getRating());
        assertEquals(ReviewMode.EN_TO_ZH, log.getDirection());
    }

    @Test
    void anEasyProductionInMixedModeCountsAsEasy() throws SQLException {
        ReviewSessionPresenter mixed = presenterAsking(ReviewMode.ZH_TO_EN);
        mixed.showDeck(deckId);
        mixed.changeMode(ReviewMode.MIXED);
        WordCard card = mixed.card().orElseThrow();
        assertEquals(card.getChinese(), mixed.question());
        mixed.setAnswer(card.getEnglish());
        mixed.submit();

        assertTrue(mixed.ratingPreview(ReviewRating.EASY).matches("1[3-9]d"), mixed.ratingPreview(ReviewRating.EASY));
        mixed.rate(ReviewRating.EASY);

        assertEquals(CardState.REVIEW, services.wordRepository().findById(card.getId()).orElseThrow().getState());
        assertEquals(ReviewMode.ZH_TO_EN, reviewLogs.findByWord(card.getId()).get(0).getDirection());
    }

    /** A presenter whose Mixed mode always asks in {@code direction}. */
    private ReviewSessionPresenter presenterAsking(ReviewMode direction) {
        Random random = new Random() {
            @Override
            public boolean nextBoolean() {
                return direction == ReviewMode.EN_TO_ZH;
            }
        };
        ReviewService reviews = new ReviewService(services.wordRepository(), services.reviewLogRepository(),
            new SimilarityService(), services.reviewScheduler(), services.goalService(), services.achievementService(),
            services.clock(), null, random);
        return new ReviewSessionPresenter(reviews, services.goalService(), () -> ai, tasks, changes,
            (title, error) -> failures.add(title), clock);
    }

    @Test
    void thePresenterAndWhatItUsesFromTheUiPackageDoNotNeedJavaFx() throws Exception {
        for (Class<?> type : List.of(ReviewSessionPresenter.class, ReviewSessionPresenter.State.class,
            ReviewSessionPresenter.FailureReporter.class, DataChanges.class, DataChange.class, TaskRunner.class,
            com.vocabtrainer.ui.Formats.class, com.vocabtrainer.ui.LatestRequest.class, WordDetails.class)) {
            String fileName = type.getName().substring(type.getName().lastIndexOf('.') + 1) + ".class";
            try (InputStream classFile = type.getResourceAsStream(fileName)) {
                String constants = new String(classFile.readAllBytes(), StandardCharsets.ISO_8859_1);
                assertFalse(constants.contains("javafx/"), type.getName() + " refers to JavaFX");
            }
        }
    }

    private static String firstMeaning(WordCard word) {
        return word.getChinese().split("[;；,，/、]")[0].trim();
    }

    private static String nl() {
        return System.lineSeparator();
    }

    /** Runs background work only when the test calls {@link #runAll()}, like a slow AI provider. */
    static final class ManualTasks implements TaskRunner {
        private final List<Runnable> pending = new ArrayList<>();

        @Override
        public <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
            pending.add(() -> {
                T value;
                try {
                    value = work.call();
                } catch (Exception e) {
                    onFailure.accept(e);
                    return;
                }
                onSuccess.accept(value);
            });
        }

        void runAll() {
            List<Runnable> due = List.copyOf(pending);
            pending.clear();
            due.forEach(Runnable::run);
        }
    }

    static final class FakeAi implements AiService {
        RuntimeException failure;

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String explain(WordCard word) {
            if (failure != null) {
                throw failure;
            }
            return "Explanation of " + word.getEnglish();
        }
    }

    /** Fails the next review-log insert, as a locked database would. */
    static final class FailingReviewLogs extends ReviewLogRepository {
        volatile boolean failNextInsert;

        FailingReviewLogs(DatabaseManager databaseManager) {
            super(databaseManager);
        }

        @Override
        public ReviewLog insert(ReviewLog log) throws SQLException {
            if (failNextInsert) {
                failNextInsert = false;
                throw new SQLException("[SQLITE_BUSY] simulated lock while inserting review log");
            }
            return super.insert(log);
        }
    }
}
