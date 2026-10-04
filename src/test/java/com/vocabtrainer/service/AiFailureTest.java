package com.vocabtrainer.service;

import com.vocabtrainer.domain.ReviewMode;
import com.vocabtrainer.domain.WordCard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A failed AI request during review says in a few words why (review finding D5): a refused key, a
 * wrong address or model, a rate limit, a server error, a timeout or no network, with a pointer to
 * the AI settings' Test button. The provider's own message stays in the log and never shows the key.
 */
class AiFailureTest {
    private static final String KEY = "sk-test-0123456789abcdef-SECRET";
    private static final String NOTE_END = ". The offline mock explanation is shown instead; Settings → AI Explanation"
        + " Provider → Test AI Explanation shows the details.";

    private final WordCard lucid = WordCard.createNew(1, "lucid", "清晰的");
    private final ExplanationRequest request = new ExplanationRequest(lucid, "清楚", ReviewMode.EN_TO_ZH);
    private StubHttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = new StubHttpServer();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void theHttpStatusGivesTheCategory() {
        assertEquals(AiFailure.AUTH, failureFor(401));
        assertEquals(AiFailure.AUTH, failureFor(403));
        assertEquals(AiFailure.NOT_FOUND, failureFor(404));
        assertEquals(AiFailure.RATE_LIMITED, failureFor(429));
        assertEquals(AiFailure.SERVER_ERROR, failureFor(500));
        assertEquals(AiFailure.SERVER_ERROR, failureFor(503));
        assertEquals(AiFailure.REFUSED, failureFor(400));
    }

    @Test
    void theTestButtonStillGetsTheProvidersMessage() {
        server.answer("/v1/chat/completions", 400, "{\"error\":{\"message\":\"Unsupported value: temperature\"}}");

        AiRequestException error = assertThrows(AiRequestException.class, () -> provider().explain(request));

        assertEquals(AiFailure.REFUSED, error.failure());
        assertEquals(400, error.status());
        assertEquals("AI provider returned HTTP 400: Unsupported value: temperature", error.getMessage());
    }

    @Test
    void anAnswerThatIsNotJsonIsABadResponse() {
        server.answer("/v1/chat/completions", 200, "<html>gateway</html>");

        AiRequestException error = assertThrows(AiRequestException.class, () -> provider().explain(request));

        assertEquals(AiFailure.BAD_RESPONSE, error.failure());
    }

    @Test
    void aProviderThatCannotBeReachedIsANetworkFailure() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        OpenAiCompatibleAiService unreachable = new OpenAiCompatibleAiService("http://127.0.0.1:" + closedPort + "/v1",
            KEY, "model-a", null, StubHttpServer.client());

        AiRequestException error = assertThrows(AiRequestException.class, () -> unreachable.explain(request));

        assertEquals(AiFailure.NETWORK, error.failure());
    }

    @Test
    void timeoutsAndUnreachableHostsAreToldApart() {
        assertEquals(AiFailure.TIMEOUT, AiFailure.of(new IllegalStateException(new HttpTimeoutException("timed out"))));
        assertEquals(AiFailure.NETWORK, AiFailure.of(new HttpConnectTimeoutException("connect timed out")));
        assertEquals(AiFailure.NETWORK, AiFailure.of(new IllegalStateException(new ConnectException("refused"))));
        assertEquals(AiFailure.NETWORK, AiFailure.of(new IOException("reset")));
        assertEquals(AiFailure.OTHER, AiFailure.of(new IllegalStateException("boom")));
    }

    @Test
    void theReviewNoteNamesTheCategoryAndNeverTheProvidersMessage() {
        // The provider echoes the key, as OpenAI does for a wrong one.
        server.answer("/v1/chat/completions", 401, "{\"error\":{\"message\":\"Incorrect API key provided: " + KEY + "\"}}");
        AiService composed = new FallbackAiService(provider(), new MockAiService());

        String shown;
        List<LogRecord> records;
        try (LogCapture log = LogCapture.of(FallbackAiService.class)) {
            shown = composed.explain(request);
            records = log.records();
        }

        assertTrue(shown.startsWith("Mock AI: lucid"), shown);
        assertTrue(shown.endsWith("AI provider failed: the API key was refused (HTTP 401)" + NOTE_END), shown);
        assertFalse(shown.contains("Incorrect API key"), shown);
        assertFalse(shown.contains("SECRET"), shown);
        assertEquals(1, records.size());
        assertTrue(LogCapture.allText(records.get(0)).contains("HTTP 401"), "the details are logged");
        assertFalse(LogCapture.allText(records.get(0)).contains("SECRET"), LogCapture.allText(records.get(0)));
    }

    @Test
    void everyCategoryHasAShortReason() {
        assertEquals("not found (HTTP 404): check the base URL and the model", AiFailure.NOT_FOUND.reason(404));
        assertEquals("too many requests (HTTP 429): wait a moment, or check the quota of the account",
            AiFailure.RATE_LIMITED.reason(429));
        assertEquals("the provider's server failed (HTTP 502): try again later", AiFailure.SERVER_ERROR.reason(502));
        assertEquals("it did not answer in time", AiFailure.TIMEOUT.reason(0));
        assertEquals("it cannot be reached: check the network and the base URL", AiFailure.NETWORK.reason(0));
        assertEquals("it did not answer in time",
            AiFailure.reason(new AiRequestException(AiFailure.TIMEOUT, "AI provider did not answer in time.")));
        for (AiFailure failure : AiFailure.values()) {
            assertFalse(failure.reason(500).isBlank(), failure.name());
        }
    }

    private AiFailure failureFor(int status) {
        server.answer("/v1/chat/completions", status, "{\"error\":{\"message\":\"no\"}}");
        AiRequestException error = assertThrows(AiRequestException.class, () -> provider().explain(request));
        assertEquals(status, error.status());
        return error.failure();
    }

    private OpenAiCompatibleAiService provider() {
        return new OpenAiCompatibleAiService(server.uri("/v1").toString(), KEY, "model-a", null, StubHttpServer.client());
    }
}
