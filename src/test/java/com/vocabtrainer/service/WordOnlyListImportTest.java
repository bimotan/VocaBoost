package com.vocabtrainer.service;

import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.DictionaryLookupResult;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.DeckRepository;
import com.vocabtrainer.repository.EcdictRepository;
import com.vocabtrainer.repository.TestDatabases;
import com.vocabtrainer.repository.WordRepository;
import com.vocabtrainer.service.csv.WordColumn;
import com.vocabtrainer.service.ecdict.EcdictFixtures;
import com.vocabtrainer.service.ecdict.EcdictImportService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A list of English words imports with meanings from the local dictionaries: an imported ECDICT and the starter words. */
class WordOnlyListImportTest {
    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    private Deck deck;
    private WordRepository words;
    private EcdictRepository ecdict;
    private ImportExportService service;
    private final AtomicInteger onlineChainsBuilt = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("words.db"));
        deck = new DeckRepository(databaseManager).ensureDefaultDeck();
        words = new WordRepository(databaseManager);
        Path csv = EcdictFixtures.write(tempDir.resolve("ecdict.csv"), false, List.of(
            EcdictFixtures.ABANDON, EcdictFixtures.HOOD,
            "lucid,ˈluːsɪd,,\"a. 清楚的, 透明的\\n[医] 清醒的\",,,,,0,0,,,"));
        ecdict = new EcdictRepository(tempDir.resolve("ecdict.db"));
        new EcdictImportService(ecdict).importCsv(csv, progress -> { }, () -> false);
        service = new ImportExportService(words, new WordValidationService(), new LocalDictionaryService(ecdict),
            () -> {
                onlineChainsBuilt.incrementAndGet();
                throw new AssertionError("The import asked for the online dictionaries");
            },
            () -> false);
    }

    @AfterEach
    void closeDictionary() {
        ecdict.close();
    }

    @Test
    void aWordPerLineGetsTheMeaningPhoneticAndPartOfSpeechOfTheLocalDictionary() throws Exception {
        Path file = write("list.txt", "abandon\r\nabate\r\nabandons\r\nzzyzx\r\n\r\nABANDON\r\nlucid\r\n");
        List<ImportProgress> progress = new ArrayList<>();

        ImportResult result = service.importWordList(file, deck.getId(), WordListOptions.DETECT, progress::add,
            () -> false);

        assertEquals(4, result.importedCount(), result.toSummary());
        assertEquals(2, result.skippedCount());
        assertEquals(4, result.meaningsFilled());
        assertEquals(List.of(
                "Line 6 skipped: duplicate word ABANDON",
                "Line 4 skipped: zzyzx is not in the local dictionary, so it has no meaning to import"),
            result.messages());
        assertTrue(result.toSummary().startsWith("Imported 4, skipped 2. Meanings from the local dictionary: 4."),
            result.toSummary());
        assertEquals(List.of(new ImportProgress(1, 5), new ImportProgress(2, 5), new ImportProgress(3, 5),
            new ImportProgress(4, 5), new ImportProgress(5, 5)), progress);

        WordCard abandon = word("abandon");
        assertEquals("放弃; 抛弃; 遗弃; 使屈从; 沉溺; 放纵; 放任; 无拘束; 狂热", abandon.getChinese());
        assertEquals("ә'bændәn", abandon.getPhonetic());
        assertEquals("verb; noun", abandon.getPartOfSpeech());
        assertEquals("Meaning from " + LocalDictionaryService.ECDICT_SOURCE + ".", abandon.getNote());
        WordCard abate = word("abate");
        assertEquals("减弱; 减少", abate.getChinese(), "the starter words answer after ECDICT");
        assertEquals("verb", abate.getPartOfSpeech());
        assertTrue(abate.getNote().endsWith("Meaning from " + LocalDictionaryService.STARTER_SOURCE + "."),
            abate.getNote());
        WordCard abandons = word("abandons");
        assertEquals(abandon.getChinese(), abandons.getChinese());
        assertEquals("Meaning of its base form abandon. Meaning from " + LocalDictionaryService.ECDICT_SOURCE + ".",
            abandons.getNote());
        WordCard lucid = word("lucid");
        assertEquals("清楚的; 透明的", lucid.getChinese());
        assertEquals("[医] 清醒的 Meaning from " + LocalDictionaryService.ECDICT_SOURCE + ".", lucid.getNote(),
            "ECDICT's specialist senses go to the note, as on the add form");
        assertEquals(0, onlineChainsBuilt.get());
    }

    @Test
    void theFilesOwnFieldsWinOverTheDictionarysAndOnlyMissingMeaningsAreLookedUp() throws Exception {
        Path file = write("partial.csv", String.join("\n",
            "english,chinese,pos,tags",
            "lucid,,adjective,mine",
            "abate,减轻,,mine",
            ""));

        ImportPreview preview = service.previewGreCsv(file, deck.getId());
        ImportResult result = service.importGreCsv(file, deck.getId());

        assertEquals(1, preview.importableCount());
        assertEquals(1, preview.needMeaningCount());
        assertTrue(preview.toSummary().startsWith("Rows: 2, importable: 1, meanings to look up: 1, duplicates: 0"),
            preview.toSummary());
        assertEquals(1, result.meaningsFilled());
        assertEquals("清楚的; 透明的", word("lucid").getChinese());
        assertEquals("adjective", word("lucid").getPartOfSpeech());
        assertEquals("ˈluːsɪd", word("lucid").getPhonetic());
        assertEquals("mine", word("lucid").getTags());
        assertEquals("减轻", word("abate").getChinese(), "a meaning in the file is never replaced");
    }

    @Test
    void aHeaderWithoutAChineseColumnIsAWordList() throws Exception {
        Path file = write("header.csv", "word,tags\nabandon,gre\nabate,gre\n");

        ImportPreview preview = service.previewGreCsv(file, deck.getId());
        ImportResult result = service.importGreCsv(file, deck.getId());

        assertEquals("english, tags (header row)", preview.columns());
        assertEquals(0, preview.importableCount());
        assertEquals(2, preview.needMeaningCount());
        assertEquals(2, result.importedCount(), result.toSummary());
        assertEquals("gre", word("abate").getTags());
    }

    @Test
    void thePreviewShowsWhatTheLocalDictionaryHasForTheFirstRows() throws Exception {
        Path file = write("list.txt", "abandon\nzzyzx\nabate\n");

        ImportPreview preview = service.previewGreCsv(file, deck.getId());

        assertEquals("english (no header row)", preview.columns());
        assertEquals(List.of(
                "Meaning from " + LocalDictionaryService.ECDICT_SOURCE,
                "Not in the local dictionary: skipped",
                "Meaning from " + LocalDictionaryService.STARTER_SOURCE),
            preview.rows().stream().map(ImportPreview.Row::status).toList());
        assertEquals("减弱; 减少", preview.rows().get(2).chinese());
        assertEquals(List.of(), words.findAll(deck.getId()), "a preview writes nothing");
    }

    @Test
    void aCanceledImportStopsBeforeItsNextLookupAndImportsNothing() throws Exception {
        Path file = write("list.txt", "lucid\nabandon\nabate\n");
        List<ImportProgress> progress = new ArrayList<>();

        assertThrows(CancellationException.class, () -> service.importWordList(file, deck.getId(),
            WordListOptions.DETECT, progress::add, () -> progress.size() >= 2));

        assertEquals(2, progress.size());
        assertEquals(List.of(), words.findAll(deck.getId()));
    }

    @Test
    void withoutADictionaryAWordListIsRejectedOrItsRowsSkippedAsBefore() throws Exception {
        ImportExportService withoutDictionary = new ImportExportService(words);
        Path list = write("list.txt", "abandon\nabate\n");
        Path header = write("header.csv", "english,pos\nlucid,adjective\n");

        ImportResult result = withoutDictionary.importGreCsv(list, deck.getId());

        assertEquals(0, result.importedCount());
        assertEquals(List.of("Line 1 skipped: Chinese meaning cannot be empty.",
            "Line 2 skipped: Chinese meaning cannot be empty."), result.messages());
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> withoutDictionary.importGreCsv(header, deck.getId()));
        assertTrue(error.getMessage().startsWith("The header row (line 1) has no chinese column."), error.getMessage());
    }

    @Test
    void aWordWithAnInvalidSpellingIsSkippedBeforeAnyLookup() throws Exception {
        Path file = write("list.txt", "abandon\nbad@word\n");
        List<ImportProgress> progress = new ArrayList<>();

        ImportResult result = service.importWordList(file, deck.getId(), WordListOptions.DETECT, progress::add,
            () -> false);

        assertEquals(1, result.importedCount());
        assertEquals(List.of("Line 2 skipped: English can only contain letters, spaces, hyphens and apostrophes."),
            result.messages());
        assertEquals(1, progress.size());
        assertEquals(WordColumn.ENGLISH, service.previewGreCsv(file, deck.getId()).mapping().fieldAt(0).orElseThrow());
    }

    @Test
    void aListWhoseFirstWordNamesAnotherFieldIsStillAListOfWords() throws Exception {
        Path file = write("list.txt", "definition\nabandon\nabate\n");

        ImportPreview preview = service.previewGreCsv(file, deck.getId());
        ImportResult result = service.importGreCsv(file, deck.getId());

        assertEquals("english (no header row)", preview.columns());
        assertEquals(2, result.importedCount(), result.toSummary());
        assertEquals(List.of("Line 1 skipped: definition is not in the local dictionary, so it has no meaning to import"),
            result.messages());
    }

    @Test
    void aWordAddedToTheDeckDuringTheLookupsIsSkippedInsteadOfFailingTheImport() throws Exception {
        Path file = write("list.txt", "abandon\nabate\nlucid\n");
        LocalDictionaryService local = new LocalDictionaryService(ecdict);
        // The user adds "abate" on the add form while the import looks up "abandon".
        DictionaryService addsAbate = new DictionaryService() {
            @Override
            public DictionaryLookupResult lookup(String english) {
                if (english.equals("abandon")) {
                    try {
                        words.insert(WordCard.createNew(deck.getId(), "Abate", "减轻"));
                    } catch (SQLException e) {
                        throw new IllegalStateException(e);
                    }
                }
                return local.lookup(english);
            }

            @Override
            public boolean isConfigured() {
                return true;
            }
        };
        ImportExportService racing = new ImportExportService(words, new WordValidationService(), addsAbate, null,
            () -> true);

        ImportResult result = racing.importWordList(file, deck.getId(), WordListOptions.DETECT, progress -> { },
            () -> false);

        assertEquals(2, result.importedCount(), result.toSummary());
        assertEquals(2, result.meaningsFilled());
        assertEquals(List.of("Line 2 skipped: duplicate word abate (added to the deck during the import)"),
            result.messages());
        assertEquals("减轻", word("abate").getChinese(), "the word the user added stays");
        assertEquals(3, words.findAll(deck.getId()).size());
    }

    private Path write(String name, String text) throws Exception {
        Path file = tempDir.resolve(name);
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file;
    }

    private WordCard word(String english) throws SQLException {
        return words.findByEnglish(deck.getId(), english).orElseThrow(() -> new AssertionError("No word " + english));
    }
}
