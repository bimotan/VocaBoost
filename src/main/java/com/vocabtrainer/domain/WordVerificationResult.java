package com.vocabtrainer.domain;

/**
 * Whether the dictionaries know a word.
 *
 * @param source  the dictionary that has the word; empty unless it is {@link VerificationStatus#VERIFIED}
 * @param outcome how the lookup behind the verification ended, e.g. why it is unchecked
 */
public record WordVerificationResult(
    VerificationStatus status,
    String source,
    String message,
    LookupOutcome outcome
) {
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

    public boolean found() {
        return status == VerificationStatus.VERIFIED;
    }
}
