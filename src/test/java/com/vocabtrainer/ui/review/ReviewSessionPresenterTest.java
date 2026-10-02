package com.vocabtrainer.ui.review;

import com.vocabtrainer.TestClock;
import com.vocabtrainer.app.AppServices;
import com.vocabtrainer.domain.CardState;
import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.ReviewLog;
import com.vocabtrainer.domain.ReviewMode;
import com.vocabtrainer.domain.ReviewRating;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.ReviewLogRepository;
import com.vocabtrainer.repository.TestDatabases;
import com.vocabtrainer.service.AiService;
import com.vocabtrainer.ui.DataChange;
import com.vocabtrainer.ui.DataChanges;
import com.vocabtrainer.ui.TaskRunner;
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
            .reviewLogRepository(databaseManager -> reviewLogs = new FailingReviewLogs(databaseManager))
            .open();
        databases.track(services.databaseManager());
        changes.subscribe(published::add);
        presenter = new ReviewSessionPresenter(services.reviewService(), services.goalService(), () -> ai, tasks,
            changes, (title, error) -> failures.add(title));
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
        assertEquals("英译中 | New | Lapses 0", presenter.details());
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
        services.wordRepository().insert(WordCard.createNew(single.getId(), "lucid", "清晰的"));
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

        services.wordRepository().insert(WordCard.createNew(deckId, "petrichor", "雨后泥土的气味"));
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

        services.wordRepository().insert(WordCard.createNew(deck.getId(), "petrichor", "雨后泥土的气味"));
        presenter.wordsChanged();

        assertEquals(State.AWAITING_ANSWER, presenter.state());
        assertEquals("petrichor", presenter.question());
    }

    @Test
    void reachingTheSessionTargetCompletesTheSession() {
        presenter.showDeck(deckId);
        presenter.startSession("Custom", " 1 ");
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
            () -> presenter.startSession("Custom", "0"));

        assertEquals("Custom session size must be between 1 and 500.", error.getMessage());
        assertEquals(cardNumber, presenter.cardNumber());
        assertEquals(State.AWAITING_ANSWER, presenter.state());
    }

    @Test
    void aFailedWordIsAskedAgainInTheSameSession() throws SQLException {
        Deck pair = services.deckService().createDeck("Pair");
        services.wordRepository().insert(WordCard.createNew(pair.getId(), "lucid", "清晰的"));
        services.wordRepository().insert(WordCard.createNew(pair.getId(), "abate", "减弱"));
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
        assertTrue(presenter.details().startsWith("英译中 | Learning | Recall "), presenter.details());
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
    }

    @Test
    void theResponseTimeIsMeasuredFromShowingTheCardToSubmitting() throws SQLException {
        TestClock clock = new TestClock(LocalDateTime.now().plusSeconds(1));
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
        TestClock clock = new TestClock(LocalDateTime.now().plusSeconds(1));
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
        WordCard card = WordCard.createNew(deck.getId(), "cavil", "挑剔");
        card.setState(CardState.REVIEW);
        card.setStability(2);
        card.setDifficulty(9);
        card.setRepetitions(20);
        card.setLapses(WordCard.LEECH_LAPSES - 1);
        card.setLastReviewedAt(LocalDateTime.now().minusDays(3));
        card.setNextReviewAt(LocalDateTime.now().minusDays(1));
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
    void sessionSizeChoicesAreParsed() {
        assertEquals(10, ReviewSessionPresenter.parseSessionSize("10", ""));
        assertEquals(50, ReviewSessionPresenter.parseSessionSize("50", "7"));
        assertEquals(0, ReviewSessionPresenter.parseSessionSize("All Due", ""));
        assertEquals(7, ReviewSessionPresenter.parseSessionSize("Custom", " 7 "));
        assertEquals(500, ReviewSessionPresenter.parseSessionSize("Custom", "500"));
        assertEquals(20, ReviewSessionPresenter.parseSessionSize(null, null));
        assertThrows(IllegalArgumentException.class, () -> ReviewSessionPresenter.parseSessionSize("Custom", "501"));
        assertThrows(IllegalArgumentException.class, () -> ReviewSessionPresenter.parseSessionSize("Custom", "-1"));
        assertThrows(IllegalArgumentException.class, () -> ReviewSessionPresenter.parseSessionSize("Custom", "abc"));
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
    void thePresenterAndWhatItUsesFromTheUiPackageDoNotNeedJavaFx() throws Exception {
        for (Class<?> type : List.of(ReviewSessionPresenter.class, ReviewSessionPresenter.State.class,
            ReviewSessionPresenter.FailureReporter.class, DataChanges.class, DataChange.class, TaskRunner.class,
            com.vocabtrainer.ui.Formats.class, com.vocabtrainer.ui.LatestRequest.class)) {
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
