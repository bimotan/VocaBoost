package com.vocabtrainer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vocabtrainer.domain.ReviewMode;
import com.vocabtrainer.domain.WordCard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The requests {@link OpenAiCompatibleAiService} sends and how it reads the replies, against a local stub. */
class OpenAiCompatibleAiServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String STRUCTURED_REPLY = "{\"meaning\": \"v. 放纵; 放弃\", "
        + "\"answer_feedback\": \"“放弃”只是 abandon 的一个意思。\", \"memory_tip\": \"a + band：挣脱束缚\", "
        + "\"example_en\": \"They danced with abandon.\", \"example_zh\": \"他们尽情地跳舞。\"}";

    private StubHttpServer server;
    private WordCard abandon;

    @BeforeEach
    void setUp() throws IOException {
        server = new StubHttpServer();
        abandon = WordCard.createNew(1, "abandon", "放纵; 放弃");
        abandon.setPartOfSpeech("verb; noun");
        abandon.setExampleSentence("He abandoned the plan.");
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void thePromptSendsTheTypedAnswerAndTheQuestionDirection() throws Exception {
        server.answer("/v1/chat/completions", 200, completion(STRUCTURED_REPLY));

        String text = provider("/v1", null).explain(new ExplanationRequest(abandon, "放弃", ReviewMode.EN_TO_ZH));

        JsonNode body = onlyRequestBody();
        assertEquals("model-a", body.path("model").asText());
        assertFalse(body.has("temperature"), "no temperature unless one is configured: " + body);
        String system = body.path("messages").get(0).path("content").asText();
        for (String key : List.of("meaning", "answer_feedback", "memory_tip", "example_en", "example_zh")) {
            assertTrue(system.contains("\"" + key + "\""), "the system prompt asks for " + key + ": " + system);
        }
        assertEquals("""
            Word: abandon
            Part of speech: verb; noun
            Chinese meaning in the learner's deck: 放纵; 放弃
            Existing example: He abandoned the plan.
            Question: the learner saw the English word and typed its Chinese meaning.
            Learner's answer: 放弃""", body.path("messages").get(1).path("content").asText());

        String nl = System.lineSeparator();
        assertEquals("Meaning: v. 放纵; 放弃" + nl
            + "About your answer: “放弃”只是 abandon 的一个意思。" + nl
            + "Memory tip: a + band：挣脱束缚" + nl
            + "Example: They danced with abandon." + nl
            + "  他们尽情地跳舞。", text);
    }

    @Test
    void aChineseToEnglishQuestionAndABlankAnswerAreSaidSo() throws Exception {
        server.answer("/v1/chat/completions", 200, completion(STRUCTURED_REPLY));

        provider("/v1", null).explain(new ExplanationRequest(abandon, "  ", ReviewMode.ZH_TO_EN));

        String user = onlyRequestBody().path("messages").get(1).path("content").asText();
        assertTrue(user.endsWith("Question: the learner saw the Chinese meaning and typed the English word."
            + "\nLearner's answer: (left blank)"), user);
    }

    @Test
    void withoutAnAnswerOnlyTheWordIsExplained() throws Exception {
        server.answer("/v1/chat/completions", 200, completion("lucid 指清晰易懂的。"));

        String text = provider("/v1", null).explain(WordCard.createNew(1, "lucid", "清晰的"));

        assertEquals("lucid 指清晰易懂的。", text, "a reply that is not the requested JSON is shown as it is");
        assertEquals("Word: lucid\nChinese meaning in the learner's deck: 清晰的\n"
                + "There is no learner answer; explain the word.",
            onlyRequestBody().path("messages").get(1).path("content").asText());
    }

    @Test
    void aConfiguredTemperatureIsSent() throws Exception {
        server.answer("/v1/chat/completions", 200, completion(STRUCTURED_REPLY));

        provider("/v1", 0.7).explain(ExplanationRequest.of(abandon));

        assertEquals(0.7, onlyRequestBody().path("temperature").asDouble());
    }

    @Test
    void aBareBaseUrlGetsTheChatCompletionsPath() throws Exception {
        server.answer("/chat/completions", 200, completion(STRUCTURED_REPLY));
        server.answer("/custom/endpoint", 200, completion(STRUCTURED_REPLY));

        provider("", null).explain(ExplanationRequest.of(abandon));
        provider("/", null).explain(ExplanationRequest.of(abandon));
        provider("/custom/endpoint", null).explain(ExplanationRequest.of(abandon));

        assertEquals(List.of("/chat/completions", "/chat/completions", "/custom/endpoint"), server.paths());
    }

    @Test
    void theCacheIdentityNamesTheTemperatureButNotTheKey() {
        String withDefault = provider("/v1", null).cacheIdentity();
        String withTemperature = provider("/v1", 0.7).cacheIdentity();

        assertTrue(withDefault.contains("|prompt-v" + OpenAiCompatibleAiService.PROMPT_VERSION + "|"), withDefault);
        assertTrue(withDefault.contains("|temperature=default|"), withDefault);
        assertTrue(withTemperature.contains("|temperature=0.7|"), withTemperature);
        assertFalse(withDefault.contains("test-key"), withDefault);
    }

    private OpenAiCompatibleAiService provider(String path, Double temperature) {
        String base = server.uri(path).toString();
        return new OpenAiCompatibleAiService(base, "test-key", "model-a", temperature, StubHttpServer.client());
    }

    private JsonNode onlyRequestBody() throws IOException {
        List<StubHttpServer.Request> requests = server.requests();
        assertEquals(1, requests.size(), "requests: " + server.paths());
        return JSON.readTree(requests.get(0).body());
    }

    private static String completion(String content) throws IOException {
        return JSON.writeValueAsString(
            Map.of("choices", List.of(Map.of("message", Map.of("role", "assistant", "content", content)))));
    }
}
