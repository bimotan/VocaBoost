package com.vocabtrainer.domain;

/** Whether the dictionaries confirmed that a word exists before it is added. */
public enum VerificationStatus {
    /** A dictionary has the word. */
    VERIFIED,
    /** Every dictionary answered that it does not have the word. */
    UNVERIFIED,
    /** A dictionary could not be asked (no network, timeout, refused), so nothing is known either way. */
    UNCHECKED
}
