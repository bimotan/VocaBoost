package com.vocabtrainer.domain;

/**
 * A review mode, which is also how a single question is asked: {@link #EN_TO_ZH}, {@link #ZH_TO_EN}
 * or {@link #CLOZE} (the directions a review log records).
 */
public enum ReviewMode {
    EN_TO_ZH("英译中", "Enter Chinese meaning"),
    ZH_TO_EN("中译英", "Enter English word"),
    MIXED("混合模式", "Answer Chinese or English based on the current prompt"),
    WEAK_WORDS("弱词模式", "Review weak words first"),
    /** The example sentence with the word blanked out and the Chinese meaning as a hint; the English word is typed. */
    CLOZE("Cloze / 例句填空", "Type the missing word");

    private final String label;
    private final String prompt;

    ReviewMode(String label, String prompt) {
        this.label = label;
        this.prompt = prompt;
    }

    public String getLabel() {
        return label;
    }

    public String getPrompt() {
        return prompt;
    }

    /** Whether a single question can be asked this way, as a review log's direction records it. */
    public boolean isDirection() {
        return this == EN_TO_ZH || this == ZH_TO_EN || this == CLOZE;
    }
}
