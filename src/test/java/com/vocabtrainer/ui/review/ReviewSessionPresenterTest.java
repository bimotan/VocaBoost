package com.vocabtrainer.ui.review;

import com.vocabtrainer.app.AppServices;
import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.ReviewLog;
import com.vocabtrainer.domain.ReviewMode;
import com.vocabtrainer.domain.ReviewRating;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.ReviewLogRepository;
import com.vocabtrainer.service.AiService;
import com.vocabtrainer.ui.DataChange;
import com.vocabtrainer.ui.DataChanges;
import com.vocabtrainer.ui.TaskRunner;
import com.vocabtrainer.ui.review.ReviewSessionPresenter.State;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
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
    void showingADeckStartsADefaultSessionOnADueCard() throws SQLException {
        presenter.showDeck(deckId);

        assertEquals(State.AWAITING_ANSWER, presenter.state());
        WordCard card = presenter.card().orElseThrow();
        assertEquals(deckId, card.getDeckId());
        assertEquals(card.getEnglish(), presenter.question());
        assertTrue(presenter.details().startsWith("英译中 | Streak 0 | Interval 0 days | EF "), presenter.details());
        assertEquals("Enter Chinese meaning", presenter.answerPrompt());
        assertEquals("Session 0/10 | Accuracy 0% | XP 0", presenter.sessionProgress());
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
        assertTrue(presenter.sessionProgress().startsWith("Session 1/10 | Accuracy 100% | XP "), presenter.sessionProgress());
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
        presenter.changeMode(ReviewMode.WEAK_WORDS);
        assertEquals("lucid", presenter.question());
        long firstCardNumber = presenter.cardNumber();
        presenter.setAnswer("清晰的");
        presenter.submit();

        presenter.rate(ReviewRating.GOOD);

        // Still a weak word, so weak-words mode asks it again right away.
        assertEquals("lucid", presenter.question());
        assertNotEquals(firstCardNumber, presenter.cardNumber());
        tasks.runAll();
        assertTrue(presenter.result().startsWith("Saved. XP +"), presenter.result());
        assertFalse(presenter.result().contains("清晰的"), presenter.result());
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
        assertTrue(presenter.completionMetrics().startsWith("Completed: 0/10 | Accuracy: 0% | XP: 0"),
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
