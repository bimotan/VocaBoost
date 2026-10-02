package com.vocabtrainer.service.cloze;

/**
 * A piece of a sentence: either the word looked for, as it is written there ({@code target}), or
 * the text between its occurrences.
 */
public record SentenceSpan(String text, boolean target) {
    public SentenceSpan {
        text = text == null ? "" : text;
    }
}
