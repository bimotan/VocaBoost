package com.vocabtrainer.ui;

import com.vocabtrainer.app.AppServices;
import com.vocabtrainer.domain.DictionaryEntry;
import com.vocabtrainer.domain.DictionaryLookupResult;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.service.CompositeDictionaryService;
import com.vocabtrainer.service.DictionaryService;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ScrollPane;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The import preview's column mapping, word-only lists and the exports for other apps. */
@Tag("ui")
class WordListImportUiTest extends MainWindowUiTest {
    /** An Anki "Notes in Plain Text" export with guid, note type, deck and tags columns. */
    private static final String ANKI_EXPORT = String.join("\n",
        "#separator:tab",
        "#html:true",
        "#guid column:1",
        "#notetype column:2",
        "#deck column:3",
        "#tags column:7",
        "b:R0zZ1+M#\tGRE Word\tGRE::Verbal\tobstreperous\t喧闹的；难以控制的<br>adj.\tThe <b>obstreperous</b> crowd booed.\tgre adjective",
        "Ffw]4X^qXE\tGRE Word\tGRE::Verbal\tpetrichor[sound:petrichor.mp3]\t<div>雨后泥土的气味</div>\tWe smelled the petrichor.\tgre",
        "s&8!/D,2Ps\tGRE Word\tGRE::Verbal\tsesquipedalian\t冗长的&nbsp;/ 爱用长词的\tHis sesquipedalian prose tires readers.\t",
        "kU2;t!e^1w\tGRE Word\tGRE::Verbal\tabate\t减弱\tThe storm began to abate.\tgre",
        "");

    private final CountDownLatch lookupStarted = new CountDownLatch(1);
    private final CountDownLatch releaseLookup = new CountDownLatch(1);

    @Override
    AppServices.Builder configure(AppServices.Builder builder) {
        if (testName().equals("anImportThatLooksMeaningsUpShowsItsProgressAndCanBeCanceled")) {
            // The online dictionaries take as long as the test wants for "obfuscate".
            return builder.dictionaryService((cache, local) -> new CompositeDictionaryService(List.of(local,
                new SlowDictionary())));
        }
        return builder;
    }

    @Test
    void anAnkiExportIsPreviewedWithItsColumnsWhichTheUserCanChangeBeforeImporting() throws Exception {
        Path file = write("GRE Verbal.txt", ANKI_EXPORT);
        selectTab("addImportTab");
        type("importPathField", file.toString());
        assertFalse(isVisible("columnMappingPane"));

        click("previewCsvButton");
        waitForBackgroundTasks();

        assertTrue(isVisible("columnMappingPane"));
        assertEquals("Column 4: obstreperous", chosen("mapEnglishColumn"));
        assertEquals("Column 5: 喧闹的；难以控制的; adj.", chosen("mapChineseColumn"));
        assertEquals("Column 6: The obstreperous crowd…", chosen("mapExampleColumn"));
        assertEquals("Column 7 · Anki tags: gre adjective", chosen("mapTagsColumn"));
        assertEquals("(none)", chosen("mapPhoneticColumn"));
        assertEquals("(none)", chosen("mapPosColumn"));
        assertEquals("(none)", chosen("mapNoteColumn"));
        assertEquals(8, Fx.call(() -> comboBox("mapNoteColumn").getItems().size()), "(none) and 7 columns");
        assertTrue(text("importFormatLabel").startsWith("File: UTF-8, tab-separated, 7 columns, Anki headers: "),
            text("importFormatLabel"));
        assertEquals(4, rowCount("importPreviewTable"));
        assertEquals("喧闹的; 难以控制的", cell("importPreviewTable", "7", 2));
        assertEquals("adj.", cell("importPreviewTable", "7", 3));
        assertEquals("The obstreperous crowd booed.", cell("importPreviewTable", "7", 5));
        assertEquals("Duplicate", cell("importPreviewTable", "10", 8), "abate is a starter word");
        String newLine = System.lineSeparator();
        assertEquals("Deck: " + STARTER_DECK + newLine
                + "Rows: 4, importable: 3, duplicates: 1, invalid: 0" + newLine
                + "Encoding: UTF-8 | Delimiter: tab | Columns: english, chinese, example, tags (no header row)"
                + " | Anki headers: deck column, guid column, html, notetype column, separator, tags column" + newLine
                + "First errors:" + newLine
                + "Line 10 skipped: duplicate word abate",
            text("importStatusLabel"));

        // The sentence is a note here, not an example, and the tags are not wanted.
        this.<Object>select("mapExampleColumn", choice -> choice.toString().equals("(none)"));
        waitForBackgroundTasks();
        this.<Object>select("mapNoteColumn", choice -> choice.toString().startsWith("Column 6"));
        waitForBackgroundTasks();
        this.<Object>select("mapTagsColumn", choice -> choice.toString().equals("(none)"));
        waitForBackgroundTasks();

        assertEquals("", cell("importPreviewTable", "7", 5));
        assertEquals("The obstreperous crowd booed.", cell("importPreviewTable", "7", 6));
        assertEquals("", cell("importPreviewTable", "7", 7));
        assertTrue(text("importStatusLabel").contains("| Columns: english, chinese, note (chosen)"),
            text("importStatusLabel"));
        assertEquals("Column 4: obstreperous", chosen("mapEnglishColumn"), "the other choices stay");
        showImportSection();
        snapshot("mapping");

        click("importCsvButton");
        waitForBackgroundTasks();

        assertTrue(text("importStatusLabel").startsWith("Deck: " + STARTER_DECK + newLine + "Imported 3, skipped 1."),
            text("importStatusLabel"));
        assertFalse(isVisible("columnMappingPane"), "the preview is out of date after the import");
        long deckId = currentDeck().getId();
        WordCard obstreperous = services.wordRepository().findByEnglish(deckId, "obstreperous").orElseThrow();
        assertEquals("喧闹的; 难以控制的", obstreperous.getChinese());
        assertEquals("adj.", obstreperous.getPartOfSpeech());
        assertEquals("The obstreperous crowd booed.", obstreperous.getNote());
        assertEquals("", orEmpty(obstreperous.getExampleSentence()));
        assertEquals("", orEmpty(obstreperous.getTags()));
        assertEquals("雨后泥土的气味", services.wordRepository().findByEnglish(deckId, "petrichor").orElseThrow().getChinese());
        selectTab("wordListTab");
        assertEquals(STARTER_WORDS + 3, rowCount("wordTable"));
    }

    @Test
    void choosingAnotherFileHidesTheColumnsOfTheLastOne() throws Exception {
        Path file = write("list.csv", "abate,减弱\n");
        selectTab("addImportTab");
        type("importPathField", file.toString());
        click("previewCsvButton");
        waitForBackgroundTasks();
        assertTrue(isVisible("columnMappingPane"));

        type("importPathField", tempDir.resolve("other.csv").toString());

        assertFalse(isVisible("columnMappingPane"));
    }

    @Test
    void aWordListGetsItsMeaningsFromTheLocalDictionaryAndOnlineOnlyWhenAllowed() throws Exception {
        // A new deck, so the starter words are not duplicates.
        dialogs.answerText("Word lists");
        click("newDeckButton");
        Path file = write("words.txt", "lucid\r\nprodigal\r\nobfuscate\r\nzzyzx\r\n");
        selectTab("addImportTab");
        type("importPathField", file.toString());
        click("previewCsvButton");
        waitForBackgroundTasks();

        assertEquals("(none)", chosen("mapChineseColumn"));
        assertTrue(isVisible("importMeaningHint"));
        assertEquals("Meaning from Bundled GRE starter", cell("importPreviewTable", "1", 8));
        assertEquals("Not in the local dictionary: skipped", cell("importPreviewTable", "3", 8));
        assertTrue(text("importStatusLabel").contains("Rows: 4, importable: 0, meanings to look up: 4"),
            text("importStatusLabel"));
        showImportSection();
        snapshot("word-list");

        click("importCsvButton");
        waitForBackgroundTasks();

        String newLine = System.lineSeparator();
        assertEquals("Deck: Word lists" + newLine
                + "Imported 2, skipped 2. Meanings from the local dictionary: 2." + newLine
                + "Line 3 skipped: obfuscate is not in the local dictionary, so it has no meaning to import" + newLine
                + "Line 4 skipped: zzyzx is not in the local dictionary, so it has no meaning to import",
            text("importStatusLabel"));
        WordCard lucid = services.wordRepository().findByEnglish(currentDeck().getId(), "lucid").orElseThrow();
        assertEquals("清晰的; 明白易懂的", lucid.getChinese());
        assertEquals("adjective", lucid.getPartOfSpeech());

        // The test's online dictionary knows "obfuscate".
        Fx.run(() -> find("importOnlineLookupCheckBox", CheckBox.class).setSelected(true));
        click("importCsvButton");
        waitForBackgroundTasks();

        assertTrue(text("importStatusLabel").startsWith("Deck: Word lists" + newLine
            + "Imported 1, skipped 3. Meanings from the local and online dictionaries: 1."), text("importStatusLabel"));
        WordCard obfuscate = services.wordRepository().findByEnglish(currentDeck().getId(), "obfuscate").orElseThrow();
        assertEquals("使模糊; 使困惑", obfuscate.getChinese());
        assertEquals("verb", obfuscate.getPartOfSpeech());
        assertEquals("/ˈɒbfʌskeɪt/", obfuscate.getPhonetic());

        click("offlineModeToggle");
        assertTrue(isDisabled("importOnlineLookupCheckBox"), "offline mode allows no online lookup");
        assertFalse(Fx.call(() -> find("importOnlineLookupCheckBox", CheckBox.class).isSelected()));
        click("offlineModeToggle");
        assertFalse(isDisabled("importOnlineLookupCheckBox"));
    }

    @Test
    void anImportThatLooksMeaningsUpShowsItsProgressAndCanBeCanceled() throws Exception {
        Path file = write("words.txt", "petrichor\nobfuscate\nsesquipedalian\n");
        selectTab("addImportTab");
        type("importPathField", file.toString());
        Fx.run(() -> find("importOnlineLookupCheckBox", CheckBox.class).setSelected(true));
        assertFalse(isVisible("importProgressBar"));

        click("importCsvButton");
        assertTrue(lookupStarted.await(10, TimeUnit.SECONDS), "the lookup of obfuscate started");

        assertTrue(isVisible("importProgressBar"));
        assertTrue(isDisabled("importCsvButton"), "the same file cannot be imported twice at once");
        assertTrue(isDisabled("importOnlineLookupCheckBox"), "the running import has its lookup choice");
        waitForTextStartingWith("importStatusLabel", "Importing..." + System.lineSeparator()
            + "Looking up meanings: 1 of 3");
        showImportSection();
        snapshot("progress");
        click("cancelImportButton");
        assertEquals("Canceling the import...", text("importStatusLabel"));
        releaseLookup.countDown();
        waitForBackgroundTasks();

        assertEquals("Import canceled. Nothing was imported.", text("importStatusLabel"));
        assertFalse(isVisible("importProgressBar"));
        assertFalse(isDisabled("importCsvButton"));
        assertFalse(isDisabled("importOnlineLookupCheckBox"));
        assertTrue(services.wordRepository().findByEnglish(currentDeck().getId(), "petrichor").isEmpty());
        selectTab("wordListTab");
        assertEquals(STARTER_WORDS, rowCount("wordTable"));
    }

    @Test
    void theDeckIsExportedForAnkiAndAsAWordList() throws Exception {
        Path anki = tempDir.resolve("exports").resolve("anki.txt");
        Path list = tempDir.resolve("exports").resolve("list.txt");
        selectTab("addImportTab");

        dialogs.saveFile(anki);
        click("exportAnkiButton");
        waitForBackgroundTasks();
        dialogs.saveFile(list);
        click("exportWordListButton");
        waitForBackgroundTasks();

        assertEquals("vocaboost-anki.txt", dialogs.shown(ScriptedDialogs.Kind.SAVE_FILE).get(0).value());
        assertEquals("vocaboost-word-list.txt", dialogs.last(ScriptedDialogs.Kind.SAVE_FILE).value());
        assertEquals("Exported: " + list.toAbsolutePath(), dialogs.last(ScriptedDialogs.Kind.INFO).content());
        List<String> ankiLines = Files.readAllLines(anki, StandardCharsets.UTF_8);
        assertEquals(List.of("#separator:tab", "#html:true", "#columns:English\tChinese\tExample\tTags", "#tags column:4"),
            ankiLines.subList(0, 4));
        assertEquals(4 + STARTER_WORDS, ankiLines.size());
        assertTrue(ankiLines.stream().anyMatch(line -> line.startsWith("abate\t减弱; 减少<br><span class='pos'>verb</span>")),
            String.join("\n", ankiLines.subList(0, 8)));
        List<String> words = Files.readAllLines(list, StandardCharsets.UTF_8);
        assertEquals(STARTER_WORDS, words.size());
        assertTrue(words.contains("abate"));
    }

    /** Scrolls the Add / Import tab down to the import section, for the snapshots. */
    private void showImportSection() {
        Fx.run(() -> {
            ScrollPane scrollPane = (ScrollPane) tab("addImportTab").getContent();
            Node section = find("columnMappingPane", Node.class).getParent();
            scrollPane.layout();
            double contentHeight = scrollPane.getContent().getBoundsInLocal().getHeight();
            double viewport = scrollPane.getViewportBounds().getHeight();
            double top = section.getBoundsInParent().getMinY();
            scrollPane.setVvalue(Math.min(1, Math.max(0, top / Math.max(1, contentHeight - viewport))));
        });
        Fx.flush();
    }

    private String chosen(String comboBoxId) {
        return Fx.call(() -> String.valueOf(comboBox(comboBoxId).getValue()));
    }

    private Path write(String name, String text) throws Exception {
        Path file = tempDir.resolve(name);
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    /** Knows "obfuscate", and answers only when the test releases it. */
    private final class SlowDictionary implements DictionaryService {
        @Override
        public DictionaryLookupResult lookup(String english) {
            if (!"obfuscate".equals(english)) {
                return DictionaryLookupResult.notFound("Not in the slow dictionary.");
            }
            lookupStarted.countDown();
            try {
                if (!releaseLookup.await(20, TimeUnit.SECONDS)) {
                    throw new AssertionError("The test never released the lookup");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return DictionaryLookupResult.interrupted();
            }
            return DictionaryLookupResult.success("Found.", List.of(
                new DictionaryEntry("obfuscate", "使模糊", "verb", "", "", "Slow dictionary")));
        }

        @Override
        public boolean isConfigured() {
            return true;
        }
    }
}
