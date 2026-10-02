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
    PRACTICE
}
