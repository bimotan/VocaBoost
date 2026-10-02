package com.vocabtrainer.service;

import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.DeckRepository;
import com.vocabtrainer.repository.TestDatabases;
import com.vocabtrainer.repository.WordRepository;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** "Export for Anki" writes a file Anki reads, and importing it back gives the same words and fields. */
class AnkiExportRoundTripTest {
    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    private DeckRepository decks;
    private WordRepository words;
    private ImportExportService service;

    @BeforeEach
    void openDatabase() throws SQLException {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("anki-export.db"));
        decks = new DeckRepository(databaseManager);
        words = new WordRepository(databaseManager);
        service = new ImportExportService(words, new WordValidationService(), new LocalDictionaryService(), null,
            () -> true);
    }

    @Test
    void theExportHasAnkisHeaderLinesAndHtmlFields() throws Exception {
        Deck deck = decks.ensureDefaultDeck();
        save(deck, "abate", "减弱; 减少", "/əˈbeɪt/", "verb", "The storm began to <abate> & calm.", "", "gre; core words");
        save(deck, "lucid", "清晰的", "", "", "", "", "");

        Path file = service.exportForAnki(deck.getId(), tempDir.resolve("out").resolve("anki.txt"));

        byte[] bytes = Files.readAllBytes(file);
        assertFalse(bytes.length >= 3 && bytes[0] == (byte) 0xEF, "no byte order mark before Anki's header lines");
        assertEquals(String.join("\n",
                "#separator:tab",
                "#html:true",
                "#columns:English\tChinese\tExample\tTags",
                "#tags column:4",
                "abate\t减弱; 减少<br><span class='pos'>verb</span> <span class='phonetic'>/əˈbeɪt/</span>"
                    + "\tThe storm began to &lt;abate&gt; &amp; calm.\tgre core_words",
                "lucid\t清晰的\t\t",
                ""),
            new String(bytes, StandardCharsets.UTF_8));
    }

    @Test
    void anExportedDeckImportsBackWithTheSameWordsAndFields() throws Exception {
        Deck source = decks.ensureDefaultDeck();
        save(source, "abate", "减弱; 减少", "/əˈbeɪt/", "verb", "The storm began to abate.", "", "gre; core");
        save(source, "lucid", "清晰的; 易懂的", "[ˈluːsɪd]", "adj.", "He said \"lucid\", twice.", "", "gre");
        save(source, "rock 'n' roll", "摇滚乐", "", "noun", "Rock 'n' roll & <blues>, all night.", "", "");
        save(source, "ness", "名词后缀 (表示性质)", "", "-suffix", "=SUM(1)", "", "@home");
        Deck target = decks.create("From Anki");

        Path file = service.exportForAnki(source.getId(), tempDir.resolve("anki.txt"));
        ImportPreview preview = service.previewGreCsv(file, target.getId());
        ImportResult result = service.importGreCsv(file, target.getId());

        assertEquals("english, chinese, example, tags (Anki #columns)", preview.columns());
        assertEquals(0, result.skippedCount(), result.toSummary());
        assertEquals(4, result.importedCount(), result.toSummary());
        assertEquals(0, result.meaningsFilled());
        assertEquals(fields(source), fields(target));
    }

    @Test
    void aWordListExportIsOneWordPerLineAndImportsBackWithLocalMeanings() throws Exception {
        Deck source = decks.ensureDefaultDeck();
        save(source, "abate", "减弱; 减少", "", "verb", "", "", "");
        save(source, "lucid", "清晰的; 易懂的", "", "adjective", "", "", "");
        save(source, "petrichor", "雨后泥土的气味", "", "noun", "", "", "");
        Deck target = decks.create("From the list");

        Path file = service.exportWordList(source.getId(), tempDir.resolve("words.txt"));
        ImportResult result = service.importGreCsv(file, target.getId());

        assertEquals("abate\r\nlucid\r\npetrichor\r\n", Files.readString(file, StandardCharsets.UTF_8));
        assertEquals(2, result.importedCount(), result.toSummary());
        assertEquals(2, result.meaningsFilled());
        assertEquals(List.of("Line 3 skipped: petrichor is not in the local dictionary, so it has no meaning to import"),
            result.messages());
        WordCard abate = words.findByEnglish(target.getId(), "abate").orElseThrow();
        assertEquals("减弱; 减少", abate.getChinese(), "the starter words know abate");
        assertTrue(abate.getNote().startsWith("Meaning from "), abate.getNote());
    }

    private void save(Deck deck, String english, String chinese, String phonetic, String pos, String example,
                      String note, String tags) throws SQLException {
        WordCard word = WordCard.createNew(deck.getId(), english, chinese);
        word.setPhonetic(phonetic);
        word.setPartOfSpeech(pos);
        word.setExampleSentence(example);
        word.setNote(note);
        word.setTags(tags);
        words.save(word);
    }

    /** What survives the trip: everything but the note, which the four Anki columns do not carry. */
    private List<List<String>> fields(Deck deck) throws SQLException {
        return words.findAll(deck.getId()).stream()
            .map(word -> List.of(word.getEnglish(), word.getChinese(), text(word.getPhonetic()),
                text(word.getPartOfSpeech()), text(word.getExampleSentence()), text(word.getTags())))
            .toList();
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
