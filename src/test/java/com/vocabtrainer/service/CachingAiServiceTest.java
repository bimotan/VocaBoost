package com.vocabtrainer.service;

import com.vocabtrainer.domain.ReviewMode;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.AiCacheRepository;
import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.SettingsRepository;
import com.vocabtrainer.repository.TestDatabases;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CachingAiServiceTest {
    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    @Test
    void cachesConfiguredAiResponseByWord() throws Exception {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("ai-cache.db"));
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
        CachingAiService service = new CachingAiService(delegate, new AiCacheRepository(databaseManager), "provider-a");
        WordCard word = WordCard.createNew(1, "lucid", "清晰的");

        assertEquals("response-1", service.explain(word));
        assertEquals("response-1", service.explain(word));
        assertEquals(1, calls.get());
    }

    @Test
    void eachAnswerGetsItsOwnExplanationAndSpacingOrCaseDoNotMatter() throws Exception {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("ai-answers.db"));
        List<String> asked = new ArrayList<>();
        CachingAiService service = new CachingAiService(recordingAi(asked), new AiCacheRepository(databaseManager),
            "provider-a");
        WordCard word = WordCard.createNew(1, "abandon", "放纵; 放弃");

        String first = service.explain(new ExplanationRequest(word, "放弃", ReviewMode.EN_TO_ZH));
        assertEquals(first, service.explain(new ExplanationRequest(word, " 放弃 ", ReviewMode.EN_TO_ZH)));
        service.explain(new ExplanationRequest(word, "放纵", ReviewMode.EN_TO_ZH));
        service.explain(new ExplanationRequest(word, "Abandon", ReviewMode.ZH_TO_EN));
        assertEquals("explanation 3 for Abandon",
            service.explain(new ExplanationRequest(word, "abandon  ", ReviewMode.ZH_TO_EN)));
        service.explain(ExplanationRequest.of(word));

        assertEquals(List.of("放弃", "放纵", "Abandon", "(none)"), asked,
            "an explanation written for one answer is never shown for another");
    }

    @Test
    void regenerateAsksAgainAndReplacesTheCachedExplanation() throws Exception {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("ai-regenerate.db"));
        List<String> asked = new ArrayList<>();
        AiCacheRepository repository = new AiCacheRepository(databaseManager);
        CachingAiService service = new CachingAiService(recordingAi(asked), repository, "provider-a");
        ExplanationRequest request = new ExplanationRequest(WordCard.createNew(1, "lucid", "清晰的"), "清楚",
            ReviewMode.EN_TO_ZH);

        assertEquals("explanation 1 for 清楚", service.explain(request));
        assertEquals("explanation 1 for 清楚", service.explain(request));
        assertEquals("explanation 2 for 清楚", service.regenerate(request), "regenerate bypasses the cache");
        assertEquals("explanation 2 for 清楚", service.explain(request), "the new explanation replaced the old one");
        assertEquals(2, asked.size());

        assertEquals(1, repository.deleteAll());
        assertEquals("explanation 3 for 清楚", service.explain(request), "a cleared cache asks again");
    }

    private static AiService recordingAi(List<String> asked) {
        return new AiService() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String explain(WordCard word) {
                return explain(ExplanationRequest.of(word));
            }

            @Override
            public String explain(ExplanationRequest request) {
                asked.add(request.hasAnswer() ? request.typedAnswer() : "(none)");
                return "explanation " + asked.size() + " for " + asked.get(asked.size() - 1);
            }
        };
    }

    @Test
    void cacheKeyChangesWithProviderIdentityAndPromptFields() {
        AiService delegate = new MockAiService();
        CachingAiService modelA = new CachingAiService(delegate, null, "openai-compatible|prompt-v1|https://a.test|model-a");
        CachingAiService modelARebuilt = new CachingAiService(delegate, null, "openai-compatible|prompt-v1|https://a.test|model-a");
        CachingAiService modelB = new CachingAiService(delegate, null, "openai-compatible|prompt-v1|https://a.test|model-b");
        WordCard word = WordCard.createNew(1, "Lucid", "清晰的");
        WordCard withExample = WordCard.createNew(1, "lucid", "清晰的");
        withExample.setExampleSentence("Her lucid explanation helped.");

        String key = modelA.cacheKey(ExplanationRequest.of(word));

        assertEquals(key, modelARebuilt.cacheKey(ExplanationRequest.of(word)), "same provider and word must hit the same row");
        assertNotEquals(key, modelB.cacheKey(ExplanationRequest.of(word)), "another model must not reuse this explanation");
        assertNotEquals(key, modelA.cacheKey(ExplanationRequest.of(withExample)),
            "the prompt sends the example, so it is part of the key");
        assertTrue(key.startsWith("explain:v2:lucid:"), key);
        assertEquals("explain:v2:lucid:".length() + 64, key.length());
        assertNotEquals(key, modelA.cacheKey(ExplanationRequest.memoryAid(word)),
            "a memory aid is another answer than an explanation");
        assertEquals(key, modelA.cacheKey(new ExplanationRequest(word, null, null, ExplanationRequest.Focus.EXPLANATION)),
            "plain explanations keep the keys of earlier versions");
    }

    @Test
    void startupDeletesCachedFallbackTextAndKeepsRealExplanations() throws Exception {
        Path databasePath = tempDir.resolve("poisoned-ai-cache.db");
        DatabaseManager databaseManager = databases.open(databasePath);
        AiCacheRepository repository = new AiCacheRepository(databaseManager);
        LocalDateTime createdAt = LocalDateTime.of(2026, 5, 1, 9, 0);
        // What older versions stored after a provider error: the mock text plus the failure note.
        String poisoned = new MockAiService().explain(WordCard.createNew(1, "lucid", "清晰的"))
            + System.lineSeparator() + "AI provider failed; mock fallback was used.";
        repository.save("explain:v1:lucid:清晰的", poisoned, createdAt);
        repository.save("explain:v1:candid:坦率的", "candid 指坦率的、直言不讳的。", createdAt);
        // Those versions did not record a schema version.
        try (Connection connection = databaseManager.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version = 0");
        }

        databases.open(databasePath);

        assertTrue(repository.find("explain:v1:lucid:清晰的").isEmpty(), "cached fallback text must be deleted");
        assertEquals("candid 指坦率的、直言不讳的。", repository.find("explain:v1:candid:坦率的").orElseThrow());
    }

    @Test
    void factoryUsesMockWhenConfigIsIncomplete() {
        AiService service = AiServiceFactory.create(null, java.util.Map.of());

        assertTrue(service instanceof MockAiService);
    }

    @Test
    void factoryUsesLocalSettingsBeforeEnvironment() throws Exception {
        DatabaseManager databaseManager = databases.open(tempDir.resolve("ai-settings.db"));
        SettingsService settingsService = new SettingsService(new SettingsRepository(databaseManager));
        settingsService.saveAiSettings(
            "openai-compatible",
            "https://example.test/v1/chat/completions",
            "local-key",
            "local-model"
        );
        Map<String, String> environment = Map.of(
            "VOCABOOST_AI_PROVIDER", "off",
            "VOCABOOST_AI_BASE_URL", "https://env.test/v1/chat/completions",
            "VOCABOOST_AI_API_KEY", "env-key",
            "VOCABOOST_AI_MODEL", "env-model"
        );

        AiService service = AiServiceFactory.create(null, settingsService, environment);
        AiService provider = AiServiceFactory.createUncachedProvider(settingsService, environment).orElseThrow();

        assertTrue(service.isAvailable(), "the saved provider wins over VOCABOOST_AI_PROVIDER=off");
        String identity = ((OpenAiCompatibleAiService) provider).cacheIdentity();
        assertTrue(identity.contains("https://example.test/v1/chat/completions"), identity);
        assertTrue(identity.endsWith("|local-model"), identity);
        assertFalse(identity.contains("local-key"), "the API key is not part of the cache identity");
    }
}
