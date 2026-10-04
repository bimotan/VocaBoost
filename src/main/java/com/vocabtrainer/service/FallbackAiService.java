package com.vocabtrainer.service;

import com.vocabtrainer.domain.WordCard;

import java.util.logging.Level;
import java.util.logging.Logger;

import static com.vocabtrainer.util.Messages.tr;

/**
 * The provider's explanation, or the mock text when the provider is not configured, answers with
 * nothing or fails. A failure is noted under the mock text with its category ({@link AiFailure}), such
 * as a refused key or a timeout, and a pointer to the AI settings' Test button; the details, which
 * may quote the provider's error message, go to the log only.
 */
public class FallbackAiService implements AiService {
    private static final Logger LOGGER = Logger.getLogger(FallbackAiService.class.getName());

    /**
     * Appended to the mock text when the provider fails, in the app's language: what went wrong and
     * where to look further. Older versions cached their English note in {@code ai_cache};
     * {@code SchemaMigrations} deletes such rows.
     */
    static String providerFailedNote(Throwable error) {
        return tr("ai.note.providerFailed", AiFailure.reason(error));
    }

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
        return explain(ExplanationRequest.of(word));
    }

    @Override
    public String explain(ExplanationRequest request) {
        return ask(request, false);
    }

    @Override
    public String regenerate(ExplanationRequest request) {
        return ask(request, true);
    }

    private String ask(ExplanationRequest request, boolean regenerate) {
        if (!primary.isAvailable()) {
            return fallback.explain(request);
        }
        try {
            String response = regenerate ? primary.regenerate(request) : primary.explain(request);
            if (response != null && !response.isBlank()) {
                return response;
            }
            return fallback.explain(request);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "AI provider failed (" + AiFailure.of(e) + "); using the mock explanation instead", e);
            return fallback.explain(request) + System.lineSeparator() + providerFailedNote(e);
        }
    }
}
