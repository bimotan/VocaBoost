package com.vocabtrainer.ui;

import com.vocabtrainer.app.AppServices;
import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.WordRepository;
import com.vocabtrainer.service.AiService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Background work that finishes after the user moved on (review finding C7): it is applied to the
 * deck or card it was started for and leaves the current deck, card and typed answer alone.
 */
@Tag("ui")
class LateResultsUiTest extends MainWindowUiTest {
    private final HeldAi ai = new HeldAi();
    private HeldWordRepository words;

    @Override
    AppServices.Builder configure(AppServices.Builder builder) {
        return builder
            .wordRepository(databaseManager -> words = new HeldWordRepository(databaseManager))
            .aiService((cache, settings) -> ai);
    }

    /** Runs before the base class waits for background tasks, so a failed test cannot leave one waiting. */
    @AfterEach
    void releaseHeldWork() {
        ai.gate.release();
        words.gate.release();
    }

    @Test
    void anImportThatFinishesAfterADeckSwitchCountsForTheDeckItWentInto() throws Exception {
        long starterId = currentDeck().getId();
        startHeldCsvImport();
        for (String id : List.of("importCsvButton", "importLegacyButton", "previewCsvButton", "importStarterButton")) {
            assertTrue(isDisabled(id), id + " must be disabled while the import runs");
        }

        dialogs.answerText("TOEFL");
        click("newDeckButton");
        Deck toefl = currentDeck();
        assertEquals("TOEFL", toefl.getName());
        words.gate.release();
        waitForBackgroundTasks();

        assertTrue(text("importStatusLabel").startsWith("Deck: " + STARTER_DECK + System.lineSeparator()
            + "Imported 2, skipped 0."), text("importStatusLabel"));
        assertFalse(isDisabled("importCsvButton"));
        assertTrue(services.wordRepository().findByEnglish(starterId, "petrichor").isPresent());
        assertTrue(services.wordRepository().findByEnglish(toefl.getId(), "petrichor").isEmpty());
        assertEquals(2, services.goalService().getTodayProgress(starterId).newWordsCount());
        assertEquals(0, services.goalService().getTodayProgress(toefl.getId()).newWordsCount());
        assertEquals(toefl.getId(), currentDeck().getId());
        selectTab("dashboardTab");
        assertEquals("0", text("totalWordsLabel"));
        assertEquals("0 / 5", text("newWordsTodayLabel"));
    }

    @Test
    void anImportThatFinishesWhileAnAnswerIsTypedKeepsTheCardAndTheAnswer() throws Exception {
        startHeldCsvImport();
        selectTab("reviewTab");
        String question = text("reviewWordLabel");
        type("answerField", "半个答案");

        words.gate.release();
        waitForBackgroundTasks();

        assertTrue(text("importStatusLabel").startsWith("Deck: " + STARTER_DECK), text("importStatusLabel"));
        assertEquals(question, text("reviewWordLabel"));
        assertEquals("半个答案", text("answerField"));
        assertFalse(isDisabled("answerField"));
        assertFalse(isDisabled("submitAnswerButton"));
    }

    @Test
    void anImportThatFinishesAfterAnAnswerWasSubmittedKeepsItReadyToRate() throws Exception {
        startHeldCsvImport();
        selectTab("reviewTab");
        WordCard word = questionWord();
        type("answerField", correctAnswer(word));
        click("submitAnswerButton");

        words.gate.release();
        waitForBackgroundTasks();

        assertEquals(word.getEnglish(), text("reviewWordLabel"));
        assertTrue(text("reviewResultArea").startsWith("Correct answer: " + word.getChinese() + System.lineSeparator()
            + "Your answer: " + correctAnswer(word)), text("reviewResultArea"));
        assertFalse(isDisabled("ratingButtons"));
        click("rateGoodButton");
        assertEquals(1, services.reviewLogRepository().findByDeck(currentDeck().getId()).size());
    }

    @Test
    void aLateExplanationDoesNotRevealTheAnswerWhenTheSameWordIsAskedAgain() throws SQLException {
        dialogs.answerText("Single");
        click("newDeckButton");
        services.wordRepository().insert(WordCard.createNew(currentDeck().getId(), "lucid", "清晰的"));
        selectTab("reviewTab");
        click("resetSessionButton");
        assertEquals("lucid", text("reviewWordLabel"));
        ai.gate.hold();
        type("answerField", "清晰的");
        click("submitAnswerButton");
        assertTrue(text("reviewResultArea").endsWith("AI explanation: loading..."), text("reviewResultArea"));

        click("rateGoodButton");
        // Nothing else is due and its 10-minute learning step ends within 20 minutes, so the same
        // word is asked again at once.
        assertEquals("lucid", text("reviewWordLabel"));
        String saved = text("reviewResultArea");
        assertTrue(saved.startsWith("Saved. XP +"), saved);

        ai.gate.release();
        waitForBackgroundTasks();

        assertEquals(saved, text("reviewResultArea"));
        assertEquals("", text("answerField"));
        assertFalse(isDisabled("answerField"));
    }

    @Test
    void aLateExplanationFailureDoesNotOverwriteTheNextCard() {
        selectTab("reviewTab");
        ai.gate.hold();
        ai.failure = new IllegalStateException("provider timed out");
        type("answerField", "完全错误");
        click("submitAnswerButton");
        click("rateAgainButton");
        String saved = text("reviewResultArea");
        assertTrue(saved.startsWith("Saved. XP +"), saved);

        ai.gate.release();
        waitForBackgroundTasks();

        assertEquals(saved, text("reviewResultArea"));
        assertFalse(isDisabled("submitAnswerButton"));
    }

    /** Starts a GRE CSV import of two new words that waits before writing until the test releases it. */
    private void startHeldCsvImport() throws Exception {
        Path csv = tempDir.resolve("late.csv");
        Files.writeString(csv, """
            english,chinese,pos,example,tags
            petrichor,"雨后泥土的气味",noun,"",late
            sesquipedalian,"冗长的; 爱用长词的",adjective,"",late
            """, StandardCharsets.UTF_8);
        selectTab("addImportTab");
        dialogs.openFile(csv);
        click("chooseImportFileButton");
        words.gate.hold();
        click("importCsvButton");
        assertEquals("Importing...", text("importStatusLabel"));
    }

    /** Lets background work wait until the test releases it. */
    static final class Gate {
        private volatile CountDownLatch latch;

        void hold() {
            latch = new CountDownLatch(1);
        }

        void release() {
            CountDownLatch current = latch;
            if (current != null) {
                current.countDown();
            }
        }

        void await() {
            CountDownLatch current = latch;
            if (current == null) {
                return;
            }
            try {
                if (!current.await(Fx.TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new IllegalStateException("The test never released the held work");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    /** An AI provider that answers when the test releases it, like a slow network call. */
    static final class HeldAi implements AiService {
        final Gate gate = new Gate();
        volatile RuntimeException failure;

        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public String explain(WordCard word) {
            gate.await();
            if (failure != null) {
                throw failure;
            }
            return "Late explanation: " + word.getEnglish() + " means " + word.getChinese();
        }
    }

    /** Holds bulk inserts (imports) before they start writing until the test releases them. */
    static final class HeldWordRepository extends WordRepository {
        final Gate gate = new Gate();

        HeldWordRepository(DatabaseManager databaseManager) {
            super(databaseManager);
        }

        @Override
        public int insertAll(List<WordCard> words) throws SQLException {
            gate.await();
            return super.insertAll(words);
        }
    }
}
