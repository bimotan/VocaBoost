package com.vocabtrainer.service;

import com.vocabtrainer.service.csv.WordColumns;

import java.util.Optional;

/**
 * How to import a word list.
 *
 * @param columns      the columns the user chose in the preview; null to detect them
 * @param onlineLookup whether a word the local dictionaries do not have may be looked up online;
 *                     ignored while offline mode is on
 */
public record WordListOptions(WordColumns columns, boolean onlineLookup) {
    /** Detected columns, local dictionaries only. */
    public static final WordListOptions DETECT = new WordListOptions(null, false);

    public Optional<WordColumns> chosenColumns() {
        return Optional.ofNullable(columns);
    }
}
