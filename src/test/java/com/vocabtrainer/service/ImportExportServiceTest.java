package com.vocabtrainer.service;

import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.DeckRepository;
import com.vocabtrainer.repository.WordRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportExportServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void importsValidLegacyRowsAndSkipsInvalidRows() throws Exception {
        DatabaseManager databaseManager = new DatabaseManager(tempDir.resolve("test.db"));
        databaseManager.initialize();
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
    void importsGreCsvAndSkipsDuplicatesAndBadRows() throws Exception {
        DatabaseManager databaseManager = new DatabaseManager(tempDir.resolve("test.db"));
        databaseManager.initialize();
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
        DatabaseManager databaseManager = new DatabaseManager(tempDir.resolve("starter.db"));
        databaseManager.initialize();
        Deck deck = new DeckRepository(databaseManager).ensureDefaultDeck();
        WordRepository wordRepository = new WordRepository(databaseManager);
        ImportExportService service = new ImportExportService(wordRepository);

        ImportResult result = service.importBundledGreStarter(deck.getId());

        assertTrue(result.importedCount() >= 200);
        assertTrue(result.importedCount() <= 2000);
        assertTrue(wordRepository.findByEnglish(deck.getId(), "abate").isPresent());
    }
}
