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
 * @param focus       what the explanation is for; null reads as {@link Focus#EXPLANATION}
 */
public record ExplanationRequest(WordCard word, String typedAnswer, ReviewMode direction, Focus focus) {
    /** What the explanation is for. */
    public enum Focus {
        /** The meaning, feedback on the learner's answer, a memory tip and an example. */
        EXPLANATION,
        /** A word the learner keeps forgetting (a leech): above all a memory aid. */
        MEMORY_AID
    }

    public ExplanationRequest {
        Objects.requireNonNull(word, "word");
        if (direction != null && !direction.isDirection()) {
            throw new IllegalArgumentException("A question is asked EN_TO_ZH, ZH_TO_EN or CLOZE, not " + direction);
        }
        focus = focus == null ? Focus.EXPLANATION : focus;
    }

    /** An explanation of the learner's answer, see {@link Focus#EXPLANATION}. */
    public ExplanationRequest(WordCard word, String typedAnswer, ReviewMode direction) {
        this(word, typedAnswer, direction, Focus.EXPLANATION);
    }

    /** An explanation of the word alone, without a learner answer. */
    public static ExplanationRequest of(WordCard word) {
        return new ExplanationRequest(word, null, null);
    }

    /** A memory aid for a word the learner keeps forgetting, without a learner answer. */
    public static ExplanationRequest memoryAid(WordCard word) {
        return new ExplanationRequest(word, null, null, Focus.MEMORY_AID);
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
