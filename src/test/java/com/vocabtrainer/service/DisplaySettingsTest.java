package com.vocabtrainer.service;

import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.SettingsRepository;
import com.vocabtrainer.repository.TestDatabases;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DisplaySettingsTest {
    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    @Test
    void theTextSizeIs100UntilSavedAndOnlyTheOfferedSizesAreSaved() throws Exception {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("display.db"));
        SettingsService settings = new SettingsService(new SettingsRepository(databaseManager));
        DisplaySettings display = new DisplaySettings(settings);
        assertEquals(100, display.textSizePercent());

        display.saveTextSizePercent(130);
        assertEquals(130, new DisplaySettings(settings).textSizePercent());
        assertEquals("130", settings.get(DisplaySettings.TEXT_SIZE_KEY).orElseThrow());

        assertThrows(IllegalArgumentException.class, () -> display.saveTextSizePercent(120));
        assertEquals(130, display.textSizePercent());
    }

    @Test
    void aSavedSizeThatIsNotOfferedShowsTextAt100() throws Exception {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("bad.db"));
        SettingsService settings = new SettingsService(new SettingsRepository(databaseManager));
        for (String saved : new String[] {"200", "big", " 115 "}) {
            settings.save(DisplaySettings.TEXT_SIZE_KEY, saved);
            assertEquals(saved.equals(" 115 ") ? 115 : 100, new DisplaySettings(settings).textSizePercent(), saved);
        }
    }

    @Test
    void aSettingThatCannotBeReadShowsTextAt100() throws Exception {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("closed.db"));
        SettingsService settings = new SettingsService(new SettingsRepository(databaseManager));
        settings.save(DisplaySettings.TEXT_SIZE_KEY, "130");
        databaseManager.close();
        assertEquals(100, new DisplaySettings(settings).textSizePercent());
    }
}
