package com.vocabtrainer.service;

import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.AchievementRepository;
import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.DeckRepository;
import com.vocabtrainer.repository.GoalRepository;
import com.vocabtrainer.repository.ReviewLogRepository;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** "Export words CSV" and "Import word list" agree on the columns, so a deck survives the trip. */
class WordCsvRoundTripTest {
    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    private DeckRepository decks;
    private WordRepository words;
    private BackupService backup;
    private ImportExportService importer;

    @BeforeEach
    void openDatabase() throws SQLException {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("round-trip.db"));
        decks = new DeckRepository(databaseManager);
        words = new WordRepository(databaseManager);
        backup = new BackupService(decks, words, new ReviewLogRepository(databaseManager),
            new GoalRepository(databaseManager), new AchievementRepository(databaseManager), databaseManager,
            new WordValidationService());
        importer = new ImportExportService(words);
    }

    @Test
    void exportedWordsImportIntoAnotherDeckWithEveryField() throws Exception {
        Deck source = decks.ensureDefaultDeck();
        save(source, "lucid", "清晰的; 易懂的", "/ˈluːsɪd/", "adjective", "He said \"lucid\", twice.",
            "常考, 见红宝书", "gre; core");
        save(source, "abate", "减弱", "", "verb", "", "", "");
        save(source, "rock 'n' roll", "摇滚乐", "", "noun", "Rock 'n' roll, all night.", "", "music");
        Deck target = decks.create("Copy");

        Path csv = backup.exportWordsCsv(source.getId(), tempDir.resolve("words.csv"));
        ImportResult result = importer.importGreCsv(csv, target.getId());

        assertEquals(3, result.importedCount(), result.toSummary());
        assertEquals(0, result.skippedCount(), result.toSummary());
        assertEquals(fields(source), fields(target));
    }

    @Test
    void formulaCellsAreNeutralizedInTheFileAndRestoredOnImport() throws Exception {
        Deck source = decks.ensureDefaultDeck();
        save(source, "ness", "名词后缀", "", "-suffix", "=HYPERLINK(\"http://x\",\"点击\")",
            "+1 more", "@home; '-quoted");
        Deck target = decks.create("Copy");

        Path csv = backup.exportWordsCsv(source.getId(), tempDir.resolve("words.csv"));

        String text = Files.readString(csv, StandardCharsets.UTF_8);
        assertTrue(text.startsWith("\uFEFFenglish,chinese,phonetic,pos,example,note,tags\r\n"), text);
        assertTrue(text.contains("ness,名词后缀,,'-suffix,\"'=HYPERLINK(\"\"http://x\"\",\"\"点击\"\")\",'+1 more,'@home; '-quoted\r\n"),
            text);
        importer.importGreCsv(csv, target.getId());
        assertEquals(fields(source), fields(target));
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

    private List<List<String>> fields(Deck deck) throws SQLException {
        return words.findAll(deck.getId()).stream()
            .map(word -> List.of(word.getEnglish(), word.getChinese(), text(word.getPhonetic()),
                text(word.getPartOfSpeech()), text(word.getExampleSentence()), text(word.getNote()), text(word.getTags())))
            .toList();
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
