package com.vocabtrainer.ui;

import com.vocabtrainer.app.AppServices;
import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.ReviewLog;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.ReviewLogRepository;
import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Restoring a JSON backup from the Statistics tab: into a new deck, and with saving paused on the
 * Review tab while the restore holds the database (review findings E1, B9 and C7).
 */
class BackupRestoreUiTest extends MainWindowUiTest {
    private final CountDownLatch restoreStarted = new CountDownLatch(1);
    private final CountDownLatch finishRestore = new CountDownLatch(1);

    /** In the pause test, the restore waits inside its transaction until the test lets it finish. */
    @Override
    AppServices.Builder configure(AppServices.Builder builder) {
        if (!testName().equals("aRestorePausesRatingsAndTheBackupButtonsUntilItIsDone")) {
            return builder;
        }
        return builder.reviewLogRepository(databaseManager -> new ReviewLogRepository(databaseManager) {
            @Override
            public int insertAllIfAbsent(List<ReviewLog> logs) throws SQLException {
                restoreStarted.countDown();
                try {
                    if (!finishRestore.await(20, TimeUnit.SECONDS)) {
                        throw new SQLException("the test never let the restore finish");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new SQLException("interrupted", e);
                }
                return super.insertAllIfAbsent(logs);
            }
        });
    }

    @Test
    void aBackupCanBeRestoredIntoANewDeckWhichBecomesTheCurrentDeck() throws Exception {
        WordCard reviewed = review(true, "rateGoodButton");
        Path backup = services.backupService().exportJsonBackup(currentDeck().getId(), tempDir.resolve("backup.json"));
        selectTab("statisticsTab");

        dialogs.openFile(backup).chooseButton("Restore into a new deck");
        click("importBackupButton");
        waitForBackgroundTasks();

        Deck restored = currentDeck();
        assertEquals(STARTER_DECK + " (2)", restored.getName());
        ScriptedDialogs.Shown summary = dialogs.last(ScriptedDialogs.Kind.TEXT);
        assertEquals("Deck: " + STARTER_DECK + " (2)", summary.header());
        assertTrue(summary.content().contains("Restored into a new deck, \"" + STARTER_DECK + " (2)\"."),
            summary.content());
        assertTrue(summary.content().contains("Words: " + STARTER_WORDS + " added, 0 updated"), summary.content());
        assertEquals(1, services.wordRepository().findByEnglish(restored.getId(), reviewed.getEnglish()).orElseThrow()
            .getRepetitions());
        selectTab("dashboardTab");
        assertEquals(String.valueOf(STARTER_WORDS), text("totalWordsLabel"));
    }

    @Test
    void aRestorePausesRatingsAndTheBackupButtonsUntilItIsDone() throws Exception {
        review(true, "rateGoodButton");
        Path backup = services.backupService().exportJsonBackup(currentDeck().getId(), tempDir.resolve("backup.json"));
        WordCard answered = questionWord();
        type("answerField", correctAnswer(answered));
        click("submitAnswerButton");
        waitForBackgroundTasks();
        assertFalse(isDisabled("ratingButtons"));
        assertFalse(isVisible("reviewPausedLabel"));

        selectTab("statisticsTab");
        dialogs.openFile(backup).chooseButton("Keep current progress");
        click("importBackupButton");
        assertTrue(restoreStarted.await(20, TimeUnit.SECONDS), "the restore started");

        assertTrue(isDisabled("importBackupButton"));
        assertTrue(isDisabled("exportBackupButton"));
        assertTrue(isDisabled("deckSelector"), "no deck switch, rename or archive during the restore");
        selectTab("reviewTab");
        assertTrue(isDisabled("ratingButtons"));
        assertTrue(isDisabled("undoButton"));
        assertTrue(isDisabled("reviewModeSelector"), "the session settings are saved too");
        assertTrue(isVisible("reviewPausedLabel"));
        assertTrue(text("reviewPausedLabel").startsWith("A backup is being restored."), text("reviewPausedLabel"));
        pressKey("reviewWordLabel", KeyCode.DIGIT3);
        assertEquals(0, services.reviewLogRepository().findByWord(answered.getId()).size(), "no rating while paused");

        finishRestore.countDown();
        waitForBackgroundTasks();

        assertTrue(dialogs.last(ScriptedDialogs.Kind.TEXT).content().startsWith("Backup restored"));
        assertFalse(isVisible("reviewPausedLabel"));
        assertFalse(isDisabled("ratingButtons"));
        assertFalse(isDisabled("importBackupButton"));
        assertFalse(isDisabled("exportBackupButton"));
        assertFalse(isDisabled("deckSelector"));
        assertEquals(answered.getEnglish(), text("reviewWordLabel"), "the answered card waits for its rating");
        click("rateGoodButton");
        assertEquals(1, services.reviewLogRepository().findByWord(answered.getId()).size());
    }
}
