package com.vocabtrainer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.vocabtrainer.domain.ReviewMode;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.AiCacheRepository;
import com.vocabtrainer.repository.DatabaseManager;
import com.vocabtrainer.repository.TestDatabases;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The composed services from {@link AiServiceFactory} against a local OpenAI-compatible stub. */
class AiServiceFactoryTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    private final AtomicInteger requests = new AtomicInteger();
    private HttpServer server;
    private DatabaseManager databaseManager;
    private AiCacheRepository cacheRepository;

    @BeforeEach
    void setUp() throws Exception {
        databaseManager = databases.open(tempDir.resolve("ai-factory.db"));
        cacheRepository = new AiCacheRepository(databaseManager);
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void failedRequestShowsFallbackOnceWithoutCachingIt() throws Exception {
        startServer((request, body) -> request == 1
            ? new StubResponse(503, "{\"error\":{\"message\":\"Service temporarily unavailable\"}}")
            : completion("lucid 指清晰易懂的。"));
        AiService service = AiServiceFactory.create(cacheRepository, config("/v1/chat/completions", "model-a"));
        WordCard word = WordCard.createNew(1, "lucid", "清晰的");

        String first;
        List<LogRecord> warnings;
        try (LogCapture log = LogCapture.of(FallbackAiService.class)) {
            first = service.explain(word);
            warnings = log.warnings();
        }

        assertTrue(first.endsWith("AI provider failed; mock fallback was used."), first);
        assertEquals(1, warnings.size());
        assertEquals(List.of(), cachedResponses(), "the fallback text must not be cached");

        assertEquals("lucid 指清晰易懂的。", service.explain(word), "the next call asks the provider again");
        assertEquals(List.of("lucid 指清晰易懂的。"), cachedResponses());
        assertEquals("lucid 指清晰易懂的。", service.explain(word));
        assertEquals(2, requests.get(), "the real answer is served from the cache");
    }

    @Test
    void changingModelOrEndpointRequestsNewExplanation() throws Exception {
        startServer((request, body) -> completion("explanation from " + requestedModel(body)));
        WordCard word = WordCard.createNew(1, "lucid", "清晰的");

        assertEquals("explanation from model-a",
            AiServiceFactory.create(cacheRepository, config("/v1/chat/completions", "model-a")).explain(word));
        assertEquals("explanation from model-b",
            AiServiceFactory.create(cacheRepository, config("/v1/chat/completions", "model-b")).explain(word));
        assertEquals(2, requests.get());

        // Rebuilding the service with the same settings (as Save does) keeps using the cache.
        assertEquals("explanation from model-a",
            AiServiceFactory.create(cacheRepository, config("/v1/chat/completions", "model-a")).explain(word));
        assertEquals(2, requests.get());

        AiServiceFactory.create(cacheRepository, config("/other/chat/completions", "model-a")).explain(word);
        assertEquals(3, requests.get(), "another endpoint must not reuse the cached explanation");
    }

    @Test
    void regenerateAsksTheProviderAgainAndCachesTheNewExplanation() throws Exception {
        startServer((request, body) -> completion("explanation " + request));
        AiService service = AiServiceFactory.create(cacheRepository, config("/v1", "model-a"));
        ExplanationRequest request = new ExplanationRequest(WordCard.createNew(1, "lucid", "清晰的"), "清楚",
            ReviewMode.EN_TO_ZH);

        assertEquals("explanation 1", service.explain(request));
        assertEquals("explanation 1", service.explain(request));
        assertEquals("explanation 2", service.regenerate(request));
        assertEquals("explanation 2", service.explain(request));

        assertEquals(2, requests.get());
        assertEquals(List.of("explanation 2"), cachedResponses());
    }

    @Test
    void theTemperatureIsSentOnlyWhenAValidOneIsConfigured() throws Exception {
        List<String> bodies = new ArrayList<>();
        startServer((request, body) -> {
            bodies.add(body);
            return completion("ok");
        });
        WordCard word = WordCard.createNew(1, "lucid", "清晰的");
        Map<String, String> withTemperature = new java.util.HashMap<>(config("/v1", "model-a"));
        withTemperature.put("VOCABOOST_AI_TEMPERATURE", "0.3");
        Map<String, String> invalid = new java.util.HashMap<>(config("/v1", "model-a"));
        invalid.put("VOCABOOST_AI_TEMPERATURE", "warm");

        AiServiceFactory.createUncachedProvider(null, withTemperature).orElseThrow().explain(word);
        List<LogRecord> warnings;
        try (LogCapture log = LogCapture.of(AiServiceFactory.class)) {
            AiServiceFactory.createUncachedProvider(null, invalid).orElseThrow().explain(word);
            warnings = log.warnings();
        }

        assertEquals(0.3, JSON.readTree(bodies.get(0)).path("temperature").asDouble());
        assertFalse(JSON.readTree(bodies.get(1)).has("temperature"), bodies.get(1));
        assertEquals(1, warnings.size());
    }

    @Test
    void uncachedProviderSendsFreshRequestsAndThrowsProviderErrors() throws Exception {
        startServer((request, body) -> switch (request) {
            case 1 -> completion("cached answer");
            case 2 -> new StubResponse(401, "{\"error\":{\"message\":\"Incorrect API key provided: test-key.\"}}");
            default -> completion("fresh answer");
        });
        Map<String, String> config = config("/v1/chat/completions", "model-a");
        WordCard word = WordCard.createNew(1, "lucid", "清晰的; 易懂的");
        assertEquals("cached answer", AiServiceFactory.create(cacheRepository, config).explain(word));

        AiService provider = AiServiceFactory.createUncachedProvider(null, config).orElseThrow();

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> provider.explain(word));
        assertEquals("AI provider returned HTTP 401: Incorrect API key provided: ***.", error.getMessage(),
            "shows the provider's message without echoing the key");
        assertEquals("fresh answer", provider.explain(word));
        assertEquals(3, requests.get());
        assertEquals(List.of("cached answer"), cachedResponses(), "the test request does not touch the cache");
    }

    @Test
    void uncachedProviderIsEmptyWhenNothingIsConfigured() {
        assertTrue(AiServiceFactory.createUncachedProvider(null, Map.of()).isEmpty());
        assertTrue(AiServiceFactory.createUncachedProvider(null, Map.of(
            "VOCABOOST_AI_PROVIDER", "off",
            "VOCABOOST_AI_BASE_URL", "http://127.0.0.1:9/v1/chat/completions",
            "VOCABOOST_AI_API_KEY", "key",
            "VOCABOOST_AI_MODEL", "model")).isEmpty());
    }

    @Test
    void providerErrorWithoutJsonBodyReportsTheStatus() throws Exception {
        startServer((request, body) -> new StubResponse(404, "<html><body>Not Found</body></html>"));
        AiService provider = AiServiceFactory.createUncachedProvider(null, config("/v1", "model-a")).orElseThrow();

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> provider.explain(WordCard.createNew(1, "lucid", "清晰的")));

        assertEquals("AI provider returned HTTP 404.", error.getMessage());
    }

    private void startServer(BiFunction<Integer, String, StubResponse> responder) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            try {
                String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                StubResponse response = responder.apply(requests.incrementAndGet(), requestBody);
                byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(response.status(), bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    private Map<String, String> config(String path, String model) {
        return Map.of(
            "VOCABOOST_AI_PROVIDER", "openai-compatible",
            "VOCABOOST_AI_BASE_URL", "http://127.0.0.1:" + server.getAddress().getPort() + path,
            "VOCABOOST_AI_API_KEY", "test-key",
            "VOCABOOST_AI_MODEL", model
        );
    }

    private List<String> cachedResponses() throws SQLException {
        List<String> responses = new ArrayList<>();
        try (Connection connection = databaseManager.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT response FROM ai_cache ORDER BY id")) {
            while (rs.next()) {
                responses.add(rs.getString(1));
            }
        }
        return responses;
    }

    private static StubResponse completion(String content) {
        try {
            return new StubResponse(200, JSON.writeValueAsString(
                Map.of("choices", List.of(Map.of("message", Map.of("role", "assistant", "content", content))))));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String requestedModel(String requestBody) {
        try {
            return JSON.readTree(requestBody).path("model").asText();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private record StubResponse(int status, String body) {
    }
}
