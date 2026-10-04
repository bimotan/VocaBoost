package com.vocabtrainer.ui;

import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.WordCard;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Restoring a JSON backup from the Statistics tab into a new deck (review finding E1). */
class BackupRestoreUiTest extends MainWindowUiTest {
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
}
