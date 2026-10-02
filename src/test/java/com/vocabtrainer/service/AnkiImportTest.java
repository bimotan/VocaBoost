package com.vocabtrainer.service;

import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.DeckRepository;
import com.vocabtrainer.repository.TestDatabases;
import com.vocabtrainer.repository.WordRepository;
import com.vocabtrainer.service.csv.WordColumn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Anki plain-text exports, as the Anki desktop app writes them, import into a deck. */
class AnkiImportTest {
    /**
     * "Notes in Plain Text" from Anki 2.1.66 with HTML, guid, note type, deck and tags ticked, for a
     * note type with Word, Meaning and Example fields. Fields with a quote are quoted, as Anki does.
     */
    static final String NOTES_EXPORT = String.join("\n",
        "#separator:tab",
        "#html:true",
        "#guid column:1",
        "#notetype column:2",
        "#deck column:3",
        "#tags column:7",
        "b:R0zZ1+M#\tGRE Word\tGRE::Verbal\tobdurate\t顽固的；执拗的<br>adj.\tHe remained <b>obdurate</b>,<br>refusing to apologise.\tgre adjective",
        "Ffw]4X^qXE\tGRE Word\tGRE::Verbal\tabate[sound:abate.mp3]\t<div>减弱</div><div>减少</div>\tThe storm began to abate.\tgre verb",
        "s&8!/D,2Ps\tGRE Word\tGRE::Verbal\tlucid\t清晰的&nbsp;/ 易懂的<img src=\"lucid.png\">\t\"His \"\"lucid\"\" talk&nbsp;helped.\"\t",
        "kU2;t!e^1w\tGRE Word\tGRE::Verbal\tobdurate\t重复\t\tgre",
        "");

    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    private Deck deck;
    private WordRepository words;
    private ImportExportService service;

    @BeforeEach
    void openDatabase() throws SQLException {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("anki.db"));
        deck = new DeckRepository(databaseManager).ensureDefaultDeck();
        words = new WordRepository(databaseManager);
        service = new ImportExportService(words);
    }

    @Test
    void anAnkiNotesExportImportsItsFieldsAndTagsWithoutItsHtmlMediaOrMetadataColumns() throws Exception {
        Path file = write("GRE Verbal.txt", NOTES_EXPORT);

        ImportPreview preview = service.previewGreCsv(file, deck.getId());
        ImportResult result = service.importGreCsv(file, deck.getId());

        assertEquals("english, chinese, example, tags (no header row)", preview.columns());
        assertEquals("tab", preview.delimiter());
        assertEquals("Anki headers: deck column, guid column, html, notetype column, separator, tags column",
            preview.format());
        assertEquals(3, preview.importableCount());
        assertEquals(1, preview.duplicateCount());
        assertEquals(3, result.importedCount(), result.toSummary());
        assertEquals(List.of("Line 10 skipped: duplicate word obdurate"), result.messages(),
            "line numbers count the header lines");

        WordCard obdurate = word("obdurate");
        assertEquals("顽固的; 执拗的", obdurate.getChinese());
        assertEquals("adj.", obdurate.getPartOfSpeech(), "a part of speech alone on its line is not a meaning");
        assertEquals("He remained obdurate, refusing to apologise.", obdurate.getExampleSentence());
        assertEquals("gre; adjective", obdurate.getTags());
        WordCard abate = word("abate");
        assertEquals("减弱; 减少", abate.getChinese());
        assertEquals("gre; verb", abate.getTags());
        WordCard lucid = word("lucid");
        assertEquals("清晰的; 易懂的", lucid.getChinese());
        assertEquals("His \"lucid\" talk helped.", lucid.getExampleSentence());
        assertEquals("", orEmpty(lucid.getTags()));
        assertEquals(3, words.findAll(deck.getId()).size(), "the guid, note type and deck are not words");
    }

    @Test
    void columnNamesPipesGlobalTagsAndPlainTextFields() throws Exception {
        Path file = write("pipes.txt", String.join("\n",
            "#separator:Pipe",
            "#html:false",
            "#tags:gre-3000",
            "#columns:Front|Back|Tags",
            "#tags column:3",
            "candid|坦率的; 直言不讳的|honest",
            "laconic|简洁的 &amp; 精炼的|",
            ""));

        ImportPreview preview = service.previewGreCsv(file, deck.getId());
        service.importGreCsv(file, deck.getId());

        assertEquals("english, chinese, tags (Anki #columns)", preview.columns());
        assertEquals("pipe", preview.delimiter());
        assertEquals("坦率的; 直言不讳的", word("candid").getChinese());
        assertEquals("honest; gre-3000", word("candid").getTags());
        assertEquals("简洁的 &amp; 精炼的", word("laconic").getChinese(), "html:false keeps the text as it is");
        assertEquals("gre-3000", word("laconic").getTags());
    }

    @Test
    void columnNamesInChineseAndSemicolons() throws Exception {
        Path file = write("semicolons.csv", String.join("\r\n",
            "#separator:semicolon",
            "#html:true",
            "#columns:单词;释义;例句;音标",
            "abate;减弱<br>减少;The storm began to abate.;/əˈbeɪt/",
            ""));

        service.importGreCsv(file, deck.getId());

        WordCard abate = word("abate");
        assertEquals("减弱; 减少", abate.getChinese());
        assertEquals("The storm began to abate.", abate.getExampleSentence());
        assertEquals("/əˈbeɪt/", abate.getPhonetic());
    }

    @Test
    void anOlderAnkiExportWithoutHeadersIsReadAsHtmlWhereItHasTags() throws Exception {
        // Anki before 2.1.55 wrote no header lines, only tab-separated fields with their HTML.
        Path file = write("old-anki.txt", String.join("\n",
            "abate\t减弱<br>减少\tverb",
            "prodigal\t挥霍的&nbsp;/&nbsp;浪费的\tadjective",
            "candid\t\"坦率的",
            "直言不讳的\"\tadjective",
            "a&b\tA 和 B\tnoun",
            ""));

        ImportPreview preview = service.previewGreCsv(file, deck.getId());
        ImportResult result = service.importGreCsv(file, deck.getId());

        assertEquals("english, chinese, pos (no header row)", preview.columns());
        assertEquals("", preview.format());
        assertEquals(3, result.importedCount(), result.toSummary());
        assertEquals(List.of("Line 5 skipped: English can only contain letters, spaces, hyphens and apostrophes."),
            result.messages(), "a & without an entity is text, so the word stays invalid");
        assertEquals("减弱; 减少", word("abate").getChinese());
        assertEquals("verb", word("abate").getPartOfSpeech());
        assertEquals("挥霍的; 浪费的", word("prodigal").getChinese());
        assertEquals("坦率的; 直言不讳的", word("candid").getChinese());
    }

    @Test
    void commentLinesAtTheTopOfAnyWordListAreSkipped() throws Exception {
        Path file = write("commented.csv", String.join("\n",
            "# GRE list from the red book",
            "# english,chinese",
            "english,chinese",
            "abate,减弱",
            ""));

        ImportPreview preview = service.previewGreCsv(file, deck.getId());
        service.importGreCsv(file, deck.getId());

        assertEquals("english, chinese (header row)", preview.columns());
        assertEquals("", preview.format());
        assertEquals("减弱", word("abate").getChinese());
        assertEquals(1, words.findAll(deck.getId()).size());
    }

    @Test
    void thePreviewListsTheColumnsWithTheirAnkiRolesAndTheFirstRows() throws Exception {
        Path file = write("GRE Verbal.txt", NOTES_EXPORT);

        ImportPreview preview = service.previewGreCsv(file, deck.getId());

        assertEquals(List.of(
                "Column 1 · Anki guid: b:R0zZ1+M#",
                "Column 2 · Anki note type: GRE Word",
                "Column 3 · Anki deck: GRE::Verbal",
                "Column 4: obdurate",
                "Column 5: 顽固的；执拗的; adj.",
                "Column 6: He remained obdurate, r…",
                "Column 7 · Anki tags: gre adjective"),
            preview.fileColumns().stream().map(column -> column.label()).toList());
        assertEquals(3, preview.mapping().index(WordColumn.ENGLISH));
        assertEquals(6, preview.mapping().index(WordColumn.TAGS));
        ImportPreview.Row first = preview.rows().get(0);
        assertEquals(new ImportPreview.Row(7, "obdurate", "顽固的; 执拗的", "adj.", "",
            "He remained obdurate, refusing to apologise.", "", "gre; adjective", "Import"), first);
        assertEquals("Duplicate", preview.rows().get(3).status());
        assertTrue(preview.toSummary().contains("| Anki headers: "), preview.toSummary());
    }

    private Path write(String name, String text) throws Exception {
        Path file = tempDir.resolve(name);
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private WordCard word(String english) throws SQLException {
        return words.findByEnglish(deck.getId(), english).orElseThrow(() -> new AssertionError("No word " + english));
    }
}
