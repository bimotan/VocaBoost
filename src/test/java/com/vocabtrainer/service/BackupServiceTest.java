package com.vocabtrainer.service;

import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.ReviewLog;
import com.vocabtrainer.domain.ReviewRating;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.DeckRepository;
import com.vocabtrainer.repository.ReviewLogRepository;
import com.vocabtrainer.repository.WordRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void exportsCsvAndJsonBackupAndImportsIt() throws Exception {
        DatabaseManager databaseManager = new DatabaseManager(tempDir.resolve("backup.db"));
        databaseManager.initialize();
        DeckRepository deckRepository = new DeckRepository(databaseManager);
        WordRepository wordRepository = new WordRepository(databaseManager);
        ReviewLogRepository reviewLogRepository = new ReviewLogRepository(databaseManager);
        Deck deck = deckRepository.ensureDefaultDeck();
        Deck restoredDeck = deckRepository.create("Restored");
        WordCard word = WordCard.createNew(deck.getId(), "lucid", "清晰的");
        word.setTags("backup");
        wordRepository.save(word);
        reviewLogRepository.insert(new ReviewLog(0, word.getId(), LocalDateTime.now(), "清晰的",
            "清晰的", 1.0, ReviewRating.EASY, 900));
        BackupService backupService = new BackupService(wordRepository, reviewLogRepository, databaseManager,
            new WordValidationService());

        Path wordsCsv = backupService.exportWordsCsv(deck.getId(), tempDir.resolve("words.csv"));
        Path logsCsv = backupService.exportReviewLogsCsv(deck.getId(), tempDir.resolve("logs.csv"));
        Path json = backupService.exportJsonBackup(deck.getId(), tempDir.resolve("backup.json"));
        ImportResult result = backupService.importJsonBackup(json, restoredDeck.getId());

        assertTrue(Files.readString(wordsCsv, StandardCharsets.UTF_8).contains("lucid"));
        assertTrue(Files.readString(logsCsv, StandardCharsets.UTF_8).contains("EASY"));
        assertTrue(Files.readString(json, StandardCharsets.UTF_8).contains("\"reviewLogs\""));
        assertEquals(1, result.importedCount());
        assertTrue(wordRepository.findByEnglish(restoredDeck.getId(), "lucid").isPresent());
    }
}
