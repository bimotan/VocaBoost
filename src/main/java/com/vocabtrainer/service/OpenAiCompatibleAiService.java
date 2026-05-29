package com.vocabtrainer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vocabtrainer.domain.WordCard;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public class OpenAiCompatibleAiService implements AiService {
    private static final Duration TIMEOUT = Duration.ofSeconds(12);

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OpenAiCompatibleAiService(String baseUrl, String apiKey, String model) {
        this(baseUrl, apiKey, model, HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
    }

    public OpenAiCompatibleAiService(String baseUrl, String apiKey, String model, HttpClient httpClient) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null ? "" : model.trim();
        this.httpClient = httpClient;
    }

    @Override
    public boolean isAvailable() {
        return !baseUrl.isBlank() && !apiKey.isBlank() && !model.isBlank();
    }

    @Override
    public String explain(WordCard word) {
        if (!isAvailable()) {
            throw new IllegalStateException("AI provider is not configured.");
        }
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                "model", model,
                "temperature", 0.2,
                "messages", List.of(
                    Map.of("role", "system", "content",
                        "You are a concise vocabulary tutor. Reply in Chinese. Include meaning, mnemonic, and one example sentence."),
                    Map.of("role", "user", "content", prompt(word))
                )
            ));
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("AI provider returned HTTP " + response.statusCode() + ".");
            }
            String content = parseContent(response.body());
            if (content.isBlank()) {
                throw new IllegalStateException("AI provider returned an empty response.");
            }
            return content;
        } catch (IOException e) {
            throw new IllegalStateException("AI provider network error.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AI request was interrupted.", e);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("AI provider URL is invalid.", e);
        }
    }

    private String prompt(WordCard word) {
        String english = word == null ? "" : word.getEnglish();
        String chinese = word == null ? "" : word.getChinese();
        String example = word == null || word.getExampleSentence() == null ? "" : word.getExampleSentence();
        return "Explain this word for GRE study: " + english
            + "\nChinese meaning: " + chinese
            + "\nExisting example: " + example;
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
