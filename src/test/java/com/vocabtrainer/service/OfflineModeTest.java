package com.vocabtrainer.service;

import com.vocabtrainer.app.AppServices;
import com.vocabtrainer.domain.DictionaryLookupResult;
import com.vocabtrainer.domain.LookupOutcome;
import com.vocabtrainer.domain.ReviewMode;
import com.vocabtrainer.domain.VerificationStatus;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.domain.WordVerificationResult;
import com.vocabtrainer.repository.AiCacheRepository;
import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.DictionaryCacheRepository;
import com.vocabtrainer.repository.SettingsRepository;
import com.vocabtrainer.repository.TestDatabases;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Offline mode: no online dictionary lookup and no AI request reaches the network. */
class OfflineModeTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-01T09:00:00Z"), ZoneOffset.UTC);
    private static final String QUOKKA = """
        [{"word":"quokka","meanings":[{"partOfSpeech":"noun","definitions":[
            {"definition":"A small wallaby of south-western Australia."}]}]}]
        """;
    private static final String COMPLETION = "{\"choices\":[{\"message\":{\"content\":\"lucid 指清晰易懂的。\"}}]}";

    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    private StubHttpServer server;
    private SettingsService settings;
    private DatabaseManager databaseManager;

    @BeforeEach
    void setUp() throws Exception {
        server = new StubHttpServer();
        databaseManager = databases.open(tempDir.resolve("offline.db"));
        settings = new SettingsService(new SettingsRepository(databaseManager));
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void offlineModeIsOffUntilSwitchedOnAndIsSaved() throws Exception {
        assertFalse(settings.isOfflineMode());

        settings.saveOfflineMode(true);

        assertTrue(new SettingsService(new SettingsRepository(databaseManager)).isOfflineMode());
        settings.saveOfflineMode(false);
        assertFalse(settings.isOfflineMode());
    }

    @Test
    void offlineTheDictionariesAskNoServerButTheLocalOnesAndTheCacheStillAnswer() {
        server.answer("/dictapi/quokka", 200, QUOKKA);
        DictionaryService chain = DictionaryServiceFactory.compose(new LocalDictionaryService(),
            new HttpDictionaryService(server.uri("/api").toString(), "key", StubHttpServer.client()),
            new PublicOnlineDictionaryService(StubHttpServer.client(), server.uri("/dictapi/"), server.uri("/wiki/"),
                Duration.ofSeconds(2)),
            new DictionaryCacheRepository(databaseManager), CLOCK, settings::isOfflineMode);
        // Online, the public dictionary's answer is cached (the configured API does not have the word).
        assertTrue(chain.lookup("quokka").success());
        int onlineRequests = server.requests().size();
        assertEquals(2, onlineRequests, "the configured API, then dictionaryapi.dev: " + server.paths());

        settings.saveOfflineMode(true);
        DictionaryLookupResult unknown = chain.lookup("petrichor");
        WordVerificationResult verification = chain.verify("petrichor");
        DictionaryLookupResult refreshed = chain.refresh("petrichor");
        DictionaryLookupResult cached = chain.lookup("quokka");
        DictionaryLookupResult starter = chain.lookup("abate");

        assertEquals(onlineRequests, server.requests().size(), "no request in offline mode: " + server.paths());
        assertEquals(LookupOutcome.OFFLINE, unknown.outcome());
        assertTrue(unknown.message().contains("offline mode is on"), unknown.message());
        assertEquals(VerificationStatus.UNCHECKED, verification.status(), "not asked is not the same as not found");
        assertEquals(LookupOutcome.OFFLINE, verification.outcome());
        assertEquals(LookupOutcome.OFFLINE, refreshed.outcome());
        assertTrue(cached.success(), "cached lookups still answer: " + cached.message());
        assertTrue(starter.success(), "the bundled starter words still answer");

        settings.saveOfflineMode(false);
        server.answer("/api", 200, "[{\"word\":\"petrichor\",\"chinese\":\"雨后泥土的气味\"}]");
        assertTrue(chain.lookup("petrichor").success());
        assertEquals(onlineRequests + 1, server.requests().size(), "back online, the configured API is asked again");
    }

    @Test
    void offlineTheAiUsesTheMockTextWithoutARequest() throws Exception {
        server.answer("/v1/chat/completions", 200, COMPLETION);
        Map<String, String> config = Map.of(
            "VOCABOOST_AI_BASE_URL", server.uri("/v1").toString(),
            "VOCABOOST_AI_API_KEY", "test-key",
            "VOCABOOST_AI_MODEL", "model-a");
        AiService service = AiServiceFactory.create(new AiCacheRepository(databaseManager), settings, config);
        ExplanationRequest request = new ExplanationRequest(WordCard.createNew(1, "lucid", "清晰的"), "清楚",
            ReviewMode.EN_TO_ZH);

        settings.saveOfflineMode(true);
        String explained = service.explain(request);
        String regenerated = service.regenerate(request);

        assertEquals(List.of(), server.paths(), "no AI request in offline mode");
        assertFalse(service.isAvailable(), "the provider is not used while offline");
        assertTrue(explained.startsWith("Mock AI: "), explained);
        assertTrue(explained.endsWith("Offline mode is on: no AI request was sent."), explained);
        assertEquals(explained, regenerated);

        settings.saveOfflineMode(false);
        assertTrue(service.isAvailable());
        assertEquals("lucid 指清晰易懂的。", service.explain(request));
        assertEquals(List.of("/v1/chat/completions"), server.paths());
    }

    @Test
    void theAppsDictionaryChainFollowsTheSavedOfflineMode() throws Exception {
        try (AppServices services = AppServices.builder(tempDir.resolve("app.db")).clock(CLOCK).open()) {
            databases.track(services.databaseManager());
            services.settingsService().saveOfflineMode(true);

            DictionaryLookupResult result = services.dictionaryServices().get().lookup("petrichor");

            assertEquals(LookupOutcome.OFFLINE, result.outcome(), result.message());
        }
    }
}
