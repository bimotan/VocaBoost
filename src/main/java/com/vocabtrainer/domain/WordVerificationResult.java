package com.vocabtrainer.domain;

import java.util.Locale;

/**
 * Whether the dictionaries know a word.
 *
 * @param source   the dictionary that has the word; empty unless it is {@link VerificationStatus#VERIFIED}
 * @param outcome  how the lookup behind the verification ended, e.g. why it is unchecked
 * @param phonetic the phonetic the dictionary gives for the word as it was asked; empty when it has
 *                 none, the word was not found, or only an inflection of it was (a base form's
 *                 phonetic would be wrong for the form)
 */
public record WordVerificationResult(
    VerificationStatus status,
    String source,
    String message,
    LookupOutcome outcome,
    String phonetic
) {
    public WordVerificationResult {
        phonetic = phonetic == null ? "" : phonetic.strip();
    }

    public WordVerificationResult(VerificationStatus status, String source, String message, LookupOutcome outcome) {
        this(status, source, message, outcome, "");
    }

    public static WordVerificationResult found(String source, String message) {
        return new WordVerificationResult(VerificationStatus.VERIFIED, source, message, LookupOutcome.FOUND);
    }

    /** Every dictionary answered that it does not have the word. */
    public static WordVerificationResult missing(String message) {
        return new WordVerificationResult(VerificationStatus.UNVERIFIED, "", message, LookupOutcome.NOT_FOUND);
    }

    /** A dictionary could not be asked; {@code outcome} says why. */
    public static WordVerificationResult unchecked(LookupOutcome outcome, String message) {
        if (outcome.isAnswer()) {
            throw new IllegalArgumentException(outcome + " is an answer, not a failure to ask");
        }
        return new WordVerificationResult(VerificationStatus.UNCHECKED, "", message, outcome);
    }

    /** The verification a lookup amounts to: found by the source of its first entry, missing or unchecked. */
    public static WordVerificationResult of(DictionaryLookupResult result) {
        if (result.success()) {
            return found(result.entries().get(0).source(), result.message());
        }
        return result.unavailable() ? unchecked(result.outcome(), result.message()) : missing(result.message());
    }

    /**
     * The verification a lookup of {@code english} amounts to, see {@link #of(DictionaryLookupResult)},
     * with the phonetic of the first entry for {@code english} itself (ignoring case).
     */
    public static WordVerificationResult of(String english, DictionaryLookupResult result) {
        WordVerificationResult verification = of(result);
        if (!result.success() || english == null) {
            return verification;
        }
        String asked = english.strip().toLowerCase(Locale.ROOT);
        return result.entries().stream()
            .filter(entry -> entry.english() != null && entry.english().strip().toLowerCase(Locale.ROOT).equals(asked))
            .map(DictionaryEntry::phonetic)
            .filter(phonetic -> phonetic != null && !phonetic.isBlank())
            .findFirst()
            .map(verification::withPhonetic)
            .orElse(verification);
    }

    /** This verification with the dictionary's phonetic for the word. */
    public WordVerificationResult withPhonetic(String phonetic) {
        return new WordVerificationResult(status, source, message, outcome, phonetic);
    }

    public boolean found() {
        return status == VerificationStatus.VERIFIED;
    }
}
