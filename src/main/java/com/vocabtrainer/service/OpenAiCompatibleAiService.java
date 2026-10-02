package com.vocabtrainer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vocabtrainer.domain.ReviewMode;
import com.vocabtrainer.domain.WordCard;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An OpenAI-compatible chat completions API. The prompt sends the word, its meanings, part of
 * speech and example, and, after a review answer, what the learner typed and the question's
 * direction; it asks for a short JSON explanation (see {@link AiExplanation}), which is returned as
 * labelled sections, or as the provider's text when it is not that JSON.
 *
 * <p>The API key is sent as a bearer token only over https, or over http to this computer (a local
 * server such as Ollama); any other plain-http endpoint is refused before anything is sent.
 */
public class OpenAiCompatibleAiService implements AiService {
    /**
     * Part of every AI cache key. Bump it whenever the system prompt, the user prompt, the request
     * parameters or the way replies are read change, so explanations cached for the old request
     * are not reused.
     */
    static final String PROMPT_VERSION = "2";
    static final String SYSTEM_PROMPT = String.join("\n",
        "You are a concise GRE vocabulary tutor for Chinese-speaking learners.",
        "Reply with one JSON object and nothing else, with these string keys:",
        "\"meaning\": the word's core meaning in Chinese, with its part of speech;",
        "\"answer_feedback\": in Chinese, whether the learner's answer is right, and if it is wrong or only"
            + " partly right, why (for example which similar word or meaning it was confused with);"
            + " an empty string when there is no learner answer;",
        "\"memory_tip\": in Chinese, one mnemonic (word root, sound or image) or a contrast with an easily"
            + " confused word;",
        "\"example_en\": one natural English example sentence that uses the word;",
        "\"example_zh\": the Chinese translation of that sentence.",
        "Keep every value to one or two short sentences.");
    private static final Duration TIMEOUT = Duration.ofSeconds(12);
    private static final int MAX_ERROR_DETAIL_LENGTH = 200;

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final Double temperature;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OpenAiCompatibleAiService(String baseUrl, String apiKey, String model) {
        this(baseUrl, apiKey, model, null);
    }

    /** @param temperature the sampling temperature to send; null leaves it to the provider (some models accept no other) */
    public OpenAiCompatibleAiService(String baseUrl, String apiKey, String model, Double temperature) {
        this(baseUrl, apiKey, model, temperature, HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
    }

    public OpenAiCompatibleAiService(String baseUrl, String apiKey, String model, Double temperature,
                                     HttpClient httpClient) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null ? "" : model.trim();
        this.temperature = temperature;
        this.httpClient = httpClient;
    }

    @Override
    public boolean isAvailable() {
        return !baseUrl.isBlank() && !apiKey.isBlank() && !model.isBlank();
    }

    /**
     * Identifies what this service would answer: the prompt version, the temperature, the endpoint
     * and the model, but not the API key, so changing the key keeps cached explanations.
     */
    public String cacheIdentity() {
        return "openai-compatible|prompt-v" + PROMPT_VERSION
            + "|temperature=" + (temperature == null ? "default" : temperature)
            + "|" + baseUrl + "|" + model;
    }

    @Override
    public String explain(WordCard word) {
        return explain(ExplanationRequest.of(word));
    }

    @Override
    public String explain(ExplanationRequest request) {
        if (!isAvailable()) {
            throw new IllegalStateException("AI provider is not configured.");
        }
        URI endpoint;
        try {
            endpoint = AiEndpoint.chatCompletions(baseUrl);
            ApiKeys.requireSafeToSendKey(endpoint, "The AI base URL");
            ApiKeys.requireSendable(apiKey, "The AI API key");
        } catch (IllegalArgumentException e) {
            // Our own messages, which never contain the key.
            throw new IllegalStateException(e.getMessage(), e);
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            if (temperature != null) {
                body.put("temperature", temperature);
            }
            body.put("messages", List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user", "content", prompt(request))
            ));
            HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String detail = errorDetail(response.body());
                throw new IllegalStateException("AI provider returned HTTP " + response.statusCode()
                    + (detail.isBlank() ? "." : ": " + detail));
            }
            String content = parseContent(response.body());
            if (content.isBlank()) {
                throw new IllegalStateException("AI provider returned an empty response.");
            }
            return AiExplanation.parse(content).text();
        } catch (IOException e) {
            throw new IllegalStateException("AI provider network error.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AI request was interrupted.", e);
        } catch (IllegalArgumentException e) {
            // The JDK quotes an invalid header value, which would be the key, so the cause is dropped.
            throw new IllegalStateException("Cannot build the AI request: "
                + ApiKeys.redact(e.getMessage(), apiKey));
        }
    }

    /**
     * The user message: the word's fields and, when the learner answered, the question's direction
     * and the typed answer; for a memory aid, that the learner keeps forgetting the word.
     * {@link CachingAiService} keys its cache on the same fields.
     */
    static String prompt(ExplanationRequest request) {
        WordCard word = request.word();
        StringBuilder prompt = new StringBuilder("Word: ").append(clean(word.getEnglish()));
        appendLine(prompt, "Part of speech", word.getPartOfSpeech());
        appendLine(prompt, "Chinese meaning in the learner's deck", word.getChinese());
        appendLine(prompt, "Existing example", word.getExampleSentence());
        if (request.focus() == ExplanationRequest.Focus.MEMORY_AID) {
            prompt.append("\nThe learner keeps forgetting this word: it lapsed again and again (a leech).")
                .append(" Make \"memory_tip\" the heart of the reply: one vivid mnemonic (word root, sound or")
                .append(" image) that ties the English word to its Chinese meaning, or a contrast with the word it")
                .append(" is easily confused with, in two or three short sentences.");
            return prompt.toString();
        }
        if (!request.hasAnswer()) {
            prompt.append("\nThere is no learner answer; explain the word.");
            return prompt.toString();
        }
        prompt.append(switch (request.direction()) {
            case ZH_TO_EN -> "\nQuestion: the learner saw the Chinese meaning and typed the English word.";
            case CLOZE -> "\nQuestion: the learner saw the existing example with the word blanked out and the"
                + " Chinese meaning, and typed the missing English word.";
            default -> "\nQuestion: the learner saw the English word and typed its Chinese meaning.";
        });
        String answer = clean(request.typedAnswer());
        prompt.append("\nLearner's answer: ").append(answer.isEmpty() ? "(left blank)" : answer);
        return prompt.toString();
    }

    private static void appendLine(StringBuilder prompt, String label, String value) {
        String clean = clean(value);
        if (!clean.isEmpty()) {
            prompt.append('\n').append(label).append(": ").append(clean);
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.strip();
    }

    /**
     * The provider's own error message from an OpenAI-style error body, or "" if there is none.
     * It is shown in the UI and logged, so the API key is masked in case the provider echoes it.
     * The key is never put into a message or log record anywhere else either.
     */
    private String errorDetail(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root == null) {
                return "";
            }
            JsonNode error = root.path("error");
            String message = error.isTextual() ? error.asText() : error.path("message").asText("");
            if (message.isBlank()) {
                message = root.path("message").asText("");
            }
            message = ApiKeys.redact(message.strip(), apiKey);
            return message.length() > MAX_ERROR_DETAIL_LENGTH
                ? message.substring(0, MAX_ERROR_DETAIL_LENGTH) + "..."
                : message;
        } catch (IOException e) {
            // Not JSON (for example an HTML error page): the status code alone has to do.
            return "";
        }
    }

    private String parseContent(String json) throws IOException {
        JsonNode root = objectMapper.readTree(json);
        JsonNode choices = root.path("choices");
        if (choices.isArray() && choices.size() > 0) {
            String content = choices.get(0).path("message").path("content").asText("");
            if (!content.isBlank()) {
                return content.trim();
            }
            return choices.get(0).path("text").asText("").trim();
        }
        return root.path("content").asText("").trim();
    }
}
