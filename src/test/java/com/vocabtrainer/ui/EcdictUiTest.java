package com.vocabtrainer.ui;

import com.vocabtrainer.app.AppServices;
import com.vocabtrainer.domain.EcdictMetadata;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.EcdictRepository;
import com.vocabtrainer.repository.SettingsRepository;
import com.vocabtrainer.service.SettingsService;
import com.vocabtrainer.service.ecdict.EcdictFixtures;
import com.vocabtrainer.service.ecdict.EcdictImportService;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ScrollPane;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The ECDICT box on the Add / Import tab: test, import in the background, cancel, look up, clear, and startup. */
@Tag("ui")
class EcdictUiTest extends MainWindowUiTest {
    private static final String NEW_LINE = System.lineSeparator();
    private static final String ABANDON_MEANING = "放弃; 抛弃; 遗弃; 使屈从; 沉溺; 放纵; 放任; 无拘束; 狂热";

    /** What was imported before the window opened, for the startup tests. */
    private EcdictMetadata importedBeforeStart;

    @Override
    AppServices.Builder configure(AppServices.Builder builder) {
        try {
            switch (testName()) {
                case "startupWithAnUpToDateImportNeverReadsTheCsv" -> prepareImportedCsv(false);
                case "startupImportsTheCsvAgainWhenItChanged" -> prepareImportedCsv(true);
                default -> {
                    // Nothing imported, no path saved.
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot prepare the ECDICT files", e);
        }
        return builder;
    }

    @Test
    void testingThenSavingImportsTheCsvAndLookupsFillTheFormWithACleanAnswer() throws Exception {
        Path csv = EcdictFixtures.write(tempDir.resolve("ecdict.csv"), true, EcdictFixtures.REAL_ROWS);
        selectTab("addImportTab");
        assertEquals("", text("ecdictPathField"));
        assertEquals("ECDICT: not imported. Bundled GRE starter: 215 entries.", text("ecdictStatusLabel"));
        assertFalse(isVisible("ecdictProgressBar"));

        dialogs.openFile(csv);
        click("chooseEcdictButton");
        ScriptedDialogs.Shown chooser = dialogs.last(ScriptedDialogs.Kind.OPEN_FILE);
        assertEquals("Choose ECDICT CSV", chooser.title());
        assertEquals("CSV [*.csv], All Files [*.*]", chooser.content());
        assertEquals(csv.toString(), text("ecdictPathField"));

        click("testEcdictButton");
        waitForBackgroundTasks();
        assertEquals("Test only, nothing imported." + NEW_LINE
                + "Encoding: UTF-8 with BOM | Delimiter: comma | Columns: word, phonetic, definition, translation, pos,"
                + " collins, oxford, tag, bnc, frq, exchange (header row)" + NEW_LINE
                + "First 6 rows: 6 entries, 0 skipped." + NEW_LINE
                + "Example: 'hood: 罩; 风帽; （布质）面罩; 学位连领帽（表示学位种类）; 覆盖; 用头巾包; 使(马,鹰等)戴头罩; 给…加罩",
            text("ecdictStatusLabel"));
        assertTrue(services.settingsService().getEcdictPath().isEmpty(), "Test must not save the path");
        assertFalse(Files.exists(tempDir.resolve("ecdict.db")), "Test must not import");

        click("saveEcdictButton");
        waitForBackgroundTasks();
        String status = text("ecdictStatusLabel");
        assertTrue(status.startsWith("Imported 6 entries in "), status);
        assertTrue(status.contains(NEW_LINE + "ECDICT: 6 entries from " + csv.toAbsolutePath() + ", imported "), status);
        assertFalse(isVisible("ecdictProgressBar"));
        assertEquals(csv.toString(), services.settingsService().getEcdictPath().orElseThrow());
        assertTrue(headerSubtitle().contains("| Dictionary: ECDICT configured |"), headerSubtitle());
        EcdictMetadata imported = services.ecdictImportService().imported().orElseThrow();
        scrollTo("ecdictPathField");
        snapshot("imported");

        // The answer key is the cleaned translation; the tagged sense and the definition go to the note.
        lookUp("abacus");
        assertEquals("Loaded from local dictionary.", text("lookupStatusLabel"));
        assertEquals("abacus", text("addEnglishField"));
        assertEquals("算盘", text("addChineseField"));
        assertEquals("noun", text("addPosField"));
        assertEquals("'æbәkәs", text("addPhoneticField"));
        assertTrue(text("addNoteArea").startsWith("[计] 算盘" + NEW_LINE + "English definition: n. a tablet placed"),
            text("addNoteArea"));

        // An inflection that has no entry of its own finds its base form.
        lookUp("abandons");
        assertEquals("Not in the local dictionary as written: abandons is the third-person singular of abandon."
            + " Showing the base form.", text("lookupStatusLabel"));
        assertEquals("abandon", text("addEnglishField"));
        assertEquals(ABANDON_MEANING, text("addChineseField"));
        assertEquals("verb; noun", text("addPosField"));
        click("addWordButton");
        waitForText("addWordStatusLabel", "Added to " + STARTER_DECK + ": abandon | Verified by Local dictionary");
        WordCard abandon = services.wordRepository().findByEnglish(currentDeck().getId(), "abandon").orElseThrow();
        assertEquals(ABANDON_MEANING, abandon.getChinese());

        // Saving the unchanged file does not import it again; Re-import does.
        click("saveEcdictButton");
        waitForBackgroundTasks();
        assertTrue(text("ecdictStatusLabel").startsWith("Saved. Already imported, the file has not changed." + NEW_LINE
            + "ECDICT: 6 entries from "), text("ecdictStatusLabel"));
        assertEquals(imported, services.ecdictImportService().imported().orElseThrow());
        click("reimportEcdictButton");
        waitForBackgroundTasks();
        assertTrue(text("ecdictStatusLabel").startsWith("Imported 6 entries in "), text("ecdictStatusLabel"));
        assertNotEquals(imported, services.ecdictImportService().imported().orElseThrow());

        click("clearEcdictButton");
        assertEquals("Cleared. Using bundled starter and online fallback.", text("ecdictStatusLabel"));
        assertEquals("", text("ecdictPathField"));
        assertTrue(services.settingsService().getEcdictPath().isEmpty());
        assertFalse(Files.exists(tempDir.resolve("ecdict.db")));
        assertTrue(headerSubtitle().contains("| Dictionary: starter/online fallback |"), headerSubtitle());
        lookUp("abacus");
        assertTrue(text("lookupStatusLabel").startsWith("词条未找到。"), text("lookupStatusLabel"));
    }

    @Test
    void cancelingAnImportKeepsThePreviousDictionaryAndPath() throws Exception {
        Path small = EcdictFixtures.write(tempDir.resolve("small.csv"), false, EcdictFixtures.REAL_ROWS);
        Path large = EcdictFixtures.writeGenerated(tempDir.resolve("large.csv"), 200_000, "新");
        selectTab("addImportTab");
        type("ecdictPathField", small.toString());
        click("saveEcdictButton");
        waitForBackgroundTasks();
        EcdictMetadata before = services.ecdictImportService().imported().orElseThrow();

        type("ecdictPathField", large.toString());
        click("saveEcdictButton");
        assertTrue(isVisible("ecdictProgressBar"));
        for (String button : new String[] {"testEcdictButton", "saveEcdictButton", "reimportEcdictButton", "clearEcdictButton"}) {
            assertTrue(isDisabled(button), button + " must wait for the import");
        }
        Fx.waitUntil("progress is shown", () -> text("ecdictStatusLabel").contains(NEW_LINE + "Read "));
        scrollTo("ecdictPathField");
        snapshot("importing");
        click("cancelEcdictImportButton");
        waitForBackgroundTasks();

        assertTrue(text("ecdictStatusLabel").startsWith("Import canceled. The path was not saved." + NEW_LINE
            + "ECDICT: 6 entries from " + small.toAbsolutePath()), text("ecdictStatusLabel"));
        assertEquals(before, services.ecdictImportService().imported().orElseThrow());
        assertEquals(small.toString(), services.settingsService().getEcdictPath().orElseThrow());
        assertFalse(isVisible("ecdictProgressBar"));
        assertFalse(isDisabled("saveEcdictButton"));
        assertFalse(Files.exists(tempDir.resolve("ecdict.db.importing")));
        lookUp("abandon");
        assertEquals(ABANDON_MEANING, text("addChineseField"));
    }

    @Test
    void aCsvThatCannotBeImportedIsExplainedAndItsPathIsNotSaved() throws Exception {
        Path csv = tempDir.resolve("no-meanings.csv");
        Files.writeString(csv, "word,phonetic\nlucid,ˈluːsɪd\n");
        selectTab("addImportTab");
        type("ecdictPathField", csv.toString());

        click("saveEcdictButton");
        waitForBackgroundTasks();

        assertEquals("Import failed: Cannot import ECDICT CSV " + csv.toAbsolutePath() + ": Line 1: the header row has"
                + " no Chinese meaning column (translation, chinese or 释义)" + NEW_LINE
                + "The path was not saved." + NEW_LINE
                + "ECDICT: not imported. Bundled GRE starter: 215 entries.",
            text("ecdictStatusLabel"));
        assertTrue(services.settingsService().getEcdictPath().isEmpty());

        type("ecdictPathField", tempDir.resolve("missing.csv").toString());
        click("saveEcdictButton");
        assertEquals("ECDICT CSV not found: " + tempDir.resolve("missing.csv"), text("ecdictStatusLabel"));
        assertFalse(dialogs.wasShown(ScriptedDialogs.Kind.ERROR));
    }

    @Test
    void startupWithAnUpToDateImportNeverReadsTheCsv() throws Exception {
        // prepareImportedCsv(false) replaced the CSV by garbage of the same size and time: reading it
        // would fail or change the dictionary.
        waitForBackgroundTasks();
        selectTab("addImportTab");

        assertEquals(importedBeforeStart, services.ecdictImportService().imported().orElseThrow());
        assertEquals(services.localDictionary().status().toDisplayText(), text("ecdictStatusLabel"));
        assertTrue(text("ecdictStatusLabel").startsWith("ECDICT: 6 entries from "), text("ecdictStatusLabel"));
        assertFalse(isVisible("ecdictProgressBar"));
        lookUp("abandon");
        assertEquals(ABANDON_MEANING, text("addChineseField"));
    }

    @Test
    void startupImportsTheCsvAgainWhenItChanged() throws Exception {
        waitForBackgroundTasks();
        selectTab("addImportTab");

        EcdictMetadata imported = services.ecdictImportService().imported().orElseThrow();
        assertEquals(100, imported.rowCount());
        assertNotEquals(importedBeforeStart, imported);
        assertTrue(text("ecdictStatusLabel").startsWith("Imported 100 entries in "), text("ecdictStatusLabel"));
        lookUp(EcdictFixtures.generatedWord(5));
        assertEquals("新5; 测试; 检验", text("addChineseField"));
        assertEquals("[网络] 生成" + NEW_LINE + "English definition: n. a generated entry" + NEW_LINE
            + "v. to test, quickly" + NEW_LINE + "Dictionary source: ECDICT/local CSV", text("addNoteArea"));
    }

    /** Scrolls the tab so the node with {@code id} is at the top, for snapshots. */
    private void scrollTo(String id) {
        Fx.run(() -> {
            Node node = find(id, Node.class);
            Parent parent = node.getParent();
            while (parent != null && !(parent instanceof ScrollPane)) {
                parent = parent.getParent();
            }
            ScrollPane pane = (ScrollPane) parent;
            Node content = pane.getContent();
            Bounds bounds = content.sceneToLocal(node.localToScene(node.getBoundsInLocal()));
            double scrollable = content.getBoundsInLocal().getHeight() - pane.getViewportBounds().getHeight();
            pane.setVvalue(Math.max(0, Math.min(1, (bounds.getMinY() - 60) / scrollable)));
        });
    }

    private void lookUp(String word) {
        type("lookupField", word);
        click("lookupButton");
        waitForBackgroundTasks();
    }

    /**
     * Imports the real rows as the app would have, saves the path, then changes the CSV: to garbage
     * of the same size and modification time, or ({@code changed}) to other entries.
     */
    private void prepareImportedCsv(boolean changed) throws Exception {
        Path csv = EcdictFixtures.write(tempDir.resolve("ecdict.csv"), false, EcdictFixtures.REAL_ROWS);
        try (EcdictRepository ecdict = new EcdictRepository(tempDir.resolve("ecdict.db"))) {
            importedBeforeStart = new EcdictImportService(ecdict).importCsv(csv, progress -> { }, () -> false);
        }
        DatabaseManager database = new DatabaseManager(tempDir.resolve("vocab.db"));
        try {
            database.initialize();
            new SettingsService(new SettingsRepository(database)).saveEcdictPath(csv.toString());
        } finally {
            database.close();
        }
        if (changed) {
            EcdictFixtures.writeGenerated(csv, 100, "新");
        } else {
            FileTime modified = Files.getLastModifiedTime(csv);
            Files.write(csv, new byte[(int) Files.size(csv)]);
            Files.setLastModifiedTime(csv, modified);
        }
    }
}
