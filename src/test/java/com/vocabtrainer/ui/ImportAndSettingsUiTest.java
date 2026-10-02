package com.vocabtrainer.ui;

import com.vocabtrainer.service.LocalDictionaryService;
import com.vocabtrainer.service.LocalDictionaryStatus;
import com.vocabtrainer.service.SettingsService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("ui")
class ImportAndSettingsUiTest extends MainWindowUiTest {
    @Test
    void savingAnEcdictPathReloadsTheDictionaryAndClearingItFallsBack() throws Exception {
        Path csv = tempDir.resolve("ecdict-mini.csv");
        Files.writeString(csv, """
            word,phonetic,translation,pos
            petrichor,ˈpetrɪkɔː,雨后泥土的气味,n
            sesquipedalian,ˌseskwɪpɪˈdeɪliən,冗长的; 爱用长词的,adj
            """, StandardCharsets.UTF_8);
        LocalDictionaryStatus expected = new LocalDictionaryService(csv.toString()).status();
        selectTab("addImportTab");
        assertEquals("", text("ecdictPathField"));

        dialogs.openFile(csv);
        click("chooseEcdictButton");
        ScriptedDialogs.Shown chooser = dialogs.last(ScriptedDialogs.Kind.OPEN_FILE);
        assertEquals("Choose ECDICT CSV", chooser.title());
        assertEquals("CSV [*.csv], All Files [*.*]", chooser.content());
        assertEquals(csv.toString(), text("ecdictPathField"));

        click("testEcdictButton");
        waitForText("ecdictStatusLabel", expected.toDisplayText() + System.lineSeparator() + "Configured CSV loaded.");
        assertTrue(services.settingsService().getEcdictPath().isEmpty(), "Test must not save the path");

        click("saveEcdictButton");
        waitForText("ecdictStatusLabel", "Saved. " + expected.toDisplayText());
        assertEquals(csv.toString(), services.settingsService().getEcdictPath().orElseThrow());
        assertEquals(String.valueOf(expected.loadedCount()),
            services.settingsService().get(SettingsService.ECDICT_LAST_LOADED_COUNT_KEY).orElseThrow());
        assertTrue(services.settingsService().get(SettingsService.ECDICT_LAST_LOADED_AT_KEY).isPresent());
        assertTrue(headerSubtitle().contains("| Dictionary: ECDICT configured |"), headerSubtitle());

        // The reloaded dictionary knows the CSV's words.
        type("addEnglishField", "petrichor");
        type("addChineseField", "雨后泥土的气味");
        click("addWordButton");
        waitForText("addWordStatusLabel", "Added to " + STARTER_DECK + ": petrichor | Verified by Local dictionary");

        click("clearEcdictButton");
        waitForText("ecdictStatusLabel", "Cleared. Using bundled starter and online fallback.");
        assertEquals("", text("ecdictPathField"));
        assertTrue(services.settingsService().getEcdictPath().isEmpty());
        assertTrue(headerSubtitle().contains("| Dictionary: starter/online fallback |"), headerSubtitle());
    }

    @Test
    void aGreCsvChosenWithTheFileChooserIsPreviewedAndImportedIntoTheCurrentDeck() throws Exception {
        Path csv = tempDir.resolve("my-words.csv");
        Files.writeString(csv, """
            english,chinese,pos,example,tags
            petrichor,"雨后泥土的气味",noun,"The petrichor rose after the storm.",mine
            sesquipedalian,"冗长的; 爱用长词的",adjective,"",mine
            abate,"减弱; 减少",verb,"",mine
            """, StandardCharsets.UTF_8);
        selectTab("addImportTab");

        dialogs.openFile(csv);
        click("chooseImportFileButton");
        assertEquals("Choose import file", dialogs.last(ScriptedDialogs.Kind.OPEN_FILE).title());
        assertEquals(csv.toString(), text("importPathField"));

        click("previewCsvButton");
        waitForBackgroundTasks();
        assertTrue(text("importStatusLabel").startsWith("Deck: " + STARTER_DECK + System.lineSeparator()),
            text("importStatusLabel"));
        assertEquals(String.valueOf(STARTER_WORDS), text("totalWordsLabel"));

        click("importCsvButton");
        waitForBackgroundTasks();
        assertTrue(text("importStatusLabel").startsWith("Deck: " + STARTER_DECK + System.lineSeparator()
            + "Imported 2, skipped 1."), text("importStatusLabel"));
        assertEquals(String.valueOf(STARTER_WORDS + 2), text("totalWordsLabel"));
        assertEquals(STARTER_WORDS + 2, rowCount("wordTable"));
        long deckId = currentDeck().getId();
        assertTrue(services.wordRepository().findByEnglish(deckId, "petrichor").isPresent());
        assertTrue(services.wordRepository().findByEnglish(deckId, "sesquipedalian").isPresent());
    }

    @Test
    void importingWithoutAFileAsksForOne() {
        selectTab("addImportTab");
        click("importCsvButton");

        assertEquals("Please choose an import file first.", text("importStatusLabel"));
        assertFalse(dialogs.wasShown(ScriptedDialogs.Kind.ERROR));
    }

    @Test
    void savingIncompleteAiSettingsExplainsWhatIsMissing() {
        selectTab("addImportTab");
        type("aiBaseUrlField", "https://example.invalid/v1/chat/completions");
        click("saveAiButton");

        assertEquals("AI base URL, API key, and model are required.", text("aiStatusLabel"));
        assertTrue(services.settingsService().getAiBaseUrl().isEmpty());
        assertTrue(headerSubtitle().endsWith("| AI: mock"), headerSubtitle());
    }
}
