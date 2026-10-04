package com.vocabtrainer.domain;

/**
 * A review mode, which is also how a single question is asked: {@link #EN_TO_ZH}, {@link #ZH_TO_EN}
 * or {@link #CLOZE} (the directions a review log records). The Review tab names them through
 * {@code ui.Labels}.
 */
public enum ReviewMode {
    EN_TO_ZH,
    ZH_TO_EN,
    MIXED,
    WEAK_WORDS,
    /** The example sentence with the word blanked out and the Chinese meaning as a hint; the English word is typed. */
    CLOZE;

    /** Whether a single question can be asked this way, as a review log's direction records it. */
    public boolean isDirection() {
        return this == EN_TO_ZH || this == ZH_TO_EN || this == CLOZE;
    }
}
