package com.vocabtrainer.ui;

/** A kind of stored data that a user action changed. Views listen for the kinds they show. */
public enum DataChange {
    /** Words were added, edited, deleted, imported or restored. */
    WORDS,
    /** A review was saved: card schedules, review logs, daily goals, XP and achievements. */
    REVIEWS,
    /** Decks were created, renamed, archived or restored. */
    DECKS,
    /** Dictionary or AI settings or offline mode changed. */
    SETTINGS,
    /**
     * Review settings changed: a new-cards-per-day limit or the day rollover hour, which change what
     * is due today, or the desired retention, which changes the intervals of the next ratings.
     */
    REVIEW_SETTINGS,
    /** The daily goals or the session goal (the review session size) were edited. */
    GOALS
}
