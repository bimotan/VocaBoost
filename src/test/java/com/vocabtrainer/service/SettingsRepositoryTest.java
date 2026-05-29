package com.vocabtrainer.service;

import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.SettingsRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsRepositoryTest {
    @TempDir
    Path tempDir;

    @Test
    void savesReadsAndClearsEcdictPath() throws Exception {
        DatabaseManager databaseManager = new DatabaseManager(tempDir.resolve("settings.db"));
        databaseManager.initialize();
        SettingsService service = new SettingsService(new SettingsRepository(databaseManager));

        service.saveEcdictPath("  C:/dict/ecdict.csv  ");

        assertEquals("C:/dict/ecdict.csv", service.getEcdictPath().orElseThrow());

        service.clearEcdictPath();

        assertTrue(service.getEcdictPath().isEmpty());
    }
}
