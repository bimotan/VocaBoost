package com.vocabtrainer.service;

import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.DeckRepository;
import com.vocabtrainer.repository.TestDatabases;
import com.vocabtrainer.repository.WordRepository;
import com.vocabtrainer.util.ErrorMessages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportExportServiceTest {
    private static final Charset GBK = Charset.forName("GBK");

    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    private Deck deck;
    private WordRepository wordRepository;
    private ImportExportService service;

    @BeforeEach
    void openDatabase() throws SQLException {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("test.db"));
        deck = new DeckRepository(databaseManager).ensureDefaultDeck();
        // Duplicate checks load the deck's words once; a query per row would fail here.
        wordRepository = new WordRepository(databaseManager) {
            @Override
            public Optional<WordCard> findByEnglish(long deckId, String english) {
                throw new AssertionError("The import looked up a word with its own query");
            }
        };
        service = new ImportExportService(wordRepository);
    }

    @Test
    void importsValidLegacyRowsAndSkipsInvalidRows() throws Exception {
        Path legacyFile = tempDir.resolve("legacy.txt");
        Files.writeString(legacyFile, String.join(System.lineSeparator(),
            "rote;死记硬背;2025-06-13 14:02:19;2025-06-13 14:02:19;2.5;0;0",
            "rote;重复单词;2025-06-13 14:02:19;2025-06-13 14:02:19;2.5;0;0",
            "bad-line",
            "aver;断言;bad-date;2025-06-13 14:03:26;2.5;0;0",
            "laud;赞美;2025-06-13 14:02:19;2025-06-13 14:02:19;2.5;x;0"
        ), StandardCharsets.UTF_8);

        ImportResult result = service.importLegacyTxt(legacyFile, deck.getId());

        assertEquals(1, result.importedCount());
        assertEquals(4, result.skippedCount());
        assertTrue(word("rote").isPresent());
        assertEquals(List.of(
            "Line 2 skipped: duplicate word rote",
            "Line 3 skipped: field count is not 7",
            "Line 4 skipped: date format is invalid (Text 'bad-date' could not be parsed at index 0)",
            "Line 5 skipped: review parameters must be numeric (For input string: \"x\")"
        ), result.messages());
    }

    @Test
    void legacyFilesWrittenInGbkImport() throws Exception {
        // The old console app wrote files in the platform charset, GBK on Chinese Windows.
        Path legacyFile = tempDir.resolve("legacy-gbk.txt");
        Files.write(legacyFile, "rote;死记硬背;2025-06-13 14:02:19;2025-06-13 14:02:19;2.5;0;0\n".getBytes(GBK));

        ImportResult result = service.importLegacyTxt(legacyFile, deck.getId());

        assertEquals(1, result.importedCount());
        assertEquals("死记硬背", word("rote").orElseThrow().getChinese());
    }

    @Test
    void importsGreCsvAndSkipsDuplicatesAndBadRows() throws Exception {
        Path csvFile = tempDir.resolve("gre.csv");
        Files.writeString(csvFile, String.join(System.lineSeparator(),
            "english,chinese,pos,example,tags",
            "abate,\"减弱; 减少\",verb,\"The storm began to abate.\",gre",
            "abate,重复,verb,,gre",
            "bad@word,坏词,verb,,gre",
            "lucid,清晰的,adjective,\"The explanation was lucid.\",gre"
        ), StandardCharsets.UTF_8);

        ImportResult result = service.importGreCsv(csvFile, deck.getId());
        ImportPreview preview = service.previewGreCsv(csvFile, deck.getId());

        assertEquals(2, result.importedCount());
        assertEquals(2, result.skippedCount());
        assertEquals(4, preview.totalRows());
        assertEquals(0, preview.importableCount());
        assertEquals(3, preview.duplicateCount());
        assertEquals(1, preview.invalidCount());
        assertTrue(word("ABATE").isPresent());
        assertTrue(word("lucid").isPresent());
    }

    @Test
    void gbkCsvFromChineseExcelImportsWithItsChinese() throws Exception {
        Path csvFile = tempDir.resolve("excel-gbk.csv");
        Files.write(csvFile, "english,chinese,pos\r\nlucid,清晰的；易懂的,adjective\r\nabate,减弱,verb\r\n".getBytes(GBK));

        ImportPreview preview = service.previewGreCsv(csvFile, deck.getId());
        ImportResult result = service.importGreCsv(csvFile, deck.getId());

        assertEquals("GBK/GB18030", preview.encoding());
        assertEquals(2, preview.importableCount());
        assertEquals(2, result.importedCount());
        assertEquals("清晰的; 易懂的", word("lucid").orElseThrow().getChinese());
        assertEquals("adjective", word("lucid").orElseThrow().getPartOfSpeech());
    }

    @Test
    void aByteOrderMarkDoesNotTurnTheHeaderIntoAnInvalidRow() throws Exception {
        Path csvFile = tempDir.resolve("excel-utf8.csv");
        Files.write(csvFile, concat(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF},
            "english,chinese,pos,example,tags\r\nlucid,清晰的,adjective,The talk was lucid.,gre\r\n"
                .getBytes(StandardCharsets.UTF_8)));

        ImportPreview preview = service.previewGreCsv(csvFile, deck.getId());

        assertEquals(1, preview.totalRows());
        assertEquals(1, preview.importableCount());
        assertEquals(0, preview.invalidCount());
        assertEquals(List.of(), preview.firstErrors());
        assertEquals("UTF-8 with BOM", preview.encoding());
        assertEquals("english, chinese, pos, example, tags (header row)", preview.columns());
        service.importGreCsv(csvFile, deck.getId());
        assertEquals("adjective", word("lucid").orElseThrow().getPartOfSpeech());
    }

    @Test
    void lineBreaksInQuotedCellsStayInTheirRowAndErrorsNameThePhysicalLine() throws Exception {
        Path csvFile = tempDir.resolve("multiline.csv");
        Files.writeString(csvFile, """
            english,chinese,pos,example,tags
            alacrity,"欣然
            敏捷",noun,"He agreed with alacrity.",gre
            bad@word,坏词,noun,,gre
            lucid,清晰的,adjective,"Line one
            line two",gre
            """, StandardCharsets.UTF_8);

        ImportResult result = service.importGreCsv(csvFile, deck.getId());

        assertEquals(2, result.importedCount());
        assertEquals(List.of("Line 4 skipped: English can only contain letters, spaces, hyphens and apostrophes."),
            result.messages());
        WordCard alacrity = word("alacrity").orElseThrow();
        // A line break in a meaning cell separates meanings.
        assertEquals("欣然; 敏捷", alacrity.getChinese());
        assertEquals("noun", alacrity.getPartOfSpeech());
        assertEquals("gre", alacrity.getTags());
        assertEquals("Line one line two", word("lucid").orElseThrow().getExampleSentence());
    }

    @Test
    void quotesAndDelimitersInsideCellsSurvive() throws Exception {
        Path csvFile = tempDir.resolve("quotes.csv");
        Files.writeString(csvFile, """
            english,chinese,pos,example
            lucid,"清晰的, 易懂的",adjective,"He said ""lucid"", twice."
            """, StandardCharsets.UTF_8);

        service.importGreCsv(csvFile, deck.getId());

        WordCard lucid = word("lucid").orElseThrow();
        assertEquals("清晰的; 易懂的", lucid.getChinese());
        assertEquals("He said \"lucid\", twice.", lucid.getExampleSentence());
    }

    @Test
    void semicolonAndTabSeparatedFilesImport() throws Exception {
        Path semicolons = tempDir.resolve("semicolons.csv");
        Files.writeString(semicolons, "english;chinese;pos\nlucid;清晰的, 易懂的;adjective\n", StandardCharsets.UTF_8);
        Path tabs = tempDir.resolve("anki.tsv");
        Files.writeString(tabs, "abate\t减弱, 减少\tverb\ncandid\t坦率的\tadjective\n", StandardCharsets.UTF_8);

        ImportPreview tabPreview = service.previewGreCsv(tabs, deck.getId());
        assertEquals(1, service.importGreCsv(semicolons, deck.getId()).importedCount());
        assertEquals(2, service.importGreCsv(tabs, deck.getId()).importedCount());

        assertEquals("tab", tabPreview.delimiter());
        assertEquals("english, chinese, pos (no header row)", tabPreview.columns());
        assertEquals("清晰的; 易懂的", word("lucid").orElseThrow().getChinese());
        assertEquals("adjective", word("lucid").orElseThrow().getPartOfSpeech());
        assertEquals("减弱; 减少", word("abate").orElseThrow().getChinese());
        assertEquals("verb", word("abate").orElseThrow().getPartOfSpeech());
    }

    @Test
    void columnsAreFoundByHeaderNamesInAnyOrder() throws Exception {
        Path csvFile = tempDir.resolve("aliases.csv");
        Files.writeString(csvFile, """
            释义,单词,备注,音标,Part of Speech,Example Sentence,Tags,来源
            清晰的,lucid,常考,/ˈluːsɪd/,adjective,The talk was lucid.,gre,red book
            """, StandardCharsets.UTF_8);

        service.importGreCsv(csvFile, deck.getId());

        WordCard lucid = word("lucid").orElseThrow();
        assertEquals("清晰的", lucid.getChinese());
        assertEquals("常考", lucid.getNote());
        assertEquals("/ˈluːsɪd/", lucid.getPhonetic());
        assertEquals("adjective", lucid.getPartOfSpeech());
        assertEquals("The talk was lucid.", lucid.getExampleSentence());
        assertEquals("gre", lucid.getTags());
    }

    @Test
    void aHeaderWithUnknownNamesIsSkippedInsteadOfImportedAsAWord() throws Exception {
        Path csvFile = tempDir.resolve("own-header.csv");
        Files.writeString(csvFile, "English,Meaning (中文),词性说明\nlucid,清晰的,adjective\n", StandardCharsets.UTF_8);

        ImportPreview preview = service.previewGreCsv(csvFile, deck.getId());
        ImportResult result = service.importGreCsv(csvFile, deck.getId());

        assertEquals(1, preview.totalRows());
        assertEquals("english, chinese, pos (header row line 1 skipped)", preview.columns());
        assertEquals(1, result.importedCount(), result.toSummary());
        assertTrue(word("english").isEmpty());
        assertEquals("清晰的", word("lucid").orElseThrow().getChinese());
        assertEquals("adjective", word("lucid").orElseThrow().getPartOfSpeech());
    }

    @Test
    void aUtf8FileWithOneDamagedByteFailsAtThatLineInsteadOfImportingGarbledMeanings() throws Exception {
        Path csvFile = tempDir.resolve("damaged-utf8.csv");
        Files.write(csvFile, concat(
            "english,chinese,example\nabate,减弱,\nlucid,清晰的,\nrote,死记硬背,\ncandid,坦率的,\nacumen,敏锐,\n"
                .getBytes(StandardCharsets.UTF_8),
            "cafe,咖啡,caf".getBytes(StandardCharsets.UTF_8),
            // A Latin-1 "é" pasted into the file; as GB18030 the whole file would decode without error.
            new byte[] {(byte) 0xE9, 's', '\n'}));

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> service.importGreCsv(csvFile, deck.getId()));

        assertEquals("Cannot read word list " + csvFile + ": Line 7: the text is not valid UTF-8"
            + " (a character on this line is damaged or in another encoding)", error.getMessage());
        assertEquals(List.of(), wordRepository.findAll(deck.getId()));
    }

    @Test
    void aHeaderWithoutAChineseColumnIsRejectedWithTheNamesItAccepts() throws Exception {
        Path csvFile = tempDir.resolve("no-chinese.csv");
        Files.writeString(csvFile, "english,pos\nlucid,adjective\n", StandardCharsets.UTF_8);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> service.importGreCsv(csvFile, deck.getId()));

        assertTrue(error.getMessage().startsWith("The header row (line 1) has no chinese column. Name it one of: "
            + "chinese, translation, meaning"), error.getMessage());
        assertTrue(error.getMessage().contains("释义"), error.getMessage());
    }

    @Test
    void fileErrorsNameTheFileAndTheReasonAndKeepTheCause() throws Exception {
        Path missing = tempDir.resolve("missing.csv");
        IllegalStateException notFound = assertThrows(IllegalStateException.class,
            () -> service.importGreCsv(missing, deck.getId()));
        assertEquals("Cannot read word list " + missing + ": the file does not exist", notFound.getMessage());
        assertInstanceOf(NoSuchFileException.class, notFound.getCause());

        Path broken = tempDir.resolve("broken.csv");
        Files.write(broken, concat("english,chinese\n".getBytes(StandardCharsets.US_ASCII),
            new byte[] {'l', 'u', 'c', 'i', 'd', ',', (byte) 0xFF, '\n'}));
        IllegalStateException undecodable = assertThrows(IllegalStateException.class,
            () -> service.previewGreCsv(broken, deck.getId()));
        assertTrue(undecodable.getMessage().startsWith("Cannot read word list " + broken
            + ": Line 2: the text is not valid GBK/GB18030"), undecodable.getMessage());
        assertInstanceOf(CharacterCodingException.class, undecodable.getCause().getCause(),
            ErrorMessages.causeChain(undecodable));
    }

    @Test
    void aLargeFileIsReadRowByRowAndOnlyTheFirstMessagesAreListed() throws Exception {
        Path csvFile = tempDir.resolve("large.csv");
        int rows = 200_000;
        try (BufferedWriter writer = Files.newBufferedWriter(csvFile, StandardCharsets.UTF_8)) {
            writer.write("english,chinese,pos,example,tags\n");
            for (int i = 0; i < rows; i++) {
                writer.write("abate,\"减弱; 减少\",verb,\"The storm began to abate, at last.\",bulk\n");
            }
        }

        ImportResult result = assertTimeoutPreemptively(Duration.ofSeconds(30),
            () -> service.importGreCsv(csvFile, deck.getId()));

        assertEquals(1, result.importedCount());
        assertEquals(rows - 1, result.skippedCount());
        assertEquals(ImportExportService.MAX_LISTED_MESSAGES + 1, result.messages().size());
        assertEquals("Line 3 skipped: duplicate word abate", result.messages().get(0));
        assertEquals(String.format(Locale.ENGLISH, "... and %,d more rows skipped.", rows - 1 - ImportExportService.MAX_LISTED_MESSAGES),
            result.messages().get(result.messages().size() - 1));
    }

    @Test
    void bundledGreStarterImportsVisibleSampleWords() throws Exception {
        ImportResult result = service.importBundledGreStarter(deck.getId());

        assertEquals(215, result.importedCount());
        assertEquals(0, result.skippedCount());
        WordCard abate = word("abate").orElseThrow();
        assertEquals("减弱; 减少", abate.getChinese());
        assertEquals("gre;starter", abate.getTags());
    }

    private Optional<WordCard> word(String english) throws SQLException {
        return wordRepository.findAll(deck.getId()).stream()
            .filter(word -> word.getEnglish().equalsIgnoreCase(english))
            .findFirst();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
