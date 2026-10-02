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

/** The legacy text import keeps the SM-2 progress of its rows as FSRS card state. */
class LegacyTextImportProgressTest {
    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

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
}
