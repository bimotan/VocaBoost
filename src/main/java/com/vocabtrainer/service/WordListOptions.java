package com.vocabtrainer.service;

import com.vocabtrainer.service.csv.WordColumns;

import java.util.Optional;

/**
 * How to import a word list.
 *
 * @param columns      the columns the user chose in the preview; null to detect them
 * @param onlineLookup whether a word the local dictionaries do not have may be looked up online;
 *                     ignored while offline mode is on
 * @param inOtherDecks what to do with a word another active deck already has; null reads as
 *                     {@link InOtherDecks#KEEP_IMPORTED}
 */
public record WordListOptions(WordColumns columns, boolean onlineLookup, InOtherDecks inOtherDecks) {
    /** Detected columns, local dictionaries only. */
    public static final WordListOptions DETECT = new WordListOptions(null, false);

    public WordListOptions {
        inOtherDecks = inOtherDecks == null ? InOtherDecks.KEEP_IMPORTED : inOtherDecks;
    }

    /** Words other decks have are imported as the file has them. */
    public WordListOptions(WordColumns columns, boolean onlineLookup) {
        this(columns, onlineLookup, InOtherDecks.KEEP_IMPORTED);
    }

    public Optional<WordColumns> chosenColumns() {
        return Optional.ofNullable(columns);
    }

    /** These options with {@code choice} for the words other decks have. */
    public WordListOptions with(InOtherDecks choice) {
        return new WordListOptions(columns, onlineLookup, choice);
    }
}
