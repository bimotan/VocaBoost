package com.vocabtrainer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vocabtrainer.app.AppServices;
import com.vocabtrainer.domain.DictionaryLookupResult;
import com.vocabtrainer.domain.LookupOutcome;
import com.vocabtrainer.domain.ReviewMode;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.TestDatabases;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An API key is sent in its request header and nowhere else: not in error messages, log records,
 * exports or backups, and never unencrypted to another computer.
 */
class ApiKeyPrivacyTest {
    private static final String SECRET = "SECRETKEY";
    private static final String KEY = "sk-live-" + SECRET + "-0123456789abcd";
    private static final String PLAIN_HTTP_REFUSED = "The AI base URL uses plain http, which would send the API key"
        + " unencrypted. Use https, or http only for a server on this computer (localhost, 127.0.0.1 or ::1).";

    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    private StubHttpServer server;
    private AppServices services;
    private final WordCard lucid = WordCard.createNew(1, "lucid", "清晰的");

    @BeforeEach
    void setUp() throws Exception {
        server = new StubHttpServer();
        services = AppServices.builder(tempDir.resolve("vocab.db")).open();
        databases.track(services.databaseManager());
    }

    @AfterEach
    void tearDown() {
        services.close();
        server.close();
    }

    @Test
    void theKeyIsSentInItsHeaderButNeverLoggedShownExportedOrBackedUp() throws Exception {
        long deckId = services.startupDeck().getId();
        List<LogRecord> records;
        String shown;
        List<Path> files;
        try (LogCapture log = LogCapture.of("com.vocabtrainer")) {
            services.settingsService().saveAiSettings("openai-compatible", server.uri("/v1").toString(), KEY, "model-a");
            // The provider refuses the key and echoes it, as OpenAI does.
            server.answer("/v1/chat/completions", 401,
                "{\"error\":{\"message\":\"Incorrect API key provided: " + KEY + ".\"}}");
            shown = services.aiServices().get().explain(new ExplanationRequest(lucid, "清楚", ReviewMode.EN_TO_ZH));

            files = List.of(
                services.backupService().exportJsonBackup(deckId, tempDir.resolve("backup.json")),
                services.backupService().exportWordsCsv(deckId, tempDir.resolve("words.csv")),
                services.backupService().exportReviewLogsCsv(deckId, tempDir.resolve("review-logs.csv")),
                services.statsService().exportMarkdownReport(deckId, tempDir.resolve("report.md")));
            records = log.records();
        }

        assertEquals("Bearer " + KEY, server.requests().get(0).headers().getFirst("Authorization"));
        assertTrue(shown.endsWith(FallbackAiService.PROVIDER_FAILED_NOTE), shown);
        assertFalse(shown.contains(SECRET), shown);
        assertTrue(records.stream().anyMatch(record -> LogCapture.allText(record).contains("HTTP 401")),
            "the provider's refusal is logged");
        for (LogRecord record : records) {
            assertFalse(LogCapture.allText(record).contains(SECRET), LogCapture.allText(record));
        }
        for (Path file : files) {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            assertFalse(content.isBlank(), file.toString());
            assertFalse(content.contains(SECRET), file + " contains the key");
        }
        // The backup holds the deck's words, but none of the settings.
        assertTrue(new ObjectMapper().readTree(files.get(0).toFile()).toString().contains("\"lucid\""));
    }

    @Test
    void aKeyThatCannotBeSentIsReportedWithoutQuotingIt() {
        // A full-width space, as a Chinese input method types it; the JDK would quote the whole header.
        String badKey = "sk-live-" + SECRET + "　-0123456789";
        OpenAiCompatibleAiService provider = new OpenAiCompatibleAiService(server.uri("/v1").toString(), badKey,
            "model-a", null, StubHttpServer.client());
        AiService composed = new FallbackAiService(provider, new MockAiService());

        List<LogRecord> records;
        IllegalStateException error;
        String shown;
        try (LogCapture log = LogCapture.of("com.vocabtrainer")) {
            error = assertThrows(IllegalStateException.class, () -> provider.explain(lucid));
            shown = composed.explain(lucid);
            records = log.records();
        }

        assertEquals("The AI API key contains spaces or characters that cannot be sent in an HTTP header"
            + " (for example a full-width character or a line break); paste it again.", error.getMessage());
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            assertFalse(String.valueOf(cause.getMessage()).contains(SECRET), cause.toString());
        }
        assertTrue(shown.endsWith(FallbackAiService.PROVIDER_FAILED_NOTE), shown);
        assertEquals(1, records.size());
        assertFalse(LogCapture.allText(records.get(0)).contains(SECRET), LogCapture.allText(records.get(0)));
        assertEquals(List.of(), server.requests(), "nothing was sent");

        IllegalArgumentException saveError = assertThrows(IllegalArgumentException.class, () -> services.settingsService()
            .saveAiSettings("openai-compatible", "https://api.example.com/v1", badKey, "model-a"));
        assertFalse(saveError.getMessage().contains(SECRET), saveError.getMessage());
        assertTrue(services.settingsService().getAiApiKey().isEmpty());
    }

    @Test
    void plainHttpToAnotherComputerIsRefusedBeforeAnythingIsSent() throws Exception {
        // Every request of this client ends up at the stub, so a request that should not be sent shows up there.
        HttpClient viaStub = HttpClient.newBuilder().proxy(ProxySelector.of(server.address())).build();
        server.answer("/v1/chat/completions", 200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
        server.answer("/lookup", 200, "[{\"word\":\"lucid\",\"chinese\":\"清晰的\"}]");

        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
            new OpenAiCompatibleAiService("http://api.example.com/v1", KEY, "model-a", null, viaStub).explain(lucid));
        DictionaryLookupResult dictionary = new HttpDictionaryService("http://dict.example.com/lookup", KEY, viaStub)
            .lookup("lucid");

        assertEquals(PLAIN_HTTP_REFUSED, error.getMessage());
        assertEquals(LookupOutcome.SERVICE_ERROR, dictionary.outcome());
        assertTrue(dictionary.message().contains("明文 http"), dictionary.message());
        assertFalse(dictionary.message().contains(SECRET), dictionary.message());
        assertEquals(List.of(), server.paths(), "nothing was sent");

        // The same client does send to this computer, and a dictionary without a key may use http.
        assertEquals("ok", new OpenAiCompatibleAiService("http://localhost/v1", KEY, "model-a", null, viaStub)
            .explain(lucid));
        assertTrue(new HttpDictionaryService("http://dict.example.com/lookup", "", viaStub).lookup("lucid").success());
        assertEquals(List.of("/v1/chat/completions", "/lookup"), server.paths());

        IllegalArgumentException saveError = assertThrows(IllegalArgumentException.class, () -> services.settingsService()
            .saveAiSettings("openai-compatible", "http://api.example.com/v1", KEY, "model-a"));
        assertEquals(PLAIN_HTTP_REFUSED, saveError.getMessage());
        assertTrue(services.settingsService().getAiBaseUrl().isEmpty());
        services.settingsService().saveAiSettings("openai-compatible", "http://127.0.0.1:11434/v1", KEY, "model-a");
        assertEquals("http://127.0.0.1:11434/v1", services.settingsService().getAiBaseUrl().orElseThrow());
    }

    @Test
    void anEmptyKeyFieldKeepsTheSavedKeyWhichIsOnlyShownByItsLastCharacters() {
        SettingsService settings = services.settingsService();
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
            () -> settings.saveAiSettings("openai-compatible", "https://api.example.com/v1", "", "model-a"));
        assertEquals("AI base URL, API key, and model are required.", missing.getMessage());

        settings.saveAiSettings("openai-compatible", "https://api.example.com/v1", KEY, "model-a");
        settings.saveAiSettings("openai-compatible", "https://api.example.com/v1", "  ", "model-b", "0.2");

        assertEquals(KEY, settings.getAiApiKey().orElseThrow(), "saving with an empty key field keeps the key");
        assertEquals("model-b", settings.getAiModel().orElseThrow());
        assertEquals("••••abcd", settings.getAiApiKeyHint().orElseThrow());

        settings.saveAiSettings("openai-compatible", "https://api.example.com/v1", "sk-new-key-9876543210zz", "model-b");
        assertEquals("••••10zz", settings.getAiApiKeyHint().orElseThrow());

        settings.removeAiApiKey();
        assertTrue(settings.getAiApiKeyHint().isEmpty());
        assertEquals("model-b", settings.getAiModel().orElseThrow(), "removing the key keeps the other settings");
        assertTrue(AiServiceFactory.createUncachedProvider(settings, Map.of()).isEmpty());
    }
}
