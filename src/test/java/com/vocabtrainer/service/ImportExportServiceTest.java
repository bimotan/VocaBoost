package com.vocabtrainer.service;

import com.vocabtrainer.domain.CardState;
import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.DeckRepository;
import com.vocabtrainer.repository.TestDatabases;
import com.vocabtrainer.repository.WordRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportExportServiceTest {
    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    @Test
    void importsValidLegacyRowsAndSkipsInvalidRows() throws Exception {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("test.db"));
        Deck deck = new DeckRepository(databaseManager).ensureDefaultDeck();
        WordRepository wordRepository = new WordRepository(databaseManager);
        ImportExportService service = new ImportExportService(wordRepository);

        Path legacyFile = tempDir.resolve("legacy.txt");
        Files.writeString(legacyFile, String.join(System.lineSeparator(),
            "rote;死记硬背;2025-06-13 14:02:19;2025-06-13 14:02:19;2.5;0;0",
            "rote;重复单词;2025-06-13 14:02:19;2025-06-13 14:02:19;2.5;0;0",
            "bad-line",
            "aver;断言;bad-date;2025-06-13 14:03:26;2.5;0;0"
        ), StandardCharsets.UTF_8);

        ImportResult result = service.importLegacyTxt(legacyFile, deck.getId());

        assertEquals(1, result.importedCount());
        assertEquals(3, result.skippedCount());
        assertTrue(wordRepository.findByEnglish(deck.getId(), "rote").isPresent());
        assertTrue(result.messages().stream().anyMatch(message -> message.contains("field count")));
        assertTrue(result.messages().stream().anyMatch(message -> message.contains("date format")));
    }

    @Test
    void legacyRowsKeepTheirReviewProgress() throws Exception {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("test.db"));
        Deck deck = new DeckRepository(databaseManager).ensureDefaultDeck();
        WordRepository wordRepository = new WordRepository(databaseManager);
        ImportExportService service = new ImportExportService(wordRepository);

        Path legacyFile = tempDir.resolve("legacy.txt");
        Files.writeString(legacyFile, String.join(System.lineSeparator(),
            "rote;死记硬背;2025-06-13 14:02:19;2025-09-20 14:02:19;1.3;30;4",
            "aver;断言;2025-06-13 14:03:26;2025-06-13 14:03:26;2.5;0;0"
        ), StandardCharsets.UTF_8);

        assertEquals(2, service.importLegacyTxt(legacyFile, deck.getId()).importedCount());

        // A reviewed row is in review, its memory estimated from the SM-2 schedule like a migrated word.
        WordCard rote = wordRepository.findByEnglish(deck.getId(), "rote").orElseThrow();
        assertEquals(CardState.REVIEW, rote.getState());
        assertEquals(30.0, rote.getStability(), 1e-9);
        assertEquals(9.0, rote.getDifficulty(), 1e-9);
        assertEquals(LocalDateTime.of(2025, 10, 20, 14, 2, 19), rote.getNextReviewAt());
        assertTrue(rote.isMastered());
        // A row without progress is a new word.
        assertEquals(CardState.NEW, wordRepository.findByEnglish(deck.getId(), "aver").orElseThrow().getState());
    }

    @Test
    void importsGreCsvAndSkipsDuplicatesAndBadRows() throws Exception {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("test.db"));
        Deck deck = new DeckRepository(databaseManager).ensureDefaultDeck();
        WordRepository wordRepository = new WordRepository(databaseManager);
        ImportExportService service = new ImportExportService(wordRepository);

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
        assertTrue(preview.duplicateCount() >= 2);
        assertTrue(wordRepository.findByEnglish(deck.getId(), "ABATE").isPresent());
        assertTrue(wordRepository.findByEnglish(deck.getId(), "lucid").isPresent());
    }

    @Test
    void bundledGreStarterImportsVisibleSampleWords() throws Exception {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("starter.db"));
        Deck deck = new DeckRepository(databaseManager).ensureDefaultDeck();
        WordRepository wordRepository = new WordRepository(databaseManager);
        ImportExportService service = new ImportExportService(wordRepository);

        ImportResult result = service.importBundledGreStarter(deck.getId());

        assertTrue(result.importedCount() >= 200);
        assertTrue(result.importedCount() <= 2000);
        assertTrue(wordRepository.findByEnglish(deck.getId(), "abate").isPresent());
    }
}
