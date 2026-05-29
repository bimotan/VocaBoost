package com.vocabtrainer.service;

import com.vocabtrainer.domain.WordCard;

public class FallbackAiService implements AiService {
    private final AiService primary;
    private final AiService fallback;

    public FallbackAiService(AiService primary, AiService fallback) {
        this.primary = primary;
        this.fallback = fallback;
    }

    @Override
    public boolean isAvailable() {
        return primary.isAvailable();
    }

    @Override
    public String explain(WordCard word) {
        if (!primary.isAvailable()) {
            return fallback.explain(word);
        }
        try {
            String response = primary.explain(word);
            if (response != null && !response.isBlank()) {
                return response;
            }
            return fallback.explain(word);
        } catch (RuntimeException e) {
            return fallback.explain(word) + System.lineSeparator()
                + "AI provider failed; mock fallback was used.";
        }
    }
}
