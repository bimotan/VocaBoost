package com.vocabtrainer.service;

import com.vocabtrainer.domain.WordCard;

import java.util.function.BooleanSupplier;

import static com.vocabtrainer.util.Messages.tr;

/**
 * The configured AI provider, which is not asked while offline mode is on: explanations then come
 * from the offline mock text with a note, and no request is sent. Offline mode is checked at each
 * call, so switching it applies at once, also to a review card that is already on screen.
 */
public class OfflineAwareAiService implements AiService {
    /** Appended to the mock text while offline mode is on, in the app's language. */
    public static String offlineNote() {
        return tr("ai.note.offline");
    }

    private final AiService online;
    private final AiService offlineFallback;
    private final BooleanSupplier offline;

    public OfflineAwareAiService(AiService online, AiService offlineFallback, BooleanSupplier offline) {
        this.online = online;
        this.offlineFallback = offlineFallback;
        this.offline = offline;
    }

    /** False while offline mode is on, since the provider is not used then. */
    @Override
    public boolean isAvailable() {
        return !offline.getAsBoolean() && online.isAvailable();
    }

    @Override
    public String explain(WordCard word) {
        return explain(ExplanationRequest.of(word));
    }

    @Override
    public String explain(ExplanationRequest request) {
        return offline.getAsBoolean() ? offlineText(request) : online.explain(request);
    }

    @Override
    public String regenerate(ExplanationRequest request) {
        return offline.getAsBoolean() ? offlineText(request) : online.regenerate(request);
    }

    private String offlineText(ExplanationRequest request) {
        return offlineFallback.explain(request) + System.lineSeparator() + offlineNote();
    }
}
