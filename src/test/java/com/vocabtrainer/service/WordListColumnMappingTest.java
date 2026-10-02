package com.vocabtrainer.service;

import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.DeckRepository;
import com.vocabtrainer.repository.TestDatabases;
import com.vocabtrainer.repository.WordRepository;
import com.vocabtrainer.service.csv.WordColumn;
import com.vocabtrainer.service.csv.WordColumns;
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
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The columns of a word list are detected from its content, and the user can choose others. */
class WordListColumnMappingTest {
    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    private Deck deck;
    private WordRepository words;
    private ImportExportService service;

    @BeforeEach
    void openDatabase() throws SQLException {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("mapping.db"));
        deck = new DeckRepository(databaseManager).ensureDefaultDeck();
        words = new WordRepository(databaseManager);
        service = new ImportExportService(words);
    }

    @Test
    void columnsWithoutAHeaderAreDetectedFromWhatTheyHold() throws Exception {
        Path file = write("reversed.tsv", String.join("\n",
            "减弱\tabate\t/əˈbeɪt/\tv.\tThe storm began to abate.",
            "清晰的\tlucid\t/ˈluːsɪd/\tadj.\tHis talk was lucid.",
            "顽固的\tobdurate\t[ˈɒbdjʊrət]\tadj.\tShe stayed obdurate.",
            ""));

        ImportPreview preview = service.previewGreCsv(file, deck.getId());
        service.importGreCsv(file, deck.getId());

        assertEquals("chinese, english, phonetic, pos, example (no header row)", preview.columns());
        WordCard abate = word("abate");
        assertEquals("减弱", abate.getChinese());
        assertEquals("/əˈbeɪt/", abate.getPhonetic());
        assertEquals("v.", abate.getPartOfSpeech());
        assertEquals("The storm began to abate.", abate.getExampleSentence());
    }

    @Test
    void theChosenColumnsReplaceTheDetectedOnesInThePreviewAndTheImport() throws Exception {
        Path file = write("reversed.tsv", String.join("\n",
            "减弱\tabate\t/əˈbeɪt/\tv.\tThe storm began to abate.",
            "清晰的\tlucid\t/ˈluːsɪd/\tadj.\tHis talk was lucid.",
            ""));
        WordColumns chosen = WordColumns.none()
            .with(WordColumn.ENGLISH, 1)
            .with(WordColumn.CHINESE, 0)
            .with(WordColumn.NOTE, 4)
            .with(WordColumn.TAGS, 3);
        WordListOptions options = new WordListOptions(chosen, false);

        ImportPreview preview = service.previewWordList(file, deck.getId(), options);
        ImportResult result = service.importWordList(file, deck.getId(), options, progress -> { }, () -> false);

        assertEquals("chinese, english, tags, note (chosen)", preview.columns());
        assertEquals(chosen, preview.mapping());
        assertEquals(new ImportPreview.Row(1, "abate", "减弱", "", "", "", "The storm began to abate.", "v.", "Import"),
            preview.rows().get(0));
        assertEquals(2, result.importedCount(), result.toSummary());
        WordCard abate = word("abate");
        assertEquals("The storm began to abate.", abate.getNote());
        assertEquals("", orEmpty(abate.getExampleSentence()));
        assertEquals("", orEmpty(abate.getPhonetic()));
        assertEquals("v.", abate.getTags());
    }

    @Test
    void chosenColumnsOverrideAHeaderRowWhichIsStillSkipped() throws Exception {
        Path file = write("header.csv", "english,chinese,note\nabate,减弱,The storm began to abate.\n");
        WordColumns chosen = WordColumns.none()
            .with(WordColumn.ENGLISH, 0)
            .with(WordColumn.CHINESE, 1)
            .with(WordColumn.EXAMPLE, 2);

        ImportResult result = service.importWordList(file, deck.getId(), new WordListOptions(chosen, false),
            progress -> { }, () -> false);

        assertEquals(1, result.importedCount(), result.toSummary());
        assertEquals("The storm began to abate.", word("abate").getExampleSentence());
        assertEquals("", orEmpty(word("abate").getNote()));
    }

    @Test
    void anImportNeedsAnEnglishColumn() throws Exception {
        Path file = write("words.csv", "abate,减弱\n");
        WordColumns chosen = WordColumns.none().with(WordColumn.CHINESE, 1);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> service.previewWordList(file, deck.getId(), new WordListOptions(chosen, false)));

        assertEquals("Choose the column that holds the English word.", error.getMessage());
        assertEquals(List.of(), words.findAll(deck.getId()));
    }

    @Test
    void aColumnBeyondARowsEndIsEmpty() throws Exception {
        Path file = write("ragged.csv", "abate,减弱,verb\nlucid,清晰的\n");

        service.importGreCsv(file, deck.getId());

        assertEquals("verb", word("abate").getPartOfSpeech());
        assertEquals("", orEmpty(word("lucid").getPartOfSpeech()));
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
