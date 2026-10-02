package com.vocabtrainer.domain;

/** What a review log records; stored in {@code review_logs.kind}. */
public enum ReviewKind {
    /** The first review of a new card, which introduces it; the new-cards-per-day limit counts these. */
    LEARN,
    /** A scheduled review: a learning step, a review or a relearning step. Logs of older versions read as this. */
    REVIEW,
    /**
     * A card practiced before it was due (Weak Words mode). It does not change the card's memory or
     * schedule, except that a failed answer can bring the due date forward.
     */
    PRACTICE,
    /**
     * A new card the user marked as already known ("Already known") instead of learning it: it went
     * straight into review with a long stability. It is not a review or an answer: it earns no XP and
     * counts towards no goal, accuracy, streak, review badge or new-cards-per-day limit. Versions that
     * do not know this kind read it as a review.
     */
    KNOWN
}
