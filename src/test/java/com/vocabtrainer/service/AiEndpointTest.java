package com.vocabtrainer.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiEndpointTest {
    @ParameterizedTest
    @CsvSource({
        "https://api.deepseek.com, https://api.deepseek.com/chat/completions",
        "https://api.deepseek.com/, https://api.deepseek.com/chat/completions",
        "https://api.openai.com/v1, https://api.openai.com/v1/chat/completions",
        "https://api.openai.com/v1/, https://api.openai.com/v1/chat/completions",
        "https://api.groq.com/openai/v1, https://api.groq.com/openai/v1/chat/completions",
        "https://open.bigmodel.cn/api/paas/v4/, https://open.bigmodel.cn/api/paas/v4/chat/completions",
        "http://localhost:11434/v1, http://localhost:11434/v1/chat/completions",
        "https://generativelanguage.googleapis.com/v1beta/openai/,"
            + " https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
        "https://example.com/v1beta?key=a, https://example.com/v1beta/chat/completions?key=a",
        "' https://api.openai.com/v1/chat/completions ', https://api.openai.com/v1/chat/completions",
        "https://proxy.example/openai/deployments/gpt/chat/completions?api-version=2024-06-01,"
            + " https://proxy.example/openai/deployments/gpt/chat/completions?api-version=2024-06-01",
        "https://proxy.example/ai/complete, https://proxy.example/ai/complete",
    })
    void aBaseUrlOrAFullEndpointGivesTheChatCompletionsUrl(String baseUrl, String endpoint) {
        assertEquals(endpoint, AiEndpoint.chatCompletions(baseUrl).toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "api.openai.com/v1", "ftp://example.com/v1", "https://", "https://exa mple.com"})
    void anythingButAnHttpUrlWithAHostIsRefused(String baseUrl) {
        assertThrows(IllegalArgumentException.class, () -> AiEndpoint.chatCompletions(baseUrl));
    }
}
