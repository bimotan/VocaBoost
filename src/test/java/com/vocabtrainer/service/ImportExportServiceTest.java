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
        assertTrue(result.messages().stream().anyMatch(message -> message.contains("字段数量")));
        assertTrue(result.messages().stream().anyMatch(message -> message.contains("日期格式")));
    }
}