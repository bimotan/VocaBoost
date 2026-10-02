package com.vocabtrainer.service;

import com.vocabtrainer.repository.SettingsRepository;
import com.vocabtrainer.repository.TestDatabases;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The optional AI temperature: a plain number from 0 to 2. */
class AiTemperatureTest {
    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    @ParameterizedTest
    @CsvSource({"0, 0.0", "-0, 0.0", "-0.0, 0.0", "0.7, 0.7", "' 1 ', 1.0", "2, 2.0", "2.000, 2.0", "1e0, 1.0"})
    void aPlainNumberFromZeroToTwoIsAccepted(String text, double expected) {
        Optional<Double> parsed = SettingsService.parseTemperature(text);
        assertEquals(Optional.of(expected), parsed);
        assertEquals(String.valueOf(expected), String.valueOf(parsed.orElseThrow()), "no negative zero");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "warm", "NaN", "Infinity", "1d", "0.5f", "0x1p0", "-0.1", "2.0000001", "3"})
    void anythingElseIsRefused(String text) {
        assertEquals(Optional.empty(), SettingsService.parseTemperature(text));
    }

    @Test
    void theTemperatureIsSavedAsTheNumberThatIsSent() throws Exception {
        SettingsService settings = new SettingsService(new SettingsRepository(databases.open(tempDir.resolve("t.db"))));

        settings.saveAiSettings("openai-compatible", "https://api.example.com/v1", "sk-test-0123456789", "m", "-0");
        assertEquals("0.0", settings.get(SettingsService.AI_TEMPERATURE_KEY).orElseThrow());

        assertThrows(IllegalArgumentException.class, () ->
            settings.saveAiSettings("openai-compatible", "https://api.example.com/v1", "", "m", "1d"));
        assertEquals("0.0", settings.get(SettingsService.AI_TEMPERATURE_KEY).orElseThrow());

        settings.saveAiSettings("openai-compatible", "https://api.example.com/v1", "", "m", "");
        assertEquals(Optional.empty(), settings.get(SettingsService.AI_TEMPERATURE_KEY));
    }
}
