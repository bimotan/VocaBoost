package com.vocabtrainer.service;

import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.SettingsRepository;
import com.vocabtrainer.repository.TestDatabases;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsRepositoryTest {
    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    @Test
    void savesReadsAndClearsEcdictPath() throws Exception {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("settings.db"));
        SettingsService service = new SettingsService(new SettingsRepository(databaseManager));

        service.saveEcdictPath("  C:/dict/ecdict.csv  ");

        assertEquals("C:/dict/ecdict.csv", service.getEcdictPath().orElseThrow());

        service.clearEcdictPath();

        assertTrue(service.getEcdictPath().isEmpty());
    }

    @Test
    void readsLastDeckIdAndStarterFlag() throws Exception {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("settings.db"));
        SettingsService service = new SettingsService(new SettingsRepository(databaseManager));

        assertTrue(service.getLastDeckId().isEmpty());
        assertFalse(service.isStarterImported());

        service.saveLastDeckId(42);
        service.markStarterImported();
        assertEquals(42L, service.getLastDeckId().orElseThrow());
        assertTrue(service.isStarterImported());

        service.save(SettingsService.LAST_DECK_ID_KEY, "not-a-number");
        assertTrue(service.getLastDeckId().isEmpty());
    }
}
