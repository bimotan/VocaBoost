package com.vocabtrainer.service;

import com.vocabtrainer.domain.WordCard;

/**
 * Explains words for review. Implementations that can use the learner's answer override
 * {@link #explain(ExplanationRequest)}; the others explain the word alone.
 */
public interface AiService {
    boolean isAvailable();

    /** Explains the word without a learner answer. */
    String explain(WordCard word);

    /** Explains the word for the learner's answer in {@code request}. */
    default String explain(ExplanationRequest request) {
        return explain(request.word());
    }

    /** Like {@link #explain(ExplanationRequest)}, but never answers from a cache. */
    default String regenerate(ExplanationRequest request) {
        return explain(request);
    }
}
