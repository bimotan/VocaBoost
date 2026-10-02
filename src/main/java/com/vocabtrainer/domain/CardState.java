package com.vocabtrainer.domain;

/** Where a card is in the FSRS learning cycle; stored in {@code words.card_state}. */
public enum CardState {
    /** Never reviewed. */
    NEW,
    /** Seen, and going through the learning steps (minutes apart) before its first interval of days. */
    LEARNING,
    /** Graduated: reviewed at intervals of whole study days. */
    REVIEW,
    /** Forgotten during review, and going through the relearning steps. */
    RELEARNING;

    /** Learning and relearning cards are due at an exact time minutes away; the others by study day. */
    public boolean isLearning() {
        return this == LEARNING || this == RELEARNING;
    }
}
