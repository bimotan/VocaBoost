package com.vocabtrainer.domain;

import java.util.List;
import java.util.Objects;

import static com.vocabtrainer.util.Messages.tr;

/**
 * What a dictionary lookup returned.
 *
 * @param outcome whether the word was found, not found, or the dictionary could not be asked
 * @param message what happened, for the user
 * @param entries the entries found; empty unless the outcome is {@link LookupOutcome#FOUND}
 */
public record DictionaryLookupResult(
    LookupOutcome outcome,
    String message,
    List<DictionaryEntry> entries
) {
    public DictionaryLookupResult {
        Objects.requireNonNull(outcome, "outcome");
        message = message == null ? "" : message;
        entries = List.copyOf(entries);
        if (outcome == LookupOutcome.FOUND && entries.isEmpty()) {
            throw new IllegalArgumentException("A lookup that found the word needs its entries");
        }
        if (outcome != LookupOutcome.FOUND && !entries.isEmpty()) {
            throw new IllegalArgumentException("Only a lookup that found the word has entries");
        }
    }

    public static DictionaryLookupResult success(String message, List<DictionaryEntry> entries) {
        return new DictionaryLookupResult(LookupOutcome.FOUND, message, entries);
    }

    /** The dictionary answered that it does not have the word. */
    public static DictionaryLookupResult notFound(String message) {
        return new DictionaryLookupResult(LookupOutcome.NOT_FOUND, message, List.of());
    }

    /** The dictionary could not be asked; {@code outcome} says why. */
    public static DictionaryLookupResult unavailable(LookupOutcome outcome, String message) {
        if (outcome.isAnswer()) {
            throw new IllegalArgumentException(outcome + " is an answer, not a failure to ask");
        }
        return new DictionaryLookupResult(outcome, message, List.of());
    }

    /** The lookup was cancelled; the caller's thread keeps its interrupt flag. */
    public static DictionaryLookupResult interrupted() {
        return unavailable(LookupOutcome.INTERRUPTED, tr("lookup.outcome.interrupted"));
    }

    /** True when the word was found. */
    public boolean success() {
        return outcome == LookupOutcome.FOUND;
    }

    /** True when the dictionary could not be asked, so the word may exist even though nothing was found. */
    public boolean unavailable() {
        return !outcome.isAnswer();
    }
}
