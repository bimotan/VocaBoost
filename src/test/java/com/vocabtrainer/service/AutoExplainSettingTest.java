package com.vocabtrainer.service;

import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.SettingsRepository;
import com.vocabtrainer.repository.TestDatabases;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** "Explain automatically" in the AI settings (review finding G8). */
class AutoExplainSettingTest {
    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    @Test
    void answersAreExplainedOnlyAfterAMistakeUntilAnotherChoiceIsSaved() throws Exception {
        SettingsService settings = settings("auto-explain.db");
        assertEquals(AutoExplain.AFTER_MISTAKE, settings.getAutoExplain());

        settings.saveAutoExplain(AutoExplain.ON_DEMAND);
        assertEquals(AutoExplain.ON_DEMAND, settings.getAutoExplain());
        assertEquals(Optional.of("never"), settings.get(SettingsService.AI_AUTO_EXPLAIN_KEY));

        settings.saveAiSettings("openai-compatible", "https://api.example.com/v1", "sk-test-0123456789abcdef", "m");
        settings.clearAiSettings();
        assertEquals(AutoExplain.ON_DEMAND, settings.getAutoExplain(), "clearing the provider keeps the preference");

        settings.saveAutoExplain(AutoExplain.ALWAYS);
        assertEquals(Optional.of("always"), settings.get(SettingsService.AI_AUTO_EXPLAIN_KEY));
    }

    @Test
    void aSavedValueThatIsNotAChoiceReadsAsTheDefault() throws Exception {
        SettingsService settings = settings("bad.db");
        for (String saved : new String[] {"sometimes", "", " MISTAKES ", "Never"}) {
            settings.save(SettingsService.AI_AUTO_EXPLAIN_KEY, saved);
            AutoExplain expected = saved.equals("Never") ? AutoExplain.ON_DEMAND : AutoExplain.AFTER_MISTAKE;
            assertEquals(expected, settings.getAutoExplain(), saved);
        }
    }

    @Test
    void onlyAMistakeIsExplainedAfterAMistake() {
        assertTrue(AutoExplain.ALWAYS.explainsAutomatically(false));
        assertTrue(AutoExplain.AFTER_MISTAKE.explainsAutomatically(true));
        assertFalse(AutoExplain.AFTER_MISTAKE.explainsAutomatically(false));
        assertFalse(AutoExplain.ON_DEMAND.explainsAutomatically(true));
    }

    private SettingsService settings(String file) throws Exception {
        DatabaseManager databaseManager = databases.open(tempDir.resolve(file));
        return new SettingsService(new SettingsRepository(databaseManager));
    }
}
