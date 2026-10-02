package com.vocabtrainer.service;

import com.vocabtrainer.domain.ReviewMode;
import com.vocabtrainer.domain.WordCard;

import java.util.Locale;
import java.util.Objects;

/**
 * What an AI explanation is asked for: a word and, after a review answer, what the learner typed
 * and in which direction the question was asked.
 *
 * @param typedAnswer the learner's answer; null when there is none (an explanation of the word only)
 * @param direction   {@link ReviewMode#EN_TO_ZH} (the English word was shown),
 *                    {@link ReviewMode#ZH_TO_EN} (the Chinese meaning was shown) or
 *                    {@link ReviewMode#CLOZE} (the example with the word blanked out and the Chinese
 *                    meaning were shown); null without an answer
 */
public record ExplanationRequest(WordCard word, String typedAnswer, ReviewMode direction) {
    public ExplanationRequest {
        Objects.requireNonNull(word, "word");
        if (direction != null && !direction.isDirection()) {
            throw new IllegalArgumentException("A question is asked EN_TO_ZH, ZH_TO_EN or CLOZE, not " + direction);
        }
    }

    /** An explanation of the word alone, without a learner answer. */
    public static ExplanationRequest of(WordCard word) {
        return new ExplanationRequest(word, null, null);
    }

    /** Whether the learner answered (possibly with an empty answer). */
    public boolean hasAnswer() {
        return typedAnswer != null && direction != null;
    }

    /**
     * The typed answer as it matters for the explanation: trimmed, spaces collapsed, lower case.
     * Answers that differ only in case or spacing get the same explanation.
     */
    public String normalizedAnswer() {
        return typedAnswer == null ? "" : typedAnswer.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
