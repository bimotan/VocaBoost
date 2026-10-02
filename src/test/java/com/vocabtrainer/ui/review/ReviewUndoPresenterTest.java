package com.vocabtrainer.ui.review;

import com.vocabtrainer.app.AppServices;
import com.vocabtrainer.domain.CardState;
import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.ReviewLog;
import com.vocabtrainer.domain.ReviewRating;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.TestDatabases;
import com.vocabtrainer.service.AiService;
import com.vocabtrainer.service.ExplanationRequest;
import com.vocabtrainer.ui.DataChange;
import com.vocabtrainer.ui.DataChanges;
import com.vocabtrainer.ui.review.ReviewSessionPresenter.State;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Undo, Already known, Suspend and the leech notice on the Review tab, without JavaFX (review findings G3, A12). */
class ReviewUndoPresenterTest {
    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    private final ReviewSessionPresenterTest.ManualTasks tasks = new ReviewSessionPresenterTest.ManualTasks();
    private final RecordingAi ai = new RecordingAi();
    private final DataChanges changes = new DataChanges();
    private final List<Set<DataChange>> published = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();
    private AppServices services;
    private ReviewSessionPresenter presenter;
    private long deckId;

    @BeforeEach
    void openDatabase() throws SQLException {
        services = AppServices.builder(tempDir.resolve("vocab.db")).open();
        databases.track(services.databaseManager());
        changes.subscribe(published::add);
        presenter = new ReviewSessionPresenter(services.reviewService(), services.goalService(), () -> ai, tasks,
            changes, (title, error) -> failures.add(title + ": " + error.getMessage()));
        deckId = services.startupDeck().getId();
    }

    @Test
    void undoBringsTheRatedCardBackWithItsCheckedAnswer() throws SQLException {
        presenter.showDeck(deckId);
        assertFalse(presenter.canUndo());
        WordCard rated = presenter.card().orElseThrow();
        String typed = firstMeaning(rated);
        answer(typed);
        presenter.rate(ReviewRating.AGAIN);
        assertNotEquals(rated.getId(), presenter.card().orElseThrow().getId());
        assertTrue(presenter.canUndo());
        assertEquals("Undo the Again rating of \"" + rated.getEnglish() + "\"", presenter.undoDescription());
        published.clear();

        presenter.undo();

        assertEquals(State.ANSWERED, presenter.state());
        assertEquals(rated.getId(), presenter.card().orElseThrow().getId());
        assertEquals(rated.getEnglish(), presenter.question());
        assertEquals(typed, presenter.answer());
        assertTrue(presenter.canRate());
        assertEquals(ReviewRating.GOOD, presenter.suggestedRating().orElseThrow());
        assertTrue(presenter.result().startsWith("Undid the Again rating of \"" + rated.getEnglish() + "\": XP -"),
            presenter.result());
        assertTrue(presenter.result().contains("Correct answer: " + rated.getChinese()), presenter.result());
        assertEquals("Session 0/20 | Accuracy 0% | XP 0", presenter.sessionProgress());
        assertEquals("英译中 | New | Lapses 0", presenter.details());
        assertFalse(presenter.canUndo());
        assertTrue(services.reviewLogRepository().findByWord(rated.getId()).isEmpty());
        assertEquals(CardState.NEW, stored(rated).getState());
        assertTrue(published.contains(Set.of(DataChange.WORDS, DataChange.REVIEWS)), published.toString());

        presenter.rate(ReviewRating.GOOD);

        List<ReviewLog> logs = services.reviewLogRepository().findByWord(rated.getId());
        assertEquals(1, logs.size());
        assertEquals(ReviewRating.GOOD, logs.get(0).getRating());
        assertEquals(typed, logs.get(0).getUserAnswer());
        assertTrue(failures.isEmpty(), failures.toString());
    }

    @Test
    void severalRatingsAreUndoneOneAfterTheOther() {
        presenter.showDeck(deckId);
        List<Long> rated = new ArrayList<>();
        for (int count = 0; count < 3; count++) {
            WordCard card = presenter.card().orElseThrow();
            rated.add(card.getId());
            answer(firstMeaning(card));
            presenter.rate(ReviewRating.GOOD);
        }
        assertEquals("Session 3/20 | Accuracy 100% | XP " + services.reviewService().sessionSummary().xpEarned(),
            presenter.sessionProgress());

        for (int index = 2; index >= 0; index--) {
            presenter.undo();
            assertEquals(rated.get(index), presenter.card().orElseThrow().getId());
            assertEquals(State.ANSWERED, presenter.state());
        }

        assertFalse(presenter.canUndo());
        assertEquals("", presenter.undoDescription());
        assertEquals("Session 0/20 | Accuracy 0% | XP 0", presenter.sessionProgress());
    }

    @Test
    void aDeckSwitchOrResetLeavesNothingToUndo() {
        Deck other = services.deckService().createDeck("Other");
        presenter.showDeck(deckId);
        rateShownCard(ReviewRating.GOOD);
        assertTrue(presenter.canUndo());

        presenter.showDeck(other.getId());
        assertFalse(presenter.canUndo());

        presenter.showDeck(deckId);
        rateShownCard(ReviewRating.GOOD);
        presenter.resetSession();
        assertFalse(presenter.canUndo());
    }

    @Test
    void aNewCardMarkedAsKnownComesBackNewOnUndo() throws SQLException {
        presenter.showDeck(deckId);
        WordCard known = presenter.card().orElseThrow();
        assertTrue(presenter.canMarkKnown());

        presenter.markKnown();

        assertNotEquals(known.getId(), presenter.card().orElseThrow().getId());
        assertEquals(CardState.REVIEW, stored(known).getState());
        assertTrue(presenter.result().startsWith("Marked \"" + known.getEnglish() + "\" as already known: its next review"
            + " is in "), presenter.result());
        assertEquals("Session 0/20 | Accuracy 0% | XP 0", presenter.sessionProgress());
        assertEquals("Undo \"Already known\" for \"" + known.getEnglish() + "\"", presenter.undoDescription());

        presenter.undo();

        assertEquals(State.AWAITING_ANSWER, presenter.state());
        assertEquals(known.getId(), presenter.card().orElseThrow().getId());
        assertEquals("", presenter.answer());
        assertEquals("Undid \"Already known\" for \"" + known.getEnglish() + "\": it is a new word again.",
            presenter.result());
        assertEquals(CardState.NEW, stored(known).getState());
        assertTrue(presenter.canMarkKnown());
    }

    @Test
    void onlyANewCardCanBeMarkedAsKnown() throws SQLException {
        Deck deck = services.deckService().createDeck("Reviewed");
        services.wordRepository().insert(reviewCard(deck, "lucid", "清晰的", 0));
        presenter.showDeck(deck.getId());

        assertEquals("lucid", presenter.question());
        assertFalse(presenter.canMarkKnown());
        assertTrue(presenter.canSuspend());
    }

    @Test
    void theCardOnScreenCanBeSuspendedAndUndoBringsItBack() throws SQLException {
        presenter.showDeck(deckId);
        WordCard suspended = presenter.card().orElseThrow();
        answer("完全错误");
        assertTrue(presenter.canSuspend());

        presenter.suspendCard();

        assertNotEquals(suspended.getId(), presenter.card().orElseThrow().getId());
        assertTrue(stored(suspended).isSuspended());
        assertEquals("Suspended \"" + suspended.getEnglish() + "\": it is not reviewed until you unsuspend it in the"
            + " Word List. Undo (Ctrl+Z) takes it back.", presenter.result());
        assertTrue(published.contains(Set.of(DataChange.WORDS, DataChange.REVIEWS)), published.toString());

        presenter.undo();

        assertFalse(stored(suspended).isSuspended());
        assertEquals(suspended.getId(), presenter.card().orElseThrow().getId());
        // It was suspended with its checked answer, which it gets back.
        assertEquals(State.ANSWERED, presenter.state());
        assertEquals("完全错误", presenter.answer());
        assertTrue(presenter.result().startsWith("Undid suspending \"" + suspended.getEnglish() + "\"."),
            presenter.result());
    }

    @Test
    void theLeechNoticeSuspendsTheLeechOnScreen() throws SQLException {
        Deck deck = services.deckService().createDeck("Leech");
        WordCard leech = services.wordRepository().insert(reviewCard(deck, "cavil", "挑剔", WordCard.LEECH_LAPSES - 1));
        presenter.showDeck(deck.getId());
        answer("完全错误");
        presenter.rate(ReviewRating.AGAIN);
        assertEquals("Leech: \"cavil\" lapsed 8 times. Suspend it for now, or find a way to remember it.",
            presenter.leechNotice());
        // Its relearning step comes right back.
        assertEquals("cavil", presenter.question());
        assertTrue(presenter.canSuspendLeech());

        presenter.suspendLeech();

        assertTrue(stored(leech).isSuspended());
        assertTrue(presenter.isComplete());
        assertTrue(presenter.leechNotice().contains("Suspended"), presenter.leechNotice());
        assertFalse(presenter.canSuspendLeech());

        presenter.undo();

        assertFalse(stored(leech).isSuspended());
        assertEquals("cavil", presenter.question());
        assertTrue(presenter.leech().isEmpty(), "the notice belonged to the rating before");
    }

    @Test
    void aLeechNotOnScreenIsSuspendedWithoutChangingTheCardShown() throws SQLException {
        Deck deck = services.deckService().createDeck("Leech");
        WordCard leech = services.wordRepository().insert(reviewCard(deck, "cavil", "挑剔", WordCard.LEECH_LAPSES - 1));
        WordCard other = reviewCard(deck, "lucid", "清晰的", 0);
        other.setNextReviewAt(LocalDateTime.now().minusMinutes(30));
        services.wordRepository().insert(other);
        presenter.showDeck(deck.getId());
        assertEquals("cavil", presenter.question());
        answer("完全错误");
        presenter.rate(ReviewRating.AGAIN);
        assertEquals("lucid", presenter.question());
        answer("清晰");
        presenter.submit();

        presenter.suspendLeech();

        assertTrue(stored(leech).isSuspended());
        assertEquals("lucid", presenter.question());
        assertEquals(State.ANSWERED, presenter.state(), "the answered card stays as it was");
        assertTrue(presenter.leechNotice().contains("Suspended"), presenter.leechNotice());
        assertEquals("Undo suspending \"cavil\"", presenter.undoDescription());

        presenter.undo();

        assertFalse(stored(leech).isSuspended());
        assertEquals("lucid", presenter.question());
        assertEquals(State.ANSWERED, presenter.state());
        assertEquals("Leech: \"cavil\" lapsed 8 times. Suspend it for now, or find a way to remember it.",
            presenter.leechNotice());
        assertTrue(presenter.canSuspendLeech());
        // The rating that made it a leech can be undone next.
        assertEquals("Undo the Again rating of \"cavil\"", presenter.undoDescription());
    }

    @Test
    void theLeechNoticeAsksTheAiForAMemoryAid() throws SQLException {
        Deck deck = services.deckService().createDeck("Leech");
        services.wordRepository().insert(reviewCard(deck, "cavil", "挑剔", WordCard.LEECH_LAPSES - 1));
        presenter.showDeck(deck.getId());
        answer("完全错误");
        presenter.rate(ReviewRating.AGAIN);
        tasks.runAll();
        assertTrue(presenter.canRequestMemoryAid());

        presenter.requestMemoryAid();

        assertEquals("Memory aid: loading...", presenter.memoryAid());
        assertFalse(presenter.canRequestMemoryAid());
        tasks.runAll();
        assertEquals("Memory aid for \"cavil\":" + System.lineSeparator() + "Mnemonic for cavil", presenter.memoryAid());
        ExplanationRequest asked = ai.requests.get(ai.requests.size() - 1);
        assertEquals(ExplanationRequest.Focus.MEMORY_AID, asked.focus());
        assertEquals("cavil", asked.word().getEnglish());
        assertTrue(presenter.canRequestMemoryAid());

        // Without an AI provider (or offline) there is nothing to ask.
        ai.available = false;
        assertFalse(presenter.canRequestMemoryAid());
    }

    @Test
    void aWordDeletedSinceItsRatingCannotBeUndone() throws SQLException {
        presenter.showDeck(deckId);
        WordCard rated = presenter.card().orElseThrow();
        rateShownCard(ReviewRating.GOOD);
        WordCard shown = presenter.card().orElseThrow();
        services.wordRepository().deleteById(rated.getId());

        presenter.undo();

        assertEquals(1, failures.size());
        assertTrue(failures.get(0).startsWith("Undo failed: \"" + rated.getEnglish() + "\" was deleted"), failures.get(0));
        assertEquals(shown.getId(), presenter.card().orElseThrow().getId());
        assertFalse(presenter.canUndo());
    }

    private void rateShownCard(ReviewRating rating) {
        answer(firstMeaning(presenter.card().orElseThrow()));
        presenter.rate(rating);
    }

    private void answer(String text) {
        presenter.setAnswer(text);
        presenter.submit();
    }

    private WordCard stored(WordCard word) throws SQLException {
        return services.wordRepository().findById(word.getId()).orElseThrow();
    }

    private static String firstMeaning(WordCard card) {
        return card.getChinese().split("[;；,，/、]")[0].trim();
    }

    private static WordCard reviewCard(Deck deck, String english, String chinese, int lapses) {
        WordCard card = WordCard.createNew(deck.getId(), english, chinese);
        card.setState(CardState.REVIEW);
        card.setStability(2);
        card.setDifficulty(9);
        card.setRepetitions(20);
        card.setLapses(lapses);
        card.setLastReviewedAt(LocalDateTime.now().minusDays(3));
        card.setNextReviewAt(LocalDateTime.now().minusDays(1));
        return card;
    }

    /** An AI provider that answers at once and records what it was asked. */
    static final class RecordingAi implements AiService {
        final List<ExplanationRequest> requests = new ArrayList<>();
        volatile boolean available = true;

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public String explain(WordCard word) {
            return explain(ExplanationRequest.of(word));
        }

        @Override
        public String explain(ExplanationRequest request) {
            requests.add(request);
            return (request.focus() == ExplanationRequest.Focus.MEMORY_AID ? "Mnemonic for " : "Explanation of ")
                + request.word().getEnglish();
        }
    }
}
