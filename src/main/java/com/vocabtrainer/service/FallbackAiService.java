package com.vocabtrainer.service;

import com.vocabtrainer.domain.WordCard;

import java.util.logging.Level;
import java.util.logging.Logger;

public class FallbackAiService implements AiService {
    private static final Logger LOGGER = Logger.getLogger(FallbackAiService.class.getName());
    /**
     * Appended to the mock text when the provider fails. Older versions cached that text in
     * {@code ai_cache}; {@code DatabaseManager} deletes such rows at startup.
     */
    static final String PROVIDER_FAILED_NOTE = "AI provider failed; mock fallback was used.";

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
            LOGGER.log(Level.WARNING, "AI provider failed; using the mock explanation instead", e);
            return fallback.explain(word) + System.lineSeparator() + PROVIDER_FAILED_NOTE;
        }
    }
}
