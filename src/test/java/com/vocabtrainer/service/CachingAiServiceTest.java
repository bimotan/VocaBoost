package com.vocabtrainer.service;

import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.AiCacheRepository;
import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.SettingsRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CachingAiServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void cachesConfiguredAiResponseByWord() throws Exception {
        DatabaseManager databaseManager = new DatabaseManager(tempDir.resolve("ai-cache.db"));
        databaseManager.initialize();
        AtomicInteger calls = new AtomicInteger();
        AiService delegate = new AiService() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String explain(WordCard word) {
                return "response-" + calls.incrementAndGet();
            }
        };
        CachingAiService service = new CachingAiService(delegate, new AiCacheRepository(databaseManager));
        WordCard word = WordCard.createNew(1, "lucid", "清晰的");

        assertEquals("response-1", service.explain(word));
        assertEquals("response-1", service.explain(word));
        assertEquals(1, calls.get());
    }

    @Test
    void factoryUsesMockWhenConfigIsIncomplete() {
        AiService service = AiServiceFactory.create(null, java.util.Map.of());

        assertTrue(service instanceof MockAiService);
    }

    @Test
    void factoryUsesLocalSettingsBeforeEnvironment() throws Exception {
        DatabaseManager databaseManager = new DatabaseManager(tempDir.resolve("ai-settings.db"));
        databaseManager.initialize();
        SettingsService settingsService = new SettingsService(new SettingsRepository(databaseManager));
        settingsService.saveAiSettings(
            "openai-compatible",
            "https://example.test/v1/chat/completions",
            "local-key",
            "local-model"
        );

        AiService service = AiServiceFactory.create(null, settingsService, java.util.Map.of());

        assertTrue(service.isAvailable());
    }
}
